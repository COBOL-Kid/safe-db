#!/usr/bin/env bash
# Headless Xvfb + xdotool driver for manual agent GUI testing on Linux/WSL (dev-only).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISPLAY_NUM="${AGENT_GUI_DISPLAY:-:99}"
export DISPLAY="$DISPLAY_NUM"
SCREEN_RES="${AGENT_GUI_SCREEN:-1600x1000}"
STATE_DIR="$ROOT/build/agent-gui"
SHOTS_DIR="$STATE_DIR/shots"
if [[ -n "${SAFEDB_AGENT_GUI_DATA_DIR:-}" && -n "${SAFEDB_AGENT_GUI_DATA_DIR//[[:space:]]/}" ]]; then
  DATA_DIR="$SAFEDB_AGENT_GUI_DATA_DIR"
else
  DATA_DIR="$STATE_DIR/data"
fi
APP_LOG="$STATE_DIR/app.log"
XVFB_LOG="$STATE_DIR/xvfb.log"
OPENBOX_LOG="$STATE_DIR/openbox.log"
VNC_LOG="$STATE_DIR/vnc.log"

XVFB_PID_FILE="$STATE_DIR/xvfb.pid"
OPENBOX_PID_FILE="$STATE_DIR/openbox.pid"
VNC_PID_FILE="$STATE_DIR/vnc.pid"
APP_PID_FILE="$STATE_DIR/app.pid"

usage() {
  cat <<'EOF'
Usage: scripts/agent_gui.sh <command> [args...]

Commands:
  up [--vnc] [--foreground]  Start Xvfb, openbox, optional x11vnc (:5900), and Safe-DB GUI.
                             Use --foreground when a runner reaps background children on exit.
  down                       Stop Safe-DB GUI, x11vnc, openbox, and Xvfb (idempotent).
  status                     Show running processes and visible windows.
  screenshot [name] [--window]
                             Capture root screen (or active window) PNG to build/agent-gui/shots/.
  windows                    List visible window IDs, titles, and geometries.
  focus <title-substring>    Activate and raise a matching window.
  click X Y [left|right|double]
                             Move mouse to (X, Y) and click.
  drag X1 Y1 X2 Y2           Drag with left button from (X1, Y1) to (X2, Y2).
  scroll up|down [N [X Y]]   Scroll N wheel ticks (default 3), optionally at (X, Y)
                             (N is required before X Y, e.g. scroll down 5 400 500).
  type <text>                Type text into the focused window.
  key <combo>...             Press key or combo (e.g. ctrl+a, Return, Tab, Escape).
  logs [N]                   Show last N lines of build/agent-gui/app.log (default 50).
  reset                      Stop everything and wipe build/agent-gui/data and shots (overrides outside those paths are kept).

Environment:
  AGENT_GUI_DISPLAY          X display (default :99)
  AGENT_GUI_SCREEN           Xvfb WxH resolution (default 1600x1000)
  SAFEDB_AGENT_GUI_DATA_DIR  App data directory (default build/agent-gui/data)
EOF
}

# Refuse a pid file that is not a live process whose cmdline contains the expected token.
pid_identity_ok() {
  local pid="$1"
  local token="${2:-}"
  [[ -n "$token" ]] || return 1
  [[ "$pid" =~ ^[1-9][0-9]+$ ]] || return 1
  local cmdline_file="/proc/${pid}/cmdline"
  [[ -r "$cmdline_file" ]] || return 1
  local cmdline
  cmdline="$(tr '\0' ' ' <"$cmdline_file" 2>/dev/null)" || return 1
  [[ -n "$cmdline" && "$cmdline" == *"$token"* ]]
}

is_pid_running() {
  local pid_file="$1"
  local token="${2:-}"
  [[ -n "$token" ]] || return 1
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(<"$pid_file")"
  if pid_identity_ok "$pid" "$token"; then
    return 0
  fi
  rm -f -- "$pid_file"
  return 1
}

