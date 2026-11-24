#!/usr/bin/env bash
# Regression tests for agent_gui.sh reset targeting, PID guards, window matching, and CLI checks.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/safedb-agent-gui-reset-test.XXXXXX")"
SLEEP_PIDS=()
GROUP_PIDS=()
cleanup_test() {
  local pid pgid i
  for pgid in "${GROUP_PIDS[@]}"; do
    if declare -F is_safe_pid >/dev/null && is_safe_pid "$pgid" && declare -F is_group_alive >/dev/null && is_group_alive "$pgid"; then
      builtin kill -TERM -- "-$pgid" 2>/dev/null || true
      for ((i = 0; i < 20; i++)); do
        is_group_alive "$pgid" || break
        sleep 0.05
      done
      if is_group_alive "$pgid"; then
        builtin kill -KILL -- "-$pgid" 2>/dev/null || true
      fi
    fi
  done
  for pid in "${SLEEP_PIDS[@]}"; do
    if [[ -n "$pid" ]] && builtin kill -0 "$pid" 2>/dev/null; then
      builtin kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  for pid in "${SLEEP_PIDS[@]}"; do
    builtin wait "$pid" 2>/dev/null || true
  done
  rm -rf "$TEST_ROOT"
}
trap cleanup_test EXIT

# shellcheck source=scripts/agent_gui.sh
source "$REPO_ROOT/scripts/agent_gui.sh"

KILL_LOG="$TEST_ROOT/kill-args"
: >"$KILL_LOG"

# Record kill arguments and refuse pid 1 / process group -1. Delegates to the builtin.
kill() {
  local arg
  for arg in "$@"; do
    if [[ "$arg" == "1" || "$arg" == "-1" ]]; then
      fail "refusing to signal unsafe pid argument: $arg"
    fi
  done
  printf '%s\n' "$*" >>"$KILL_LOG"
  builtin kill "$@"
}

# Point stop helpers at temp pid files before any stop call.
XVFB_PID_FILE="$TEST_ROOT/xvfb.pid"
OPENBOX_PID_FILE="$TEST_ROOT/openbox.pid"
VNC_PID_FILE="$TEST_ROOT/vnc.pid"
APP_PID_FILE="$TEST_ROOT/app.pid"

fail() {
  echo "agent_gui reset regression failed: $*" >&2
  exit 1
}

assert_exists() {
  [[ -e "$1" || -L "$1" ]] || fail "expected path to exist: $1"
}

assert_missing() {
  [[ ! -e "$1" && ! -L "$1" ]] || fail "expected path to be removed: $1"
}

assert_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    fail "$label: expected output to contain '$needle', got: $haystack"
  fi
}

assert_not_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    fail "$label: expected output not to contain '$needle', got: $haystack"
  fi
}

setup_state() {
  local base="$1"
  mkdir -p "$base/data" "$base/shots"
  printf 'data\n' >"$base/data/item.db"
  printf 'shot\n' >"$base/shots/screen.png"
  printf 'log\n' >"$base/app.log"
}

reset_kill_log() {
  : >"$KILL_LOG"
}

assert_no_kill() {
  if [[ -s "$KILL_LOG" ]]; then
    fail "$1: expected no signal, got: $(<"$KILL_LOG")"
  fi
}

wait_pids_dead() {
  local label="$1"
  shift
  local i pid alive
  for ((i = 0; i < 50; i++)); do
    alive=false
    for pid in "$@"; do
      if pid_process_alive "$pid"; then
        alive=true
        break
      fi
    done
    [[ "$alive" == "false" ]] && return 0
    sleep 0.1
  done
  fail "$label: process still alive ($*)"
}

wait_for_cmdline_token() {
  local pid="$1"
  local token="$2"
  local i cmdline
  for ((i = 0; i < 50; i++)); do
    if [[ -r "/proc/$pid/cmdline" ]]; then
      cmdline="$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)"
      if [[ "$cmdline" == *"$token"* ]]; then
        return 0
      fi
    fi
    sleep 0.05
  done
  fail "cmdline for $pid did not contain '$token'"
}

