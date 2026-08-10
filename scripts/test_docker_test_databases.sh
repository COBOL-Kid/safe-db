#!/usr/bin/env bash
# Dependency-free regression tests for docker_test_databases.sh orchestration.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/safedb-docker-harness-test.XXXXXX")"
FAKE_BIN="$TEST_ROOT/bin"
DOCKER_LOG="$TEST_ROOT/docker.log"
ORACLE_MARKER="$TEST_ROOT/oracle-invoked"
OPENSSL_MARKER="$TEST_ROOT/openssl-invoked"
KEYTOOL_MARKER="$TEST_ROOT/keytool-invoked"
mkdir -p "$FAKE_BIN"
trap 'rm -rf "$TEST_ROOT"' EXIT

cat >"$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
case " $* " in
  *" compose "*" ps --status running --services "*)
    printf '%s' "${FAKE_RUNNING_SERVICES:-}"
    ;;
  *" compose "*" up --no-deps --exit-code-from mssql-init mssql-init "*)
    exit "${FAKE_MSSQL_EXIT:-0}"
    ;;
  *" compose "*" exec -T oracle /usr/local/bin/safedb-oracle-init.sh "*)
    touch "$FAKE_ORACLE_MARKER"
    ;;
esac
EOF

cat >"$FAKE_BIN/openssl" <<'EOF'
#!/usr/bin/env bash
touch "$FAKE_OPENSSL_MARKER"
exit 99
EOF

cat >"$FAKE_BIN/keytool" <<'EOF'
#!/usr/bin/env bash
touch "$FAKE_KEYTOOL_MARKER"
exit 99
EOF

chmod +x "$FAKE_BIN/docker" "$FAKE_BIN/openssl" "$FAKE_BIN/keytool"

fail() {
  echo "docker harness regression failed: $*" >&2
  exit 1
}

run_harness() {
  local command="$1"
  local running_services="${2:-}"
  local mssql_exit="${3:-0}"
  local ssl_root="$TEST_ROOT/$command-ssl"
  env \
    PATH="$FAKE_BIN:$PATH" \
    SAFEDB_SSL_ROOT="$ssl_root" \
    FAKE_DOCKER_LOG="$DOCKER_LOG" \
    FAKE_ORACLE_MARKER="$ORACLE_MARKER" \
    FAKE_OPENSSL_MARKER="$OPENSSL_MARKER" \
    FAKE_KEYTOOL_MARKER="$KEYTOOL_MARKER" \
    FAKE_RUNNING_SERVICES="$running_services" \
    FAKE_MSSQL_EXIT="$mssql_exit" \
    "$ROOT/scripts/docker_test_databases.sh" "$command"
}

set +e
run_harness seed "" 37 >/dev/null 2>&1
seed_status=$?
set -e
[[ "$seed_status" -eq 37 ]] || fail "seed returned $seed_status instead of mssql-init status 37"
[[ ! -e "$ORACLE_MARKER" ]] || fail "Oracle seeding ran after mssql-init failed"
grep -q -- "up --no-deps --exit-code-from mssql-init mssql-init" "$DOCKER_LOG" ||
  fail "mssql-init was not run with --exit-code-from"

set +e
certs_output="$(run_harness certs $'mysql\npostgres\n' 0 2>&1)"
certs_status=$?
set -e
[[ "$certs_status" -ne 0 ]] || fail "certs succeeded while services were running"
[[ "$certs_output" == *"scripts/docker_test_databases.sh reset"* ]] ||
  fail "certs did not provide reset guidance"
[[ ! -e "$OPENSSL_MARKER" && ! -e "$KEYTOOL_MARKER" ]] ||
  fail "certificate tools ran before certs refused rotation"

set +e
verify_output="$(run_harness verify $'mssql\n' 0 2>&1)"
verify_status=$?
set -e
[[ "$verify_status" -ne 0 ]] || fail "verify regenerated missing certificates while running"
[[ "$verify_output" == *"scripts/docker_test_databases.sh reset"* ]] ||
  fail "verify did not provide reset guidance"
[[ ! -e "$OPENSSL_MARKER" && ! -e "$KEYTOOL_MARKER" ]] ||
  fail "certificate tools ran before verify refused rotation"

echo "docker test database harness regressions verified"