check_prereqs() {
  local need_vnc="${1:-false}"
  local required=(Xvfb xdotool import openbox setsid)
  if [[ "$need_vnc" == "true" ]]; then
    required+=(x11vnc)
  fi
  local missing=()
  for bin in "${required[@]}"; do
    if ! command -v "$bin" >/dev/null 2>&1; then
      missing+=("$bin")
    fi
  done
  if ((${#missing[@]} > 0)); then
    echo "Missing required tools: ${missing[*]}" >&2
    echo "Install with: sudo apt install xvfb xdotool imagemagick openbox x11vnc" >&2
    exit 1
  fi
}

require_display() {
  if ! xdotool getdisplaygeometry >/dev/null 2>&1; then
    echo "Display $DISPLAY_NUM is not running. Run 'scripts/agent_gui.sh up' first." >&2
    exit 1
  fi
}

is_group_alive() {
  local pgid="$1"
  ps -o stat= -g "$pgid" 2>/dev/null | grep -qEv '^[[:space:]]*(Z|$)'
}

stop_pidfile() {
  local pid_file="$1"
  local token="${2:-}"
  local kill_group="${3:-false}"
  [[ -n "$token" ]] || return 0
  [[ -f "$pid_file" ]] || return 0
  local pid
  pid="$(<"$pid_file")"
  if ! pid_identity_ok "$pid" "$token"; then
    rm -f -- "$pid_file"
    return 0
  fi
  local i=0
  if [[ "$kill_group" == "true" ]]; then
    if is_group_alive "$pid"; then
      kill -TERM -- "-$pid" 2>/dev/null || true
      while is_group_alive "$pid" && ((i < 20)); do
        sleep 0.2
        ((i += 1))
      done
      if is_group_alive "$pid"; then
        kill -KILL -- "-$pid" 2>/dev/null || true
      fi
    fi
  elif kill -0 "$pid" 2>/dev/null; then
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    while kill -0 "$pid" 2>/dev/null && ((i < 20)); do
      sleep 0.2
      ((i += 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
      pkill -KILL -P "$pid" 2>/dev/null || true
      kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  # Keep the pid file when the matched process or its group is still alive.
  if [[ "$kill_group" == "true" ]]; then
    if is_group_alive "$pid" || kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
  elif kill -0 "$pid" 2>/dev/null; then
    return 0
  fi
  rm -f -- "$pid_file"
}

cmd_up() {
  local use_vnc=false
  local foreground=false
  while (($# > 0)); do
    case "$1" in
      --vnc) use_vnc=true ;;
      --foreground) foreground=true ;;
      *)
        echo "Unknown option for up: $1" >&2
        exit 2
        ;;
    esac
    shift
  done

  check_prereqs "$use_vnc"
  mkdir -p "$STATE_DIR" "$SHOTS_DIR" "$DATA_DIR"

  trap cmd_down EXIT
  trap 'exit 1' INT TERM HUP

  if ! is_pid_running "$XVFB_PID_FILE" Xvfb; then
    nohup Xvfb "$DISPLAY_NUM" -screen 0 "${SCREEN_RES}x24" -nolisten tcp -ac \
      >"$XVFB_LOG" 2>&1 &
    echo $! >"$XVFB_PID_FILE"
    local i=0
    until xdotool getdisplaygeometry >/dev/null 2>&1; do
      if ! is_pid_running "$XVFB_PID_FILE" Xvfb; then
        echo "Xvfb exited unexpectedly. Log ($XVFB_LOG):" >&2
        cat "$XVFB_LOG" >&2 || true
        exit 1
      fi
      ((i += 1))
      if ((i >= 50)); then
        echo "Timed out waiting for Xvfb on $DISPLAY_NUM." >&2
        exit 1
      fi
      sleep 0.1
    done
  fi

  if ! is_pid_running "$OPENBOX_PID_FILE" openbox; then
    nohup openbox >"$OPENBOX_LOG" 2>&1 &
    echo $! >"$OPENBOX_PID_FILE"
    sleep 0.3
  fi

  if [[ "$use_vnc" == "true" ]] && ! is_pid_running "$VNC_PID_FILE" x11vnc; then
    nohup x11vnc -display "$DISPLAY_NUM" -localhost -forever -nopw -rfbport 5900 -shared \
      >"$VNC_LOG" 2>&1 &
    echo $! >"$VNC_PID_FILE"
  fi

  if ! is_pid_running "$APP_PID_FILE" runAgentGui; then
    (
      cd "$ROOT"
      export SAFEDB_AGENT_GUI_DATA_DIR="$DATA_DIR"
      exec setsid ./gradlew --no-daemon runAgentGui >"$APP_LOG" 2>&1 </dev/null
    ) &
    echo $! >"$APP_PID_FILE"
  fi

  local win_id=""
  local elapsed=0
  while ((elapsed < 120)); do
    win_id="$(xdotool search --onlyvisible --name "Safe-DB" 2>/dev/null | head -n 1 || true)"
    if [[ -n "$win_id" ]]; then
      break
    fi
    if ! is_pid_running "$APP_PID_FILE" runAgentGui; then
      echo "Safe-DB failed to start. Last 40 lines of $APP_LOG:" >&2
      tail -n 40 "$APP_LOG" >&2 || true
      exit 1
    fi
    sleep 1
    ((elapsed += 1))
  done

  if [[ -z "$win_id" ]]; then
    echo "Timed out after 120s waiting for Safe-DB window. Last 40 lines of $APP_LOG:" >&2
    tail -n 40 "$APP_LOG" >&2 || true
    exit 1
  fi

  xdotool windowmove "$win_id" 0 0 || true
  xdotool windowactivate "$win_id" || true

  echo "Agent GUI ready on DISPLAY=$DISPLAY_NUM ($SCREEN_RES), window=$win_id"
  echo "Data dir: $DATA_DIR"
  if is_pid_running "$VNC_PID_FILE" x11vnc; then
    echo "VNC listening on localhost:5900"
  fi

  if [[ "$foreground" == "true" ]]; then
    while is_pid_running "$APP_PID_FILE" runAgentGui; do
      sleep 1
    done
  else
    trap - EXIT INT TERM HUP
  fi
}

cmd_down() {
  stop_pidfile "$APP_PID_FILE" runAgentGui true
  stop_pidfile "$VNC_PID_FILE" x11vnc
  stop_pidfile "$OPENBOX_PID_FILE" openbox
  stop_pidfile "$XVFB_PID_FILE" Xvfb
  echo "Agent GUI stopped."
}

cmd_windows() {
  require_display
  local ids
  ids="$(xdotool search --onlyvisible --name ".*" 2>/dev/null || true)"
  if [[ -z "$ids" ]]; then
    echo "No visible windows."
    return 0
  fi
  local wid title X Y WIDTH HEIGHT key val
  while IFS= read -r wid; do
    if [[ -z "$wid" ]]; then continue; fi
    title="$(xdotool getwindowname "$wid" 2>/dev/null || true)"
    if [[ -z "$title" ]]; then continue; fi
    X="" Y="" WIDTH="" HEIGHT=""
    while IFS='=' read -r key val || [[ -n "$key" ]]; do
      case "$key" in
        X) X="$val" ;;
        Y) Y="$val" ;;
        WIDTH) WIDTH="$val" ;;
        HEIGHT) HEIGHT="$val" ;;
      esac
    done < <(xdotool getwindowgeometry --shell "$wid" 2>/dev/null || true)
    printf "%s\t%sx%s+%s+%s\t%s\n" "$wid" "${WIDTH:-?}" "${HEIGHT:-?}" "${X:-?}" "${Y:-?}" "$title"
  done <<<"$ids"
}