start_sleep() {
  local name="${1:-}"
  if [[ -n "$name" ]]; then
    (exec -a "$name" sleep 30) &
  else
    sleep 30 &
  fi
  LAST_SLEEP_PID=$!
  SLEEP_PIDS+=("$LAST_SLEEP_PID")
  if [[ -n "$name" ]]; then
    wait_for_cmdline_token "$LAST_SLEEP_PID" "$name"
  else
    wait_for_cmdline_token "$LAST_SLEEP_PID" sleep
  fi
}

# Double-fork a setsid session leader with a child so it mirrors cmd_up / cmd_down separation.
start_session_group() {
  local token="$1"
  local leader_file="$2"
  local child_file="$3"
  rm -f -- "$leader_file" "$child_file"
  (
    (
      exec setsid bash -c '
        sleep 30 &
        printf "%s\n" "$!" >"$1"
        printf "%s\n" "$$" >"$2"
        exec -a "$3" sleep 30
      ' _ "$child_file" "$leader_file" "$token"
    ) >/dev/null 2>&1 </dev/null &
  )
  local i
  for ((i = 0; i < 50; i++)); do
    if [[ -s "$leader_file" && -s "$child_file" ]]; then
      break
    fi
    sleep 0.05
  done
  [[ -s "$leader_file" && -s "$child_file" ]] || fail "session group did not record pids"
  LAST_GROUP_LEADER="$(<"$leader_file")"
  LAST_GROUP_CHILD="$(<"$child_file")"
  GROUP_PIDS+=("$LAST_GROUP_LEADER")
  wait_for_cmdline_token "$LAST_GROUP_LEADER" "$token"
}

assert_kill_targets_only() {
  local allowed="$1"
  local label="$2"
  local line arg saw_allowed=false
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    for arg in $line; do
      if [[ "$arg" =~ ^[0-9]+$ ]]; then
        [[ "$arg" == "$allowed" ]] || fail "$label: kill targeted unexpected pid '$arg': $line"
        saw_allowed=true
      elif [[ "$arg" =~ ^-[1-9][0-9]*$ ]]; then
        [[ "$arg" == "-$allowed" ]] || fail "$label: kill targeted unexpected group '$arg': $line"
        saw_allowed=true
      fi
    done
  done <"$KILL_LOG"
  [[ "$saw_allowed" == "true" ]] || fail "$label: expected a signal to pid $allowed"
}

