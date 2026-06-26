#!/usr/bin/env bash
# scripts/seed_testdb.sh — populate the safe-db MySQL test database.
#
# Usage:
#   scripts/seed_testdb.sh                  # seed safedb_test on localhost:3306
#   scripts/seed_testdb.sh --reset          # drop + recreate the database first
#   SAFEDB_TEST_MYSQL_HOST=db.local scripts/seed_testdb.sh
#   scripts/seed_testdb.sh --docker        # auto-detect running mysql/mariadb container
#   scripts/seed_testdb.sh --docker=mysql  # explicit container name
#   SAFEDB_TEST_MYSQL_DOCKER=mysql scripts/seed_testdb.sh
#   scripts/seed_testdb.sh --help
#
# Env vars (all optional; defaults shown):
#   SAFEDB_TEST_MYSQL_HOST       localhost
#   SAFEDB_TEST_MYSQL_PORT       3306        (host mode only; ignored in --docker mode)
#   SAFEDB_TEST_MYSQL_USER       root
#   SAFEDB_TEST_MYSQL_PASSWORD   (empty)
#   SAFEDB_TEST_MYSQL_DATABASE   safedb_test
#   SAFEDB_TEST_MYSQL_DOCKER     (empty)     set to a container name to run via docker exec
#
# In --docker mode the mysql client lives inside the container, so the host
# does not need a mysql-client install. The container's internal port 3306 is
# used; the host's SAFEDB_TEST_MYSQL_PORT is ignored. Password is passed via
# the MYSQL_PWD env var to `docker exec` so it never appears on the command line.
#
# Host mode requires: the `mysql` client in PATH. On macOS, install with
#   brew install mysql-client
# and ensure /opt/homebrew/opt/mysql-client/bin (or your prefix) is on PATH.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SQL_FILE="$REPO_ROOT/testdata_mysql.sql"

HOST="${SAFEDB_TEST_MYSQL_HOST:-localhost}"
PORT="${SAFEDB_TEST_MYSQL_PORT:-3306}"
USER_NAME="${SAFEDB_TEST_MYSQL_USER:-root}"
PASSWORD="${SAFEDB_TEST_MYSQL_PASSWORD:-}"
DATABASE="${SAFEDB_TEST_MYSQL_DATABASE:-safedb_test}"
DOCKER_TARGET="${SAFEDB_TEST_MYSQL_DOCKER:-}"

usage() {
  sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

RESET=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --reset) RESET=1; shift ;;
    -h | --help) usage 0 ;;
    --docker)
      DOCKER_TARGET="__autodetect__"
      shift
      ;;
    --docker=*)
      DOCKER_TARGET="${1#--docker=}"
      shift
      ;;
    --) shift; break ;;
    -*) echo "unknown argument: $1" >&2; usage 1 ;;
    *) echo "unexpected positional argument: $1" >&2; usage 1 ;;
  esac
done

DOCKER_CONTAINER=""

resolve_docker_target() {
  if [[ "$DOCKER_TARGET" != "__autodetect__" && -n "$DOCKER_TARGET" ]]; then
    if ! docker inspect "$DOCKER_TARGET" >/dev/null 2>&1; then
      echo "error: docker container '$DOCKER_TARGET' not found" >&2
      echo "  running containers:" >&2
      docker ps --format '    {{.Names}} ({{.Image}})' >&2 || true
      exit 1
    fi
    DOCKER_CONTAINER="$DOCKER_TARGET"
    return
  fi

  local matches
  matches="$(docker ps --filter "status=running" --format '{{.Names}}\t{{.Image}}' \
    | awk -F'\t' 'tolower($2) ~ /mysql|mariadb/ {print $1}')"
  local count
  count="$(printf '%s\n' "$matches" | grep -c . || true)"

  if [[ "$count" -eq 0 ]]; then
    echo "error: no running mysql/mariadb container found" >&2
    echo "  hint: pass --docker=<container-name> or set SAFEDB_TEST_MYSQL_DOCKER" >&2
    echo "  running containers:" >&2
    docker ps --format '    {{.Names}} ({{.Image}})' >&2 || true
    exit 1
  fi
  if [[ "$count" -gt 1 ]]; then
    echo "error: multiple mysql/mariadb containers running; please specify one:" >&2
    printf '    %s\n' $matches >&2
    echo "  hint: --docker=<name> or SAFEDB_TEST_MYSQL_DOCKER=<name>" >&2
    exit 1
  fi
  DOCKER_CONTAINER="$matches"
}

