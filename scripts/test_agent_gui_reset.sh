#!/usr/bin/env bash
# Boundary and symlink safety tests for agent_gui.sh reset targeting.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/safedb-agent-gui-reset-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

# shellcheck source=scripts/agent_gui.sh
source "$REPO_ROOT/scripts/agent_gui.sh"

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

# Verify cmd_reset delegates to remove_agent_gui_reset_targets and does not rm $DATA_DIR directly.
reset_body="$(declare -f cmd_reset)"
assert_contains "$reset_body" "remove_agent_gui_reset_targets" "cmd_reset body"
assert_not_contains "$reset_body" 'rm -rf "$DATA_DIR"' "cmd_reset body"

echo "agent_gui reset tests passed"