test_reset_targets() {
  local out args a1 a2 a3

  # 1. Override equal to $state/data: data and shots removed, sibling app.log kept, no Not removing.
  local c1="$TEST_ROOT/case1"
  setup_state "$c1"
  out="$(remove_agent_gui_reset_targets "$c1" "$c1/shots" "$c1/data")"
  assert_missing "$c1/data"
  assert_missing "$c1/shots"
  assert_exists "$c1/app.log"
  assert_not_contains "$out" "Not removing" "case 1"

  # 2. Override is an outside directory with a marker: managed wiped, marker kept, printed.
  local c2="$TEST_ROOT/case2"
  local c2_outside="$TEST_ROOT/case2-outside"
  setup_state "$c2"
  mkdir -p "$c2_outside"
  printf 'keep\n' >"$c2_outside/marker"
  out="$(remove_agent_gui_reset_targets "$c2" "$c2/shots" "$c2_outside")"
  assert_missing "$c2/data"
  assert_missing "$c2/shots"
  assert_exists "$c2_outside/marker"
  assert_contains "$out" "Not removing" "case 2"
  assert_contains "$out" "$c2_outside" "case 2 path"

  # 3. Override is a symlink outside the wipe set whose target has a marker: link and target kept.
  local c3="$TEST_ROOT/case3"
  local c3_target="$TEST_ROOT/case3-target"
  local c3_link="$TEST_ROOT/case3-link"
  setup_state "$c3"
  mkdir -p "$c3_target"
  printf 'keep\n' >"$c3_target/marker"
  ln -s "$c3_target" "$c3_link"
  out="$(remove_agent_gui_reset_targets "$c3" "$c3/shots" "$c3_link")"
  assert_missing "$c3/data"
  assert_missing "$c3/shots"
  assert_exists "$c3_link"
  assert_exists "$c3_target/marker"
  assert_contains "$out" "Not removing" "case 3"

  # 4. $state/data itself is a symlink to an outside directory: symlink unlinked, target kept.
  local c4="$TEST_ROOT/case4"
  local c4_target="$TEST_ROOT/case4-target"
  mkdir -p "$c4/shots" "$c4_target"
  printf 'shot\n' >"$c4/shots/screen.png"
  printf 'keep\n' >"$c4_target/marker"
  ln -s "$c4_target" "$c4/data"
  out="$(remove_agent_gui_reset_targets "$c4" "$c4/shots" "$c4/data")"
  assert_missing "$c4/data"
  assert_missing "$c4/shots"
  assert_exists "$c4_target/marker"
  assert_not_contains "$out" "Not removing" "case 4"

  # 5. Override is $state itself: $state and app.log remain, data and shots gone, printed.
  local c5="$TEST_ROOT/case5"
  setup_state "$c5"
  out="$(remove_agent_gui_reset_targets "$c5" "$c5/shots" "$c5")"
  assert_exists "$c5"
  assert_exists "$c5/app.log"
  assert_missing "$c5/data"
  assert_missing "$c5/shots"
  assert_contains "$out" "Not removing" "case 5"

  # 6. Override is a subdirectory of $state/data: no Not removing line, subdirectory gone.
  local c6="$TEST_ROOT/case6"
  setup_state "$c6"
  mkdir -p "$c6/data/sub"
  printf 'nested\n' >"$c6/data/sub/item"
  out="$(remove_agent_gui_reset_targets "$c6" "$c6/shots" "$c6/data/sub")"
  assert_missing "$c6/data/sub"
  assert_missing "$c6/data"
  assert_missing "$c6/shots"
  assert_not_contains "$out" "Not removing" "case 6"

  # 7. Empty or / arguments return nonzero and remove nothing.
  local c7="$TEST_ROOT/case7"
  setup_state "$c7"
  for args in \
    ":$c7/shots:$c7/data" \
    "$c7::$c7/data" \
    "$c7:$c7/shots:" \
    "/:$c7/shots:$c7/data" \
    "$c7:/:$c7/data" \
    "$c7:$c7/shots:/"; do
    IFS=':' read -r a1 a2 a3 <<<"$args"
    if remove_agent_gui_reset_targets "$a1" "$a2" "$a3" >/dev/null 2>&1; then
      fail "case 7: expected failure for ($a1, $a2, $a3)"
    fi
  done
  assert_exists "$c7/data/item.db"
  assert_exists "$c7/shots/screen.png"
  assert_exists "$c7/app.log"

  # 8. Override inside $shots_dir: wiped cleanly, no Not removing line, app.log preserved.
  local c8="$TEST_ROOT/case8"
  setup_state "$c8"
  mkdir -p "$c8/shots/nested-data"
  printf 'nested\n' >"$c8/shots/nested-data/item.db"
  out="$(remove_agent_gui_reset_targets "$c8" "$c8/shots" "$c8/shots/nested-data")"
  assert_missing "$c8/shots/nested-data"
  assert_missing "$c8/shots"
  assert_missing "$c8/data"
  assert_exists "$c8/app.log"
  assert_not_contains "$out" "Not removing" "case 8"

  # 9. Override using .. traversal escaping $state/data: managed wiped, outside target kept, printed.
  local c9="$TEST_ROOT/case9"
  local c9_escaped="$TEST_ROOT/case9-escaped"
  setup_state "$c9"
  mkdir -p "$c9_escaped"
  printf 'keep\n' >"$c9_escaped/marker"
  out="$(remove_agent_gui_reset_targets "$c9" "$c9/shots" "$c9/data/../../case9-escaped")"
  assert_missing "$c9/data"
  assert_missing "$c9/shots"
  assert_exists "$c9_escaped/marker"
  assert_contains "$out" "Not removing" "case 9"
}