if [[ -n "$DOCKER_TARGET" ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "error: 'docker' not found in PATH (required for --docker mode)" >&2
    exit 1
  fi
  resolve_docker_target
  echo "→ using docker container: $DOCKER_CONTAINER"
else
  if ! command -v mysql >/dev/null 2>&1; then
    echo "error: 'mysql' client not found in PATH" >&2
    echo "  install with: brew install mysql-client (macOS) or apt install default-mysql-client (Debian/Ubuntu)" >&2
    echo "  ...or run with --docker if MySQL is in a container" >&2
    exit 1
  fi
fi

if [[ ! -f "$SQL_FILE" ]]; then
  echo "error: SQL file not found: $SQL_FILE" >&2
  exit 1
fi

# Local-mode temp config so the password is not on the command line and
# `mysql -p` does not block on a TTY. 0600, removed on exit. Unused in docker mode.
MYCNF=""
if [[ -z "$DOCKER_CONTAINER" ]]; then
  MYCNF="$(mktemp -t safedb-seed.XXXXXX)"
  trap 'rm -f "$MYCNF"' EXIT
  {
    printf "[client]\n"
    printf "host=%s\n" "$HOST"
    printf "port=%s\n" "$PORT"
    printf "user=%s\n" "$USER_NAME"
    printf "password=%s\n" "$PASSWORD"
    printf "protocol=TCP\n"
  } > "$MYCNF"
  chmod 600 "$MYCNF"
fi

mysql_run() {
  if [[ -n "$DOCKER_CONTAINER" ]]; then
    docker exec -i \
      -e MYSQL_PWD="$PASSWORD" \
      "$DOCKER_CONTAINER" \
      mysql -h 127.0.0.1 -P 3306 -u "$USER_NAME" "$@"
  else
    mysql --defaults-file="$MYCNF" "$@"
  fi
}

if [[ -n "$DOCKER_CONTAINER" ]]; then
  echo "→ checking connection inside container '$DOCKER_CONTAINER' as $USER_NAME"
else
  echo "→ checking connection to $USER_NAME@$HOST:$PORT"
fi
if ! mysql_run -e "SELECT VERSION()" >/dev/null 2>&1; then
  if [[ -n "$DOCKER_CONTAINER" ]]; then
    echo "error: cannot connect to MySQL inside container '$DOCKER_CONTAINER' as $USER_NAME" >&2
    echo "  check SAFEDB_TEST_MYSQL_USER / SAFEDB_TEST_MYSQL_PASSWORD" >&2
  else
    echo "error: cannot connect to MySQL at $USER_NAME@$HOST:$PORT" >&2
    echo "  set SAFEDB_TEST_MYSQL_HOST / PORT / USER / PASSWORD and retry" >&2
  fi
  exit 1
fi
mysql_run -N -e "SELECT VERSION()" | awk '{print "  server version: " $0}'

if [[ "$RESET" -eq 1 ]]; then
  echo "→ dropping database '$DATABASE' (--reset)"
  mysql_run -e "DROP DATABASE IF EXISTS \`$DATABASE\`"
fi

echo "→ loading $SQL_FILE into '$DATABASE'"
mysql_run < "$SQL_FILE"

echo "→ verifying row counts"
mysql_run "$DATABASE" --skip-column-names -e "
  SELECT CONCAT('  ', t.table_name, ': ', c.cnt)
  FROM information_schema.tables t
  JOIN (
    SELECT 'categories'    AS table_name, COUNT(*) AS cnt FROM categories    UNION ALL
    SELECT 'products',                COUNT(*)         FROM products       UNION ALL
    SELECT 'customers',               COUNT(*)         FROM customers      UNION ALL
    SELECT 'orders',                  COUNT(*)         FROM orders         UNION ALL
    SELECT 'order_items',             COUNT(*)         FROM order_items    UNION ALL
    SELECT 'inventory_log',           COUNT(*)         FROM inventory_log
  ) c ON c.table_name = t.table_name
  WHERE t.table_schema = '$DATABASE'
  ORDER BY t.table_name;
"

echo "done."
if [[ -n "$DOCKER_CONTAINER" ]]; then
  echo "  connect with: docker exec -it $DOCKER_CONTAINER mysql -u $USER_NAME $DATABASE"
else
  echo "  connect with: mysql --defaults-file=$MYCNF $DATABASE"
fi
