#!/usr/bin/env bash
# Unified tiered local verification runner for safe-db.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_CONTAINERS=(
  safedb-test-mysql-connectivity
  safedb-test-mysql
  safedb-test-postgres
  safedb-test-mssql
  safedb-test-oracle
  safedb-test-db2
)

TIER="fast"
TIER_SET=false
OPT_UP=false
OPT_SKIP_GUI=false
OPT_QODANA=false
DOCKER_UP_DONE=false

STAGE_NAMES=()
STAGE_STATUSES=()
STAGE_DURATIONS=()

usage() {
  cat <<'EOF'
Usage: scripts/verify.sh [fast|live|packaged|gui|all] [--up] [--skip-gui] [--qodana]

Tiers:
  fast      (default) ./gradlew check jar
  live      Verify 5-engine Docker health (or start with --up) and run scripts/docker_test_databases.sh verify
  packaged  Build desktop JAR and run :mcp:assembleNpm, :mcp:npmCliTest, and :mcp:verifyNpmPackagedTest
  gui       Run scripts/agent_gui.sh smoke (passes --require-db when Docker MySQL is healthy or --up is set)
  all       Run fast -> live -> packaged -> gui

Options:
  --up        Start and seed the 5-engine Docker test stack via scripts/docker_test_databases.sh up
  --skip-gui  Skip the gui stage when running tier 'all'
  --qodana    Run ./gradlew qodanaScan as an additional final stage
  -h, --help  Show this help message and exit
EOF
}

parse_args() {
  local arg
  while (($# > 0)); do
    arg="$1"
    shift
    case "$arg" in
      -h | --help)
        usage
        exit 0
        ;;
      --up)
        OPT_UP=true
        ;;
      --skip-gui)
        OPT_SKIP_GUI=true
        ;;
      --qodana)
        OPT_QODANA=true
        ;;
      --)
        if (($# > 1)); then
          echo "Unexpected extra arguments after '--': $*" >&2
          usage >&2
          exit 2
        fi
        if (($# == 1)); then
          case "$1" in
            fast | live | packaged | gui | all)
              if [[ "$TIER_SET" == "true" ]]; then
                echo "Multiple tiers specified: '$TIER' and '$1'" >&2
                exit 2
              fi
              TIER="$1"
              TIER_SET=true
              ;;
            *)
              echo "Invalid tier or argument: '$1' (expected fast|live|packaged|gui|all)" >&2
              usage >&2
              exit 2
              ;;
          esac
        fi
        break
        ;;
      -*)
        echo "Unknown option: $arg" >&2
        usage >&2
        exit 2
        ;;
      fast | live | packaged | gui | all)
        if [[ "$TIER_SET" == "true" ]]; then
          echo "Multiple tiers specified: '$TIER' and '$arg'" >&2
          exit 2
        fi
        TIER="$arg"
        TIER_SET=true
        ;;
      *)
        echo "Invalid tier or argument: '$arg' (expected fast|live|packaged|gui|all)" >&2
        usage >&2
        exit 2
        ;;
    esac
  done

  if [[ "$TIER" == "gui" && "$OPT_SKIP_GUI" == "true" ]]; then
    echo "Cannot combine tier 'gui' with --skip-gui." >&2
    exit 2
  fi
  if [[ "$TIER" == "fast" && "$OPT_UP" == "true" ]]; then
    echo "Warning: --up has no effect with tier 'fast'." >&2
  fi
}

docker_container_state() {
  local container="$1"
  docker inspect -f '{{.State.Status}}:{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null ||
    printf 'missing:none\n'
}

is_docker_mysql_healthy() {
  command -v docker >/dev/null 2>&1 || return 1
  [[ "$(docker_container_state "safedb-test-mysql-connectivity")" == "running:healthy" ]]
}