test_cmd_reset_composition() {
  # STATE_DIR is a temp dir, never the real build/agent-gui tree.
  rm -f -- "$XVFB_PID_FILE" "$OPENBOX_PID_FILE" "$VNC_PID_FILE" "$APP_PID_FILE"
  reset_kill_log

  local r_out="$TEST_ROOT/reset-outside"
  local r_out_data="$TEST_ROOT/reset-outside-data"
  setup_state "$r_out"
  mkdir -p "$r_out_data"
  printf 'keep\n' >"$r_out_data/marker"
  STATE_DIR="$r_out"
  SHOTS_DIR="$r_out/shots"
  DATA_DIR="$r_out_data"
  local out
  out="$(cmd_reset)"
  assert_missing "$r_out/data"
  assert_missing "$r_out/shots"
  assert_exists "$r_out/app.log"
  assert_exists "$r_out_data/marker"
  assert_contains "$out" "Not removing" "cmd_reset outside"
  assert_contains "$out" "$r_out_data" "cmd_reset outside path"
  assert_no_kill "cmd_reset outside"
}

test_pid_and_kill_boundaries() {
  # is_safe_pid accepts single-digit PIDs >= 2 (fresh PID namespaces) and rejects 1/non-PIDs.
  local good_pid bad_pid
  for good_pid in 2 9 42; do
    is_safe_pid "$good_pid" || fail "is_safe_pid rejected valid pid '$good_pid'"
  done
  for bad_pid in "" 0 1 01 -5 abc; do
    if is_safe_pid "$bad_pid"; then
      fail "is_safe_pid accepted unsafe pid '$bad_pid'"
    fi
  done

  # Pid 1 is not a signal target, even as a process group.
  reset_kill_log
  printf '1\n' >"$XVFB_PID_FILE"
  stop_pidfile "$XVFB_PID_FILE" Xvfb true
  assert_missing "$XVFB_PID_FILE"
  assert_no_kill "pid 1"
  [[ -d "/proc/$$" ]] || fail "test process is not alive"

  # Live process whose cmdline lacks the expected token is not signaled.
  reset_kill_log
  start_sleep
  local mismatch_pid="$LAST_SLEEP_PID"
  printf '%s\n' "$mismatch_pid" >"$XVFB_PID_FILE"
  if is_pid_running "$XVFB_PID_FILE" Xvfb; then
    fail "mismatched cmdline reported running"
  fi
  printf '%s\n' "$mismatch_pid" >"$XVFB_PID_FILE"
  stop_pidfile "$XVFB_PID_FILE" Xvfb
  assert_no_kill "mismatched cmdline"
  builtin kill -0 "$mismatch_pid" || fail "mismatched sleep died"
  assert_missing "$XVFB_PID_FILE"

  # Matched cmdline may be signaled; unrelated sleep is untouched.
  reset_kill_log
  start_sleep Xvfb
  local matched_pid="$LAST_SLEEP_PID"
  start_sleep
  local other_pid="$LAST_SLEEP_PID"
  printf '%s\n' "$matched_pid" >"$XVFB_PID_FILE"
  is_pid_running "$XVFB_PID_FILE" Xvfb || fail "Xvfb-named sleep was not running"
  stop_pidfile "$XVFB_PID_FILE" Xvfb
  wait_pids_dead "matched sleep" "$matched_pid"
  assert_missing "$XVFB_PID_FILE"
  builtin kill -0 "$other_pid" || fail "unrelated sleep died"
  assert_kill_targets_only "$matched_pid" "matched cmdline"

  # Non-numeric pid text is removed and not signaled.
  reset_kill_log
  printf 'not-a-pid\n' >"$OPENBOX_PID_FILE"
  stop_pidfile "$OPENBOX_PID_FILE" openbox true
  assert_missing "$OPENBOX_PID_FILE"
  assert_no_kill "non-numeric pid"

  # pidfile_process_alive tracks live/missing/exited pids without deleting not-yet-exec'd pids.
  reset_kill_log
  start_sleep
  local starting_pid="$LAST_SLEEP_PID"
  printf '%s\n' "$starting_pid" >"$XVFB_PID_FILE"
  pidfile_process_alive "$XVFB_PID_FILE" || fail "pidfile_process_alive missed live pid"
  assert_exists "$XVFB_PID_FILE"
  if is_pid_running "$XVFB_PID_FILE" Xvfb; then
    fail "not-yet-exec'd sleep reported as already our Xvfb"
  fi
  assert_missing "$XVFB_PID_FILE"
  assert_no_kill "already-running mismatch"
  builtin kill -0 "$starting_pid" || fail "already-running check killed sleep"
  if pidfile_process_alive "$XVFB_PID_FILE"; then
    fail "pidfile_process_alive reported true for missing pidfile"
  fi
  (exit 0) &
  local exited_pid=$!
  wait "$exited_pid"
  printf '%s\n' "$exited_pid" >"$XVFB_PID_FILE"
  if pidfile_process_alive "$XVFB_PID_FILE"; then
    fail "pidfile_process_alive reported true for exited pid"
  fi
  rm -f -- "$XVFB_PID_FILE"

  # stop_pidfile with kill_group=true terminates a live setsid group and child.
  reset_kill_log
  start_session_group runAgentGui "$TEST_ROOT/grp-leader.pid" "$TEST_ROOT/grp-child.pid"
  local leader_pgid child_pgid
  leader_pgid="$(ps -o pgid= -p "$LAST_GROUP_LEADER" | tr -d '[:space:]')"
  child_pgid="$(ps -o pgid= -p "$LAST_GROUP_CHILD" | tr -d '[:space:]')"
  [[ "$leader_pgid" == "$LAST_GROUP_LEADER" ]] ||
    fail "expected leader $LAST_GROUP_LEADER to lead its own pgid, got '$leader_pgid'"
  [[ "$child_pgid" == "$LAST_GROUP_LEADER" ]] ||
    fail "expected child $LAST_GROUP_CHILD in pgid $LAST_GROUP_LEADER, got '$child_pgid'"
  start_sleep
  local other_group_pid="$LAST_SLEEP_PID"
  printf '%s\n' "$LAST_GROUP_LEADER" >"$APP_PID_FILE"
  is_pid_running "$APP_PID_FILE" runAgentGui || fail "session group leader was not reported running"
  stop_pidfile "$APP_PID_FILE" runAgentGui true
  wait_pids_dead "session group" "$LAST_GROUP_LEADER" "$LAST_GROUP_CHILD"
  assert_missing "$APP_PID_FILE"
  builtin kill -0 "$other_group_pid" || fail "unrelated sleep died during group stop"
  assert_kill_targets_only "$LAST_GROUP_LEADER" "matched process group"
  grep -q -- "-$LAST_GROUP_LEADER" "$KILL_LOG" ||
    fail "expected negative PGID signal -$LAST_GROUP_LEADER in KILL_LOG: $(<"$KILL_LOG")"

  # Subprocess ignoring SIGTERM escalates to SIGKILL and removes pidfile once dead.
  reset_kill_log
  local ready_file="$TEST_ROOT/ignore-term.ready"
  rm -f -- "$ready_file"
  bash -c 'trap "" TERM; touch "$1"; exec -a Xvfb sleep 30' _ "$ready_file" &
  local stubborn_pid=$!
  SLEEP_PIDS+=("$stubborn_pid")
  local i
  for ((i = 0; i < 50; i++)); do
    [[ -e "$ready_file" ]] && break
    sleep 0.05
  done
  [[ -e "$ready_file" ]] || fail "SIGTERM-ignoring subprocess did not arm trap"
  wait_for_cmdline_token "$stubborn_pid" Xvfb
  printf '%s\n' "$stubborn_pid" >"$XVFB_PID_FILE"
  STOP_TERM_POLLS=2
  stop_pidfile "$XVFB_PID_FILE" Xvfb
  STOP_TERM_POLLS=20
  wait_pids_dead "stubborn SIGKILL target" "$stubborn_pid"
  assert_missing "$XVFB_PID_FILE"
  assert_kill_targets_only "$stubborn_pid" "SIGTERM->SIGKILL escalation"
  grep -q -- "-TERM $stubborn_pid" "$KILL_LOG" || fail "expected -TERM in KILL_LOG"
  grep -q -- "-KILL $stubborn_pid" "$KILL_LOG" || fail "expected -KILL in KILL_LOG"
}

