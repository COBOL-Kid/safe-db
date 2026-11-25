#!/usr/bin/env bash
# Dependency-free regression tests for scripts/verify.sh tier routing, flags, and fail-fast behavior.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/safedb-verify-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

FAKE_REPO="$TEST_ROOT/repo"
FAKE_BIN="$TEST_ROOT/bin"
NO_DOCKER_BIN="$TEST_ROOT/no-docker-bin"
CALL_LOG="$TEST_ROOT/calls.log"
mkdir -p "$FAKE_REPO/scripts" "$FAKE_BIN" "$NO_DOCKER_BIN"

cp "$REPO_ROOT/scripts/verify.sh" "$FAKE_REPO/scripts/verify.sh"
chmod +x "$FAKE_REPO/scripts/verify.sh"

# verify.sh uses #!/usr/bin/env bash plus dirname and docker; symlink bash and dirname into
# NO_DOCKER_BIN so missing-docker tests never depend on host PATH contents.
ln -s "$(command -v bash)" "$NO_DOCKER_BIN/bash"
ln -s "$(command -v dirname)" "$NO_DOCKER_BIN/dirname"

cat >"$FAKE_REPO/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'gradlew|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
  "$PWD" \
  "${SAFEDB_KEYCHAIN_BACKEND:-unset}" \
  "${SAFEDB_TEST_REQUIRE_MYSQL:-unset}" \
  "${SAFEDB_TEST_REQUIRE_POSTGRES:-unset}" \
  "${SAFEDB_TEST_REQUIRE_MSSQL:-unset}" \
  "${SAFEDB_TEST_REQUIRE_ORACLE:-unset}" \
  "${SAFEDB_TEST_REQUIRE_DB2:-unset}" \
  "$*" \
  "end" >>"$FAKE_CALL_LOG"
if [[ -n "${FAKE_FAIL_GRADLEW_MATCH:-}" && "$*" == *"$FAKE_FAIL_GRADLEW_MATCH"* ]]; then
  exit "${FAKE_FAIL_GRADLEW_CODE:-1}"
fi
EOF

cat >"$FAKE_REPO/scripts/docker_test_databases.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker_test_databases|%s\n' "$*" >>"$FAKE_CALL_LOG"
if [[ -n "${FAKE_FAIL_DOCKER_HARNESS_CMD:-}" && "${1:-}" == "$FAKE_FAIL_DOCKER_HARNESS_CMD" ]]; then
  exit "${FAKE_FAIL_DOCKER_HARNESS_CODE:-1}"
fi
EOF

cat >"$FAKE_REPO/scripts/agent_gui.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'agent_gui|%s\n' "$*" >>"$FAKE_CALL_LOG"
if [[ -n "${FAKE_FAIL_GUI_CMD:-}" && "${1:-}" == "$FAKE_FAIL_GUI_CMD" ]]; then
  exit "${FAKE_FAIL_GUI_CODE:-1}"
fi
EOF