cmd_status() {
  local label file token spec
  local specs=(
    "xvfb|$XVFB_PID_FILE|Xvfb"
    "openbox|$OPENBOX_PID_FILE|openbox"
    "x11vnc|$VNC_PID_FILE|x11vnc"
    "app|$APP_PID_FILE|runAgentGui"
  )
  for spec in "${specs[@]}"; do
    IFS='|' read -r label file token <<<"$spec"
    if is_pid_running "$file" "$token"; then
      echo "$label: running (pid $(<"$file"))"
    else
      echo "$label: stopped"
    fi
  done
  if xdotool getdisplaygeometry >/dev/null 2>&1; then
    echo "display $DISPLAY_NUM: up"
    cmd_windows
  else
    echo "display $DISPLAY_NUM: down"
  fi
}

cmd_screenshot() {
  require_display
  mkdir -p "$SHOTS_DIR"
  local name=""
  local target_window=false
  while (($# > 0)); do
    case "$1" in
      --window) target_window=true ;;
      -*)
        echo "Unknown option for screenshot: $1" >&2
        exit 2
        ;;
      *)
        if [[ -n "$name" ]]; then
          echo "Unexpected extra argument: $1" >&2
          exit 2
        fi
        name="$1"
        ;;
    esac
    shift
  done

  if [[ -z "$name" ]]; then
    name="$(date +%Y%m%d-%H%M%S)"
  fi
  name="${name%.png}.png"
  local out="$SHOTS_DIR/$(basename "$name")"

  if [[ "$target_window" == "true" ]]; then
    local wid
    wid="$(xdotool getactivewindow 2>/dev/null || true)"
    if [[ -z "$wid" ]]; then
      echo "No active window found on $DISPLAY_NUM." >&2
      exit 1
    fi
    import -display "$DISPLAY_NUM" -window "$wid" "$out"
  else
    import -display "$DISPLAY_NUM" -window root "$out"
  fi
  echo "$out"
}