test_window_matching() {
  local fake_bin="$TEST_ROOT/fake-bin"
  mkdir -p "$fake_bin"
  cat >"$fake_bin/xdotool" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cmd="${1:-}"
shift || true
case "$cmd" in
  search)
    if [[ -z "${FAKE_XDOTOOL_IDS:-}" ]]; then
      exit 1
    fi
    printf '%s\n' "$FAKE_XDOTOOL_IDS"
    ;;
  getwindowname)
    wid="${1:-}"
    case "$wid" in
      101) printf '%s\n' "Explore - Safe-DB" ;;
      202) printf '%s\n' "Safe-DB" ;;
      301) printf '%s\n' "Explore - Safe-DB" ;;
      302) printf '%s\n' "Export - Safe-DB" ;;
      *) exit 1 ;;
    esac
    ;;
  *)
    exit 1
    ;;
esac
EOF
  chmod +x "$fake_bin/xdotool"

  local exact_wid fallback_wid missing_wid
  exact_wid="$(
    PATH="$fake_bin:$PATH" FAKE_XDOTOOL_IDS=$'101\n202' \
      pick_window_match "Safe-DB" --onlyvisible
  )"
  [[ "$exact_wid" == "202" ]] || fail "pick_window_match exact preference returned '$exact_wid', expected '202'"

  fallback_wid="$(
    PATH="$fake_bin:$PATH" FAKE_XDOTOOL_IDS=$'301\n302' \
      pick_window_match "Explore" --onlyvisible
  )"
  [[ "$fallback_wid" == "301" ]] || fail "pick_window_match substring fallback returned '$fallback_wid', expected '301'"

  missing_wid="$(
    PATH="$fake_bin:$PATH" FAKE_XDOTOOL_IDS="" \
      pick_window_match "Missing" --onlyvisible
  )"
  [[ -z "$missing_wid" ]] || fail "pick_window_match no-match returned '$missing_wid', expected empty"
}