cat >"$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "inspect" ]]; then
  container=""
  for container in "$@"; do :; done
  if [[ -n "${FAKE_DOCKER_OVERRIDES:-}" ]]; then
    IFS=',' read -r -a pairs <<<"$FAKE_DOCKER_OVERRIDES"
    if ((${#pairs[@]} > 0)); then
      for pair in "${pairs[@]}"; do
        key="${pair%%=*}"
        val="${pair#*=}"
        if [[ "$key" == "$container" ]]; then
          printf '%s\n' "$val"
          exit 0
        fi
      done
    fi
  fi
  if [[ "${FAKE_DOCKER_HEALTHY:-false}" == "true" ]]; then
    printf 'running:healthy\n'
  else
    printf 'missing:none\n'
  fi
  exit 0
fi
exit 1
EOF

chmod +x \
  "$FAKE_REPO/gradlew" \
  "$FAKE_REPO/scripts/docker_test_databases.sh" \
  "$FAKE_REPO/scripts/agent_gui.sh" \
  "$FAKE_BIN/docker"

fail() {
  echo "verify.sh regression test failed: $*" >&2
  exit 1
}

LAST_RC=0
LAST_OUT=""

run_verify() {
  : >"$CALL_LOG"
  local use_path="$FAKE_BIN:$PATH"
  if [[ "${TEST_NO_DOCKER:-false}" == "true" ]]; then
    use_path="$NO_DOCKER_BIN"
  fi
  set +e
  LAST_OUT="$(
    env \
      PATH="$use_path" \
      FAKE_CALL_LOG="$CALL_LOG" \
      FAKE_DOCKER_HEALTHY="${TEST_DOCKER_HEALTHY:-false}" \
      FAKE_DOCKER_OVERRIDES="${TEST_DOCKER_OVERRIDES:-}" \
      FAKE_FAIL_GRADLEW_MATCH="${TEST_FAIL_GRADLEW_MATCH:-}" \
      FAKE_FAIL_GRADLEW_CODE="${TEST_FAIL_GRADLEW_CODE:-1}" \
      FAKE_FAIL_DOCKER_HARNESS_CMD="${TEST_FAIL_DOCKER_HARNESS_CMD:-}" \
      FAKE_FAIL_DOCKER_HARNESS_CODE="${TEST_FAIL_DOCKER_HARNESS_CODE:-1}" \
      FAKE_FAIL_GUI_CMD="${TEST_FAIL_GUI_CMD:-}" \
      FAKE_FAIL_GUI_CODE="${TEST_FAIL_GUI_CODE:-1}" \
      SAFEDB_KEYCHAIN_BACKEND="" \
      SAFEDB_TEST_REQUIRE_MYSQL="" \
      SAFEDB_TEST_REQUIRE_POSTGRES="" \
      SAFEDB_TEST_REQUIRE_MSSQL="" \
      SAFEDB_TEST_REQUIRE_ORACLE="" \
      SAFEDB_TEST_REQUIRE_DB2="" \
      "$FAKE_REPO/scripts/verify.sh" "$@" 2>&1
  )"
  LAST_RC=$?
  set -e
}

assert_rc() {
  local expected="$1"
  [[ "$LAST_RC" -eq "$expected" ]] ||
    fail "expected exit $expected, got $LAST_RC. Output:\n$LAST_OUT"
}

assert_calls_empty() {
  [[ ! -s "$CALL_LOG" ]] ||
    fail "expected zero external calls, got:\n$(cat "$CALL_LOG")"
}

assert_calls_exact() {
  local expected="$1"
  local actual
  actual="$(cat "$CALL_LOG")"
  [[ "$actual" == "$expected" ]] ||
    fail "unexpected call log.\nExpected:\n$expected\nActual:\n$actual"
}

assert_output_contains() {
  local needle="$1"
  [[ "$LAST_OUT" == *"$needle"* ]] ||
    fail "expected output to contain '$needle'. Output:\n$LAST_OUT"
}

# 1. Help flags exit 0 with usage and zero external calls.
for flag in --help -h; do
  run_verify "$flag"
  assert_rc 0
  assert_output_contains "Usage: scripts/verify.sh [fast|live|packaged|gui|all]"
  assert_calls_empty
done

# 2. Invalid/duplicate/conflicting arguments exit 2 with zero external calls.
for bad_case in "bogus" "fast live" "fast fast" "--bogus" "gui --skip-gui"; do
  # shellcheck disable=SC2086
  run_verify $bad_case
  assert_rc 2
  assert_calls_empty
done

run_verify ""
assert_rc 2
assert_calls_empty

run_verify -- --bogus
assert_rc 2
assert_calls_empty

run_verify -- fast live
assert_rc 2
assert_calls_empty

# 3. Default tier and explicit fast both run ./gradlew check jar.
run_verify
assert_rc 0
assert_calls_exact "gradlew|$FAKE_REPO|unset|unset|unset|unset|unset|unset|check jar|end"
assert_output_contains "=== Verification Summary ==="
assert_output_contains "fast"
assert_output_contains "PASS"

run_verify fast --up
assert_rc 0
assert_output_contains "Warning: --up has no effect with tier 'fast'."
assert_calls_exact "gradlew|$FAKE_REPO|unset|unset|unset|unset|unset|unset|check jar|end"

# 4. live tier: missing docker on PATH fails fast.
TEST_NO_DOCKER=true run_verify live
TEST_NO_DOCKER=false
assert_rc 1
assert_output_contains "Required tool not found: docker"
assert_calls_empty

# 5. live tier: unhealthy container without --up fails with guidance and zero harness calls.
TEST_DOCKER_HEALTHY=true
TEST_DOCKER_OVERRIDES="safedb-test-db2=running:starting"
run_verify live
TEST_DOCKER_HEALTHY=false
TEST_DOCKER_OVERRIDES=""
assert_rc 1
assert_output_contains "safedb-test-db2 (running:starting)"
assert_output_contains "--up"
assert_calls_empty

# 6. live tier: all 6 healthy without --up runs verify without up.
TEST_DOCKER_HEALTHY=true
run_verify live
TEST_DOCKER_HEALTHY=false
assert_rc 0
assert_calls_exact "docker_test_databases|verify"

# 7. live --up and --up live start stack then verify.
for order in "live --up" "--up live"; do
  # shellcheck disable=SC2086
  run_verify $order
  assert_rc 0
  assert_calls_exact $'docker_test_databases|up\ndocker_test_databases|verify'
done

# 8. packaged tier: stopped Docker runs offline without forcing SAFEDB_TEST_REQUIRE_*.
run_verify packaged
assert_rc 0
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|jar|end\ngradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|:mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace|end'

# 9. packaged tier: healthy Docker exports SAFEDB_KEYCHAIN_BACKEND=disabled and all 5 SAFEDB_TEST_REQUIRE_*=true.
TEST_DOCKER_HEALTHY=true
run_verify packaged
TEST_DOCKER_HEALTHY=false
assert_rc 0
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|jar|end\ngradlew|'"$FAKE_REPO"$'|disabled|true|true|true|true|true|:mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace|end'

# 10. packaged tier: 5/6 partial Docker health stays offline without SAFEDB_TEST_REQUIRE_*.
TEST_DOCKER_HEALTHY=true
TEST_DOCKER_OVERRIDES="safedb-test-db2=running:starting"
run_verify packaged
TEST_DOCKER_HEALTHY=false
TEST_DOCKER_OVERRIDES=""
assert_rc 0
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|jar|end\ngradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|:mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace|end'

# 11. packaged --up starts stack once and runs with all 5 SAFEDB_TEST_REQUIRE_*=true.
run_verify packaged --up
assert_rc 0
assert_calls_exact $'docker_test_databases|up\ngradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|jar|end\ngradlew|'"$FAKE_REPO"$'|disabled|true|true|true|true|true|:mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace|end'

# 12. gui tier: MySQL stopped -> offline smoke (no --require-db).
run_verify gui
assert_rc 0
assert_calls_exact "agent_gui|smoke"

# 13. gui tier: MySQL healthy even while Db2 is missing -> passes --require-db.
TEST_DOCKER_HEALTHY=false
TEST_DOCKER_OVERRIDES="safedb-test-mysql-connectivity=running:healthy"
run_verify gui
TEST_DOCKER_OVERRIDES=""
assert_rc 0
assert_calls_exact "agent_gui|smoke --require-db"

# 14. gui --up: starts Docker then passes --require-db.
run_verify gui --up
assert_rc 0
assert_calls_exact $'docker_test_databases|up\nagent_gui|smoke --require-db'

# 15. all tier (healthy): exact sequence and summary.
TEST_DOCKER_HEALTHY=true
run_verify all
TEST_DOCKER_HEALTHY=false
assert_rc 0
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|check jar|end\ndocker_test_databases|verify\ngradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|jar|end\ngradlew|'"$FAKE_REPO"$'|disabled|true|true|true|true|true|:mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace|end\nagent_gui|smoke --require-db'
assert_output_contains "fast        PASS"
assert_output_contains "live        PASS"
assert_output_contains "packaged    PASS"
assert_output_contains "gui         PASS"

# 16. all --up --skip-gui --qodana: fast runs before up, up runs once, gui skipped, qodana runs.
run_verify all --up --skip-gui --qodana
assert_rc 0
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|check jar|end\ndocker_test_databases|up\ndocker_test_databases|verify\ngradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|jar|end\ngradlew|'"$FAKE_REPO"$'|disabled|true|true|true|true|true|:mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace|end\ngradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|qodanaScan|end'
assert_output_contains "gui         SKIP"
assert_output_contains "qodana      PASS"

# 17. single tier + --qodana runs qodanaScan after that tier.
run_verify fast --qodana
assert_rc 0
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|check jar|end\ngradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|qodanaScan|end'

# 18. Fail-fast when fast fails in all --up --qodana: preserves exit code, skips remaining stages, prints summary.
TEST_FAIL_GRADLEW_MATCH="check jar"
TEST_FAIL_GRADLEW_CODE=17
run_verify all --up --qodana
TEST_FAIL_GRADLEW_MATCH=""
TEST_FAIL_GRADLEW_CODE=1
assert_rc 17
assert_calls_exact "gradlew|$FAKE_REPO|unset|unset|unset|unset|unset|unset|check jar|end"
assert_output_contains "=== Verification Summary ==="
assert_output_contains "fast        FAIL"

# 19. Fail-fast when live verify fails in all: preserves exit code, skips packaged/gui, prints summary.
TEST_DOCKER_HEALTHY=true
TEST_FAIL_DOCKER_HARNESS_CMD="verify"
TEST_FAIL_DOCKER_HARNESS_CODE=23
run_verify all
TEST_DOCKER_HEALTHY=false
TEST_FAIL_DOCKER_HARNESS_CMD=""
TEST_FAIL_DOCKER_HARNESS_CODE=1
assert_rc 23
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|check jar|end\ndocker_test_databases|verify'
assert_output_contains "fast        PASS"
assert_output_contains "live        FAIL"

# 20. Fail-fast when docker up fails inside live --up in all --up: skips verify/packaged/gui.
TEST_FAIL_DOCKER_HARNESS_CMD="up"
TEST_FAIL_DOCKER_HARNESS_CODE=19
run_verify all --up
TEST_FAIL_DOCKER_HARNESS_CMD=""
TEST_FAIL_DOCKER_HARNESS_CODE=1
assert_rc 19
assert_calls_exact $'gradlew|'"$FAKE_REPO"$'|unset|unset|unset|unset|unset|unset|check jar|end\ndocker_test_databases|up'
assert_output_contains "fast        PASS"
assert_output_contains "live        FAIL"

# 21. Fail-fast when packaged fails on ./gradlew jar: skips :mcp:assembleNpm and prints packaged FAIL.
TEST_FAIL_GRADLEW_MATCH="jar"
TEST_FAIL_GRADLEW_CODE=31
run_verify packaged
TEST_FAIL_GRADLEW_MATCH=""
TEST_FAIL_GRADLEW_CODE=1
assert_rc 31
assert_calls_exact "gradlew|$FAKE_REPO|unset|unset|unset|unset|unset|unset|jar|end"
assert_output_contains "=== Verification Summary ==="
assert_output_contains "packaged    FAIL"

echo "verify.sh regression tests passed"