pick_window_match() {
  local pattern="$1"
  shift
  local ids
  ids="$(xdotool search "$@" --name "$pattern" 2>/dev/null || true)"
  if [[ -z "$ids" ]]; then
    return 0
  fi
  local first="" wid title
  while IFS= read -r wid; do
    if [[ -z "$wid" ]]; then continue; fi
    if [[ -z "$first" ]]; then
      first="$wid"
    fi
    title="$(xdotool getwindowname "$wid" 2>/dev/null || true)"
    if [[ "$title" == "$pattern" ]]; then
      echo "$wid"
      return 0
    fi
  done <<<"$ids"
  echo "$first"
}

cmd_focus() {
  require_display
  local pattern="${1:?focus requires a title substring}"
  local wid
  wid="$(pick_window_match "$pattern" --onlyvisible)"
  if [[ -z "$wid" ]]; then
    wid="$(pick_window_match "$pattern")"
  fi
  if [[ -z "$wid" ]]; then
    echo "No window matching '$pattern' found." >&2
    exit 1
  fi
  xdotool windowactivate "$wid"
  xdotool windowraise "$wid"
  local title
  title="$(xdotool getwindowname "$wid" 2>/dev/null || true)"
  echo "Focused $wid ($title)"
}

cmd_click() {
  require_display
  local x="${1:?click requires X}"
  local y="${2:?click requires Y}"
  local mode="${3:-left}"
  case "$mode" in
    left)
      xdotool mousemove --sync "$x" "$y" click 1
      ;;
    right)
      xdotool mousemove --sync "$x" "$y" click 3
      ;;
    double)
      xdotool mousemove --sync "$x" "$y" click --repeat 2 --delay 50 1
      ;;
    *)
      echo "Unknown click mode '$mode' (expected left|right|double)" >&2
      exit 2
      ;;
  esac
}

cmd_drag() {
  require_display
  local x1="${1:?drag requires X1}"
  local y1="${2:?drag requires Y1}"
  local x2="${3:?drag requires X2}"
  local y2="${4:?drag requires Y2}"
  xdotool mousemove --sync "$x1" "$y1" mousedown 1 mousemove --sync "$x2" "$y2" mouseup 1
}