test_data_dir_and_cli_validation() {
  local whitespace_data_dir custom_data_dir
  whitespace_data_dir="$(
    SAFEDB_AGENT_GUI_DATA_DIR="   " bash -c '
      source "$1"
      printf "%s\n" "$DATA_DIR"
    ' _ "$REPO_ROOT/scripts/agent_gui.sh"
  )"
  [[ "$whitespace_data_dir" == "$REPO_ROOT/build/agent-gui/data" ]] ||
    fail "whitespace SAFEDB_AGENT_GUI_DATA_DIR resolved to '$whitespace_data_dir'"

  custom_data_dir="$(
    SAFEDB_AGENT_GUI_DATA_DIR="$TEST_ROOT/custom-data" bash -c '
      source "$1"
      printf "%s\n" "$DATA_DIR"
    ' _ "$REPO_ROOT/scripts/agent_gui.sh"
  )"
  [[ "$custom_data_dir" == "$TEST_ROOT/custom-data" ]] ||
    fail "explicit SAFEDB_AGENT_GUI_DATA_DIR resolved to '$custom_data_dir'"

  local cli_case cli_status
  local -a cli_args
  for cli_case in \
    "unknown-cmd" \
    "up:--bogus" \
    "scroll:side:3" \
    "scroll:down:3:100" \
    "type:a:b"; do
    IFS=':' read -r -a cli_args <<<"$cli_case"
    set +e
    "$REPO_ROOT/scripts/agent_gui.sh" "${cli_args[@]}" >/dev/null 2>&1
    cli_status=$?
    set -e
    [[ "$cli_status" -eq 2 ]] ||
      fail "CLI validation for (${cli_args[*]}) exited $cli_status instead of 2"
  done
}

test_reset_targets
test_cmd_reset_composition
test_pid_and_kill_boundaries
test_window_matching
test_data_dir_and_cli_validation

echo "agent_gui reset tests passed"
