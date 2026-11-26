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
  if [[ "$SAFEDB_AGENT_GUI_DATA_DIR" == /* ]]; then
    DATA_DIR="$SAFEDB_AGENT_GUI_DATA_DIR"
  elif command -v realpath >/dev/null 2>&1; then
    DATA_DIR="$(realpath -m -- "$SAFEDB_AGENT_GUI_DATA_DIR")"
  else
    DATA_DIR="$PWD/$SAFEDB_AGENT_GUI_DATA_DIR"
  fi
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
  resize W H [title]         Resize the matching window (default title Safe-DB) to W x H pixels.
  logs [N]                   Show last N lines of build/agent-gui/app.log (default 50).
  reset                      Stop everything and wipe build/agent-gui/data and shots (overrides outside those paths are kept).
  smoke [--require-db]       Run an isolated headless Xvfb + Ctrl+K smoke test (seeds Smoke MySQL when reachable).

Environment:
  AGENT_GUI_DISPLAY             X display (default :99)
  AGENT_GUI_SCREEN              Xvfb WxH resolution (default 1600x1000)
  SAFEDB_AGENT_GUI_DATA_DIR     App data directory (default build/agent-gui/data)
  SAFEDB_AGENT_GUI_CREDENTIALS  Credential store backend: memory|file (default memory)
EOF
}

is_safe_pid() {
  local pid="$1"
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$pid" != "1" ]]
}

# Refuse a pid file that is not a live process whose cmdline contains the expected token.
pid_identity_ok() {
  local pid="$1"
  local token="${2:-}"
  [[ -n "$token" ]] || return 1
  is_safe_pid "$pid" || return 1
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

# Live non-zombie process. Does not signal. A missing or zombie pid has exited.
pid_process_alive() {
  local pid="$1"
  local stat state
  is_safe_pid "$pid" || return 1
  { stat="$(<"/proc/$pid/stat")"; } 2>/dev/null || return 1
  state="${stat##*) }"
  state="${state%% *}"
  [[ "$state" == "Z" ]] && return 1
  return 0
}

pidfile_process_alive() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 1
  pid_process_alive "$(<"$pid_file")"
}

check_prereqs() {
  local need_vnc="${1:-false}"
  local need_identify="${2:-false}"
  local required=(Xvfb xdotool import openbox setsid)
  if [[ "$need_vnc" == "true" ]]; then
    required+=(x11vnc)
  fi
  if [[ "$need_identify" == "true" ]]; then
    required+=(identify)
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

STOP_TERM_POLLS=20

is_stop_target_alive() {
  local pid="$1"
  local kill_group="$2"
  if [[ "$kill_group" == "true" ]]; then
    is_group_alive "$pid" || pid_process_alive "$pid"
  else
    pid_process_alive "$pid"
  fi
}

wait_stop_target_dead() {
  local pid="$1"
  local kill_group="$2"
  local attempts="$3"
  local interval="$4"
  local i=0
  while is_stop_target_alive "$pid" "$kill_group" && ((i < attempts)); do
    sleep "$interval"
    ((i += 1))
  done
  ! is_stop_target_alive "$pid" "$kill_group"
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
  if is_stop_target_alive "$pid" "$kill_group"; then
    if [[ "$kill_group" == "true" ]]; then
      kill -TERM -- "-$pid" 2>/dev/null || true
    else
      pkill -TERM -P "$pid" 2>/dev/null || true
      kill -TERM "$pid" 2>/dev/null || true
    fi
    if ! wait_stop_target_dead "$pid" "$kill_group" "$STOP_TERM_POLLS" 0.2; then
      if [[ "$kill_group" == "true" ]]; then
        kill -KILL -- "-$pid" 2>/dev/null || true
      else
        pkill -KILL -P "$pid" 2>/dev/null || true
        kill -KILL "$pid" 2>/dev/null || true
      fi
      wait_stop_target_dead "$pid" "$kill_group" 20 0.1 || return 0
    fi
  fi
  rm -f -- "$pid_file"
}

start_agent_gui_session() {
  local use_vnc="${1:-false}"
  mkdir -p "$STATE_DIR" "$SHOTS_DIR" "$DATA_DIR"

  if ! is_pid_running "$XVFB_PID_FILE" Xvfb; then
    nohup Xvfb "$DISPLAY_NUM" -screen 0 "${SCREEN_RES}x24" -nolisten tcp -ac \
      >"$XVFB_LOG" 2>&1 &
    echo $! >"$XVFB_PID_FILE"
    local i=0
    until xdotool getdisplaygeometry >/dev/null 2>&1; do
      if ! pidfile_process_alive "$XVFB_PID_FILE"; then
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
      if [[ -n "${SAFEDB_AGENT_GUI_CREDENTIALS:-}" ]]; then
        export SAFEDB_AGENT_GUI_CREDENTIALS
      fi
      exec setsid ./gradlew --no-daemon runAgentGui >"$APP_LOG" 2>&1 </dev/null
    ) &
    echo $! >"$APP_PID_FILE"
  fi

  local win_id=""
  local elapsed=0
  while ((elapsed < 120)); do
    win_id="$(pick_window_match "Safe-DB" --onlyvisible)"
    if [[ -n "$win_id" ]]; then
      break
    fi
    if ! pidfile_process_alive "$APP_PID_FILE"; then
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

  trap 'trap "" INT TERM HUP; cmd_down' EXIT
  trap 'exit 1' INT TERM HUP

  start_agent_gui_session "$use_vnc"

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

cmd_resize() {
  local width="${1:?resize requires WIDTH}" height="${2:?resize requires HEIGHT}"
  local pattern="${3:-Safe-DB}"
  if [[ ! "$width" =~ ^[1-9][0-9]*$ || ! "$height" =~ ^[1-9][0-9]*$ ]]; then
    echo "resize WIDTH and HEIGHT must be positive integers." >&2
    exit 2
  fi
  require_display
  local wid
  wid="$(pick_window_match "$pattern" --onlyvisible)"
  if [[ -z "$wid" ]]; then
    echo "No window matching '$pattern' found." >&2
    exit 1
  fi
  xdotool windowsize "$wid" "$width" "$height"
  echo "Resized $wid to ${width}x${height}"
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

pick_smoke_display() {
  if [[ -n "${AGENT_GUI_DISPLAY:-}" ]]; then
    printf '%s\n' "$AGENT_GUI_DISPLAY"
    return 0
  fi
  local n
  for n in $(seq 99 120); do
    if [[ ! -e "/tmp/.X${n}-lock" && ! -S "/tmp/.X11-unix/X${n}" ]]; then
      printf ':%s\n' "$n"
      return 0
    fi
  done
  printf ':99\n'
}

is_tcp_reachable() {
  local host="$1" port="$2"
  timeout 2 bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' _ "$host" "$port" >/dev/null 2>&1
}

find_repo_mcp_jre_java() {
  local npm_root="$1"
  local candidate
  shopt -s nullglob
  for candidate in "$npm_root/@safe-db/mcp-"*"/jre/bin/java" "$npm_root/@safe-db/mcp-"*"/jre/bin/java.exe"; do
    if [[ -e "$candidate" ]]; then
      shopt -u nullglob
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  shopt -u nullglob
  return 1
}

find_repo_mcp_shadow_jar() {
  local libs_dir="$1"
  local candidate latest=""
  shopt -s nullglob
  for candidate in "$libs_dir"/safe-db-mcp-*-all.jar; do
    if [[ -f "$candidate" ]]; then
      latest="$candidate"
    fi
  done
  shopt -u nullglob
  if [[ -n "$latest" ]]; then
    printf '%s\n' "$latest"
    return 0
  fi
  return 1
}

resolve_repo_mcp_cli() {
  local npm_root="$ROOT/mcp/build/npm"
  local cli_js="$npm_root/@safe-db/mcp/cli.js"
  if command -v node >/dev/null 2>&1; then
    if [[ ! -f "$cli_js" ]] || ! find_repo_mcp_jre_java "$npm_root" >/dev/null; then
      (cd "$ROOT" && ./gradlew --no-daemon :mcp:assembleNpm >&2) || true
    fi
    if [[ -f "$cli_js" ]] && find_repo_mcp_jre_java "$npm_root" >/dev/null; then
      printf 'node\n%s\n%s\n' "$cli_js" "$npm_root"
      return 0
    fi
  fi

  local libs_dir="$ROOT/mcp/build/libs"
  local jar_path=""
  if ! jar_path="$(find_repo_mcp_shadow_jar "$libs_dir")"; then
    (cd "$ROOT" && ./gradlew --no-daemon :mcp:shadowJar >&2)
    jar_path="$(find_repo_mcp_shadow_jar "$libs_dir")" || {
      echo "Could not locate repo-built safe-db-mcp-*-all.jar in $libs_dir" >&2
      return 1
    }
  fi
  printf 'jar\n%s\n\n' "$jar_path"
}

run_repo_mcp_cli() {
  local resolved mode="" target="" node_path=""
  resolved="$(resolve_repo_mcp_cli)" || return $?
  {
    IFS= read -r mode || true
    IFS= read -r target || true
    IFS= read -r node_path || true
  } <<<"$resolved"
  case "$mode" in
    node)
      NODE_PATH="$node_path" node "$target" "$@"
      ;;
    jar)
      java -jar "$target" "$@"
      ;;
    *)
      echo "Unsupported MCP CLI launch mode: $mode" >&2
      return 1
      ;;
  esac
}

validate_smoke_screenshots() {
  local min_count="$1"
  shift
  local files=("$@")
  if ((${#files[@]} < min_count)); then
    echo "Expected at least $min_count smoke screenshots, found ${#files[@]}." >&2
    return 1
  fi
  local -a hashes=()
  local file magic ident w h stddev hash existing
  for file in "${files[@]}"; do
    if [[ ! -s "$file" ]]; then
      echo "Smoke screenshot is missing or empty: $file" >&2
      return 1
    fi
    magic="$(od -An -tx1 -N8 "$file" | tr -d ' \n')"
    if [[ "$magic" != "89504e470d0a1a0a" ]]; then
      echo "Smoke screenshot is not a valid PNG ($magic): $file" >&2
      return 1
    fi
    ident="$(identify -format '%w %h %[fx:standard_deviation] %#' "$file")" || {
      echo "identify failed on smoke screenshot: $file" >&2
      return 1
    }
    read -r w h stddev hash <<<"$ident"
    if [[ ! "$w" =~ ^[0-9]+$ || ! "$h" =~ ^[0-9]+$ ]] || ((w <= 0 || h <= 0)); then
      echo "Invalid screenshot dimensions ($w x $h): $file" >&2
      return 1
    fi
    if ! awk -v s="$stddev" 'BEGIN { exit !(s + 0 > 0) }'; then
      echo "Smoke screenshot appears blank (stddev=$stddev): $file" >&2
      return 1
    fi
    for existing in "${hashes[@]+"${hashes[@]}"}"; do
      if [[ "$existing" == "$hash" ]]; then
        echo "Smoke screenshot is identical to another capture (hash=$hash): $file" >&2
        return 1
      fi
    done
    hashes+=("$hash")
  done
}

validate_smoke_log() {
  local log_file="$1"
  local expect_file_creds="${2:-false}"
  if [[ ! -s "$log_file" ]]; then
    echo "Smoke app log is missing or empty: $log_file" >&2
    return 1
  fi
  if grep -E -q 'Exception in thread|BUILD FAILED|UnsatisfiedLinkError|SIGSEGV|OutOfMemoryError|safe-db agent-gui:|PasswordNotFoundException|Caused by:' "$log_file"; then
    echo "Smoke app log contains error markers ($log_file):" >&2
    tail -n 40 "$log_file" >&2 || true
    return 1
  fi
  if [[ "$expect_file_creds" == "true" ]] && grep -q 'WARN: using in-memory credential store' "$log_file"; then
    echo "Expected file-backed credential store, but log reported in-memory store ($log_file)." >&2
    return 1
  fi
}

smoke_palette_navigate() {
  local query="$1"
  local shot_name="$2"
  local settle_seconds="${3:-0.8}"
  cmd_focus "Safe-DB" >/dev/null
  sleep 0.2
  cmd_key Tab
  sleep 0.2
  cmd_key ctrl+k
  sleep 0.5
  cmd_key Tab
  sleep 0.2
  cmd_type "$query"
  sleep 0.3
  cmd_key Return
  sleep "$settle_seconds"
  cmd_screenshot "$shot_name" >/dev/null
}

cleanup_smoke() {
  cmd_down >/dev/null 2>&1 || true
  if [[ -n "${SMOKE_DIR:-}" && -d "$SMOKE_DIR" ]]; then
    rm -rf -- "$SMOKE_DIR"
  fi
}

cmd_smoke() {
  local require_db=false
  while (($# > 0)); do
    case "$1" in
      --require-db) require_db=true ;;
      -*)
        echo "Unknown option for smoke: $1" >&2
        exit 2
        ;;
      *)
        echo "Unexpected argument for smoke: $1" >&2
        exit 2
        ;;
    esac
    shift
  done

  check_prereqs false true

  SMOKE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/safedb-agent-gui-smoke.XXXXXX")"
  STATE_DIR="$SMOKE_DIR/state"
  SHOTS_DIR="$SMOKE_DIR/shots"
  local smoke_xdg="$SMOKE_DIR/xdg"
  DATA_DIR="$smoke_xdg/com.safedb.mcp"
  APP_LOG="$STATE_DIR/app.log"
  XVFB_LOG="$STATE_DIR/xvfb.log"
  OPENBOX_LOG="$STATE_DIR/openbox.log"
  VNC_LOG="$STATE_DIR/vnc.log"
  XVFB_PID_FILE="$STATE_DIR/xvfb.pid"
  OPENBOX_PID_FILE="$STATE_DIR/openbox.pid"
  VNC_PID_FILE="$STATE_DIR/vnc.pid"
  APP_PID_FILE="$STATE_DIR/app.pid"
  DISPLAY_NUM="$(pick_smoke_display)"
  export DISPLAY="$DISPLAY_NUM"

  trap 'trap "" INT TERM HUP; cleanup_smoke' EXIT
  trap 'exit 1' INT TERM HUP

  mkdir -p "$STATE_DIR" "$SHOTS_DIR" "$DATA_DIR"

  local mysql_host="${SAFEDB_TEST_MYSQL_HOST:-127.0.0.1}"
  local mysql_port="${SAFEDB_TEST_MYSQL_PORT:-3306}"
  local mysql_user="${SAFEDB_TEST_MYSQL_USER:-safedb}"
  local mysql_password="${SAFEDB_TEST_MYSQL_PASSWORD-safedb}"
  local mysql_db="${SAFEDB_TEST_MYSQL_DATABASE:-safedb_test}"
  local seeded=false

  if is_tcp_reachable "$mysql_host" "$mysql_port"; then
    local pw_file="$SMOKE_DIR/mysql.pw"
    printf '%s' "$mysql_password" >"$pw_file"
    chmod 0600 "$pw_file"
    if (
      unset SAFEDB_KEYCHAIN_BACKEND
      export XDG_DATA_HOME="$smoke_xdg"
      run_repo_mcp_cli setup \
        --name "Smoke MySQL" \
        --dialect mysql \
        --host "$mysql_host" \
        --port "$mysql_port" \
        --database "$mysql_db" \
        --username "$mysql_user" \
        --password-file "$pw_file" \
        --transport disabled >/dev/null
    ); then
      seeded=true
    elif [[ "$require_db" == "true" ]]; then
      echo "Failed to seed Smoke MySQL at $mysql_host:$mysql_port via repo MCP CLI." >&2
      exit 1
    else
      echo "Warning: Smoke MySQL setup failed at $mysql_host:$mysql_port; continuing offline." >&2
    fi
  elif [[ "$require_db" == "true" ]]; then
    echo "Required MySQL fixture is not reachable at $mysql_host:$mysql_port." >&2
    exit 1
  fi

  export SAFEDB_AGENT_GUI_DATA_DIR="$DATA_DIR"
  export SAFEDB_AGENT_GUI_CREDENTIALS="file"

  start_agent_gui_session false
  sleep 1.5
  cmd_screenshot "smoke-01-home" >/dev/null
  smoke_palette_navigate "Go to Connections" "smoke-02-connections" 0.8
  if [[ "$seeded" == "true" ]]; then
    smoke_palette_navigate "Explore: Smoke MySQL" "smoke-03-builder" 2.5
    smoke_palette_navigate "Go to SQL" "smoke-04-sql" 0.8
    smoke_palette_navigate "Go to Map" "smoke-05-map" 1.0
  else
    smoke_palette_navigate "Go to Query Builder" "smoke-03-builder" 0.8
    smoke_palette_navigate "Go to SQL" "smoke-04-sql" 0.8
    smoke_palette_navigate "Go to History" "smoke-05-history" 0.8
  fi

  validate_smoke_screenshots 5 "$SHOTS_DIR"/smoke-*.png
  validate_smoke_log "$APP_LOG" true
  echo "Agent GUI smoke passed (5 screenshots, seeded=$seeded)."
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
    resize) cmd_resize "$@" ;;
    logs) cmd_logs "$@" ;;
    reset) cmd_reset "$@" ;;
    smoke) cmd_smoke "$@" ;;
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