cmd_scroll() {
  if (($# != 1 && $# != 2 && $# != 4)); then
    echo "Usage: scripts/agent_gui.sh scroll up|down [N [X Y]] (e.g. scroll down 5 400 500)" >&2
    exit 2
  fi
  local dir="$1"
  local btn
  case "$dir" in
    up) btn=4 ;;
    down) btn=5 ;;
    *)
      echo "Unknown scroll direction '$dir' (expected up|down)" >&2
      exit 2
      ;;
  esac
  require_display
  local count="${2:-3}"
  if (($# == 4)); then
    xdotool mousemove --sync "$3" "$4"
  fi
  xdotool click --repeat "$count" --delay 30 "$btn"
}

cmd_type() {
  if (($# != 1)); then
    echo "Usage: scripts/agent_gui.sh type <text> (quote text containing spaces)" >&2
    exit 2
  fi
  require_display
  xdotool type --clearmodifiers --delay 15 -- "$1"
}

cmd_key() {
  require_display
  if (($# == 0)); then
    echo "key requires at least one key or combo" >&2
    exit 2
  fi
  xdotool key --clearmodifiers -- "$@"
}

cmd_logs() {
  local lines="${1:-50}"
  if [[ ! -f "$APP_LOG" ]]; then
    echo "No log file at $APP_LOG"
    return 0
  fi
  tail -n "$lines" "$APP_LOG"
}

normalize_path() {
  local input="${1:-}"
  [[ -n "$input" ]] || return 1
  command -v realpath >/dev/null 2>&1 || return 1
  realpath -m -- "$input"
}

is_within() {
  local child="$1" parent="$2"
  [[ "$child" == "$parent" || "$child" == "$parent"/* ]]
}

remove_agent_gui_reset_targets() {
  local state_dir="${1:-}"
  local shots_dir="${2:-}"
  local data_dir="${3:-}"
  if [[ -z "$state_dir" || -z "$shots_dir" || -z "$data_dir" || "$state_dir" == "/" || "$shots_dir" == "/" || "$data_dir" == "/" ]]; then
    echo "Refusing to reset with an empty or root path." >&2
    return 1
  fi
  local managed_data="$state_dir/data"
  local preserve=0
  if [[ "$data_dir" != "$managed_data" && "$data_dir" != "$shots_dir" ]]; then
    local data_norm managed_norm shots_norm
    if data_norm="$(normalize_path "$data_dir")" &&
      managed_norm="$(normalize_path "$managed_data")" &&
      shots_norm="$(normalize_path "$shots_dir")"; then
      if ! is_within "$data_norm" "$managed_norm" && ! is_within "$data_norm" "$shots_norm"; then
        preserve=1
      fi
    else
      preserve=1
    fi
  fi
  rm -rf -- "$managed_data" "$shots_dir"
  echo "Removed $managed_data and $shots_dir."
  if ((preserve)); then
    echo "Not removing $data_dir (outside $managed_data and $shots_dir)."
  fi
}

cmd_reset() {
  cmd_down
  remove_agent_gui_reset_targets "$STATE_DIR" "$SHOTS_DIR" "$DATA_DIR"
}

main() {
  local cmd="${1:-}"
  if [[ -z "$cmd" || "$cmd" == "-h" || "$cmd" == "--help" ]]; then
    usage
    exit 0
  fi
  shift
  case "$cmd" in
    up) cmd_up "$@" ;;
    down) cmd_down "$@" ;;
    status) cmd_status "$@" ;;
    screenshot) cmd_screenshot "$@" ;;
    windows) cmd_windows "$@" ;;
    focus) cmd_focus "$@" ;;
    click) cmd_click "$@" ;;
    drag) cmd_drag "$@" ;;
    scroll) cmd_scroll "$@" ;;
    type) cmd_type "$@" ;;
    key) cmd_key "$@" ;;
    logs) cmd_logs "$@" ;;
    reset) cmd_reset "$@" ;;
    *)
      echo "Unknown command: $cmd" >&2
      usage >&2
      exit 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