check_docker_health() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Required tool not found: docker. Install Docker or start the test stack before running this tier." >&2
    return 1
  fi

  local unhealthy=()
  local container state
  for container in "${DOCKER_CONTAINERS[@]}"; do
    state="$(docker_container_state "$container")"
    if [[ "$state" != "running:healthy" ]]; then
      unhealthy+=("$container ($state)")
    fi
  done

  if ((${#unhealthy[@]} > 0)); then
    echo "Docker test database stack is not healthy:" >&2
    local entry
    for entry in "${unhealthy[@]}"; do
      echo "  - $entry" >&2
    done
    echo "Run 'scripts/docker_test_databases.sh up' or re-run with '--up'." >&2
    return 1
  fi
}

ensure_docker_up() {
  if [[ "$DOCKER_UP_DONE" == "true" ]]; then
    return 0
  fi
  "$ROOT/scripts/docker_test_databases.sh" up || return $?
  DOCKER_UP_DONE=true
}

stage_fast() {
  (cd "$ROOT" && ./gradlew check jar)
}

stage_live() {
  if [[ "$OPT_UP" == "true" ]]; then
    ensure_docker_up || return $?
  else
    check_docker_health || return $?
  fi
  "$ROOT/scripts/docker_test_databases.sh" verify
}

stage_packaged() {
  if [[ "$OPT_UP" == "true" ]]; then
    ensure_docker_up || return $?
  fi
  (cd "$ROOT" && ./gradlew jar) || return $?
  if [[ "$DOCKER_UP_DONE" == "true" ]] || check_docker_health >/dev/null 2>&1; then
    (
      cd "$ROOT" &&
        SAFEDB_KEYCHAIN_BACKEND=disabled \
        SAFEDB_TEST_REQUIRE_MYSQL=true \
        SAFEDB_TEST_REQUIRE_POSTGRES=true \
        SAFEDB_TEST_REQUIRE_MSSQL=true \
        SAFEDB_TEST_REQUIRE_ORACLE=true \
        SAFEDB_TEST_REQUIRE_DB2=true \
        ./gradlew :mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace
    )
  else
    (
      cd "$ROOT" &&
        ./gradlew :mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace
    )
  fi
}

stage_gui() {
  local args=(smoke)
  if [[ "$OPT_UP" == "true" ]]; then
    ensure_docker_up || return $?
    args+=(--require-db)
  elif is_docker_mysql_healthy; then
    args+=(--require-db)
  fi
  "$ROOT/scripts/agent_gui.sh" "${args[@]}"
}

stage_qodana() {
  (cd "$ROOT" && ./gradlew qodanaScan)
}

run_stage() {
  local name="$1"
  local fn="$2"
  echo "==> [verify] stage: $name"
  local start=$SECONDS
  local rc=0
  # 'if' suppresses errexit inside the callee, so stage functions propagate errors with '|| return $?'.
  if "$fn"; then
    rc=0
  else
    rc=$?
  fi
  local elapsed=$((SECONDS - start))
  STAGE_NAMES+=("$name")
  if ((rc == 0)); then
    STAGE_STATUSES+=("PASS")
  else
    STAGE_STATUSES+=("FAIL")
  fi
  STAGE_DURATIONS+=("${elapsed}s")
  return "$rc"
}

record_skip() {
  STAGE_NAMES+=("$1")
  STAGE_STATUSES+=("SKIP")
  STAGE_DURATIONS+=("0s")
}

print_summary() {
  ((${#STAGE_NAMES[@]} > 0)) || return 0
  echo ""
  echo "=== Verification Summary ==="
  printf '%-10s  %-6s  %s\n' "Stage" "Status" "Duration"
  local i
  for ((i = 0; i < ${#STAGE_NAMES[@]}; i++)); do
    printf '%-10s  %-6s  %s\n' "${STAGE_NAMES[$i]}" "${STAGE_STATUSES[$i]}" "${STAGE_DURATIONS[$i]}"
  done
}

run_selected() {
  case "$TIER" in
    fast)
      run_stage fast stage_fast || return $?
      ;;
    live)
      run_stage live stage_live || return $?
      ;;
    packaged)
      run_stage packaged stage_packaged || return $?
      ;;
    gui)
      run_stage gui stage_gui || return $?
      ;;
    all)
      run_stage fast stage_fast || return $?
      run_stage live stage_live || return $?
      run_stage packaged stage_packaged || return $?
      if [[ "$OPT_SKIP_GUI" == "true" ]]; then
        record_skip gui
      else
        run_stage gui stage_gui || return $?
      fi
      ;;
  esac

  if [[ "$OPT_QODANA" == "true" ]]; then
    run_stage qodana stage_qodana || return $?
  fi
}

main() {
  parse_args "$@"
  local rc=0
  if run_selected; then
    rc=0
  else
    rc=$?
  fi
  print_summary
  return "$rc"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
