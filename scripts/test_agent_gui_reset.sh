#!/usr/bin/env bash
# Boundary and symlink safety tests for agent_gui.sh reset targeting.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/safedb-agent-gui-reset-test.XXXXXX")"
SLEEP_PIDS=()
cleanup_test() {
  local pid
  for pid in "${SLEEP_PIDS[@]}"; do
    if [[ -n "$pid" ]] && builtin kill -0 "$pid" 2>/dev/null; then
      builtin kill "$pid" 2>/dev/null || true
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
  local arg prev=""
  for arg in "$@"; do
    if [[ "$arg" == "1" || "$arg" == "-1" || "$arg" == "-- -1" ]]; then
      fail "refusing to signal unsafe pid argument: $arg"
    fi
    if [[ "$prev" == "--" && "$arg" == "-1" ]]; then
      fail "refusing to signal unsafe pid argument: -- -1"
    fi
    prev="$arg"
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

# 1. Override equal to $state/data: data and shots removed, sibling app.log kept, no Not removing.
c1="$TEST_ROOT/case1"
setup_state "$c1"
out="$(remove_agent_gui_reset_targets "$c1" "$c1/shots" "$c1/data")"
assert_missing "$c1/data"
assert_missing "$c1/shots"
assert_exists "$c1/app.log"
assert_not_contains "$out" "Not removing" "case 1"

# 2. Override is an outside directory with a marker: managed wiped, marker kept, printed.
c2="$TEST_ROOT/case2"
c2_outside="$TEST_ROOT/case2-outside"
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
c3="$TEST_ROOT/case3"
c3_target="$TEST_ROOT/case3-target"
c3_link="$TEST_ROOT/case3-link"
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
c4="$TEST_ROOT/case4"
c4_target="$TEST_ROOT/case4-target"
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
c5="$TEST_ROOT/case5"
setup_state "$c5"
out="$(remove_agent_gui_reset_targets "$c5" "$c5/shots" "$c5")"
assert_exists "$c5"
assert_exists "$c5/app.log"
assert_missing "$c5/data"
assert_missing "$c5/shots"
assert_contains "$out" "Not removing" "case 5"

# 6. Override is a subdirectory of $state/data: no Not removing line, subdirectory gone.
c6="$TEST_ROOT/case6"
setup_state "$c6"
mkdir -p "$c6/data/sub"
printf 'nested\n' >"$c6/data/sub/item"
out="$(remove_agent_gui_reset_targets "$c6" "$c6/shots" "$c6/data/sub")"
assert_missing "$c6/data/sub"
assert_missing "$c6/data"
assert_missing "$c6/shots"
assert_not_contains "$out" "Not removing" "case 6"

# 7. Empty or / arguments return nonzero and remove nothing.
c7="$TEST_ROOT/case7"
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

# cmd_reset composition. Pid files stay absent so cmd_down does not signal.
# STATE_DIR is a temp dir, never the real build/agent-gui tree.
clear_reset_pid_files() {
  rm -f -- "$XVFB_PID_FILE" "$OPENBOX_PID_FILE" "$VNC_PID_FILE" "$APP_PID_FILE"
}

# Outside override: managed data and shots are wiped; the outside marker is kept.
clear_reset_pid_files
reset_kill_log
r_out="$TEST_ROOT/reset-outside"
r_out_data="$TEST_ROOT/reset-outside-data"
setup_state "$r_out"
mkdir -p "$r_out_data"
printf 'keep\n' >"$r_out_data/marker"
STATE_DIR="$r_out"
SHOTS_DIR="$r_out/shots"
DATA_DIR="$r_out_data"
out="$(cmd_reset)"
assert_missing "$r_out/data"
assert_missing "$r_out/shots"
assert_exists "$r_out_data/marker"
assert_contains "$out" "Not removing" "cmd_reset outside"
assert_contains "$out" "$r_out_data" "cmd_reset outside path"
assert_no_kill "cmd_reset outside"

# state/data is a symlink to an outside directory: unlink the symlink, keep the target.
clear_reset_pid_files
reset_kill_log
r_link="$TEST_ROOT/reset-symlink"
r_link_target="$TEST_ROOT/reset-symlink-target"
mkdir -p "$r_link/shots" "$r_link_target"
printf 'shot\n' >"$r_link/shots/screen.png"
printf 'keep\n' >"$r_link_target/marker"
ln -s "$r_link_target" "$r_link/data"
STATE_DIR="$r_link"
SHOTS_DIR="$r_link/shots"
DATA_DIR="$r_link/data"
out="$(cmd_reset)"
assert_missing "$r_link/data"
assert_missing "$r_link/shots"
assert_exists "$r_link_target/marker"
assert_not_contains "$out" "Not removing" "cmd_reset symlink"
assert_no_kill "cmd_reset symlink"

# Kill boundary: pid 1 is not a signal target, even as a process group.
reset_kill_log
printf '1\n' >"$XVFB_PID_FILE"
stop_pidfile "$XVFB_PID_FILE" Xvfb true
assert_missing "$XVFB_PID_FILE"
assert_no_kill "pid 1"
[[ -d "/proc/$$" ]] || fail "test process is not alive"

# Kill boundary: a live process whose cmdline lacks the expected token is not signaled.
reset_kill_log
start_sleep
mismatch_pid="$LAST_SLEEP_PID"
printf '%s\n' "$mismatch_pid" >"$XVFB_PID_FILE"
if is_pid_running "$XVFB_PID_FILE" Xvfb; then
  fail "mismatched cmdline reported running"
fi
printf '%s\n' "$mismatch_pid" >"$XVFB_PID_FILE"
stop_pidfile "$XVFB_PID_FILE" Xvfb
assert_no_kill "mismatched cmdline"
builtin kill -0 "$mismatch_pid" || fail "mismatched sleep died"
assert_missing "$XVFB_PID_FILE"

# Kill boundary: a cmdline match may be signaled; an unrelated sleep in the same run is not.
reset_kill_log
start_sleep Xvfb
matched_pid="$LAST_SLEEP_PID"
start_sleep
other_pid="$LAST_SLEEP_PID"
printf '%s\n' "$matched_pid" >"$XVFB_PID_FILE"
is_pid_running "$XVFB_PID_FILE" Xvfb || fail "Xvfb-named sleep was not running"
stop_pidfile "$XVFB_PID_FILE" Xvfb
for ((i = 0; i < 50; i++)); do
  builtin kill -0 "$matched_pid" 2>/dev/null || break
  sleep 0.1
done
if builtin kill -0 "$matched_pid" 2>/dev/null; then
  fail "matched sleep still alive"
fi
assert_missing "$XVFB_PID_FILE"
builtin kill -0 "$other_pid" || fail "unrelated sleep died"
assert_kill_targets_only "$matched_pid" "matched cmdline"

# Kill boundary: non-numeric pid text is removed and not signaled.
reset_kill_log
printf 'not-a-pid\n' >"$OPENBOX_PID_FILE"
stop_pidfile "$OPENBOX_PID_FILE" openbox true
assert_missing "$OPENBOX_PID_FILE"
assert_no_kill "non-numeric pid"

# Kill boundary: a not-yet-exec'd pid is still starting. Already-running still drops a mismatch.
reset_kill_log
start_sleep
starting_pid="$LAST_SLEEP_PID"
printf '%s\n' "$starting_pid" >"$XVFB_PID_FILE"
launch_state="$(launch_pid_state "$XVFB_PID_FILE" Xvfb)"
[[ "$launch_state" == "starting" ]] || fail "startup wait reported '$launch_state', expected starting"
assert_exists "$XVFB_PID_FILE"
builtin kill -0 "$starting_pid" || fail "not-yet-exec'd sleep died"
assert_no_kill "startup wait"
if is_pid_running "$XVFB_PID_FILE" Xvfb; then
  fail "not-yet-exec'd sleep reported as already our Xvfb"
fi
assert_missing "$XVFB_PID_FILE"
assert_no_kill "already-running mismatch"
builtin kill -0 "$starting_pid" || fail "already-running check killed sleep"

echo "agent_gui reset tests passed"
