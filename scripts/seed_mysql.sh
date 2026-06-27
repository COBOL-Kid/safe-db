#!/usr/bin/env bash
# scripts/seed_mysql.sh — populate the safe-db MySQL test database.
#
# Targets the local MySQL server at localhost:3306. If a host-side `mysql`
# client is on PATH it is used directly; otherwise the script auto-detects a
# running mysql/mariadb Docker container and runs the client inside it via
# `docker exec` (the container's internal 127.0.0.1:3306 is the same server
# the host sees on localhost:3306 when the port is published). Loads
# testdata_mysql.sql into the 'safedb_test' database.
#
# Usage:
#   scripts/seed_mysql.sh                       # seed safedb_test
#   scripts/seed_mysql.sh --reset               # drop + recreate the database first
#   scripts/seed_mysql.sh --reset-state         # also wipe safe-db connections + history
#   scripts/seed_mysql.sh --reset --reset-state # drop DB and wipe safe-db state
#   scripts/seed_mysql.sh --help
#
# Env vars (all optional; defaults shown):
#   SAFEDB_TEST_MYSQL_HOST      localhost       (host-client mode only)
#   SAFEDB_TEST_MYSQL_PORT      3306            (host-client mode only)
#   SAFEDB_TEST_MYSQL_USER      root
#   SAFEDB_TEST_MYSQL_PASSWORD  (empty)
#   SAFEDB_TEST_MYSQL_DATABASE  safedb_test
#   SAFEDB_TEST_MYSQL_DOCKER    (empty)         pin a container name (forces docker exec)
#
# By default the script does NOT touch the local safe-db app state. Pass
# --reset-state to wipe connections.json and query_history.json in the app
# data dir (stale query_history.v1.bak is also removed). Saved queries and
# settings are always left untouched. This is opt-in to avoid surprising
# developers who have configured local connections for manual testing.
#
# Docker: when SAFEDB_TEST_MYSQL_PASSWORD is unset and USER is root, the script
# reads MYSQL_ROOT_PASSWORD from the container env (standard mysql image). This
# applies in docker-exec mode and in host-client mode when targeting localhost.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SQL_FILE="$REPO_ROOT/testdata_mysql.sql"

HOST="${SAFEDB_TEST_MYSQL_HOST:-localhost}"
PORT="${SAFEDB_TEST_MYSQL_PORT:-3306}"
USER_NAME="${SAFEDB_TEST_MYSQL_USER:-root}"
PASSWORD="${SAFEDB_TEST_MYSQL_PASSWORD:-}"
DATABASE="${SAFEDB_TEST_MYSQL_DATABASE:-safedb_test}"
DOCKER_PIN="${SAFEDB_TEST_MYSQL_DOCKER:-}"

sanitize_identifier() {
  local val="$1"
  local label="$2"
  if [[ ! "$val" =~ ^[A-Za-z0-9_.-]+$ ]]; then
    echo "error: invalid $label (allowed: letters, digits, ., _, -)" >&2
    exit 1
  fi
}

sanitize_config_value() {
  local val="$1"
  local label="$2"
  if [[ "$val" == *$'\n'* || "$val" == *$'\r'* ]]; then
    echo "error: invalid $label (must not contain newlines)" >&2
    exit 1
  fi
}

sanitize_identifier "$DATABASE" "SAFEDB_TEST_MYSQL_DATABASE"
sanitize_identifier "$HOST" "SAFEDB_TEST_MYSQL_HOST"
sanitize_identifier "$USER_NAME" "SAFEDB_TEST_MYSQL_USER"
sanitize_config_value "$PASSWORD" "SAFEDB_TEST_MYSQL_PASSWORD"
if [[ -n "$DOCKER_PIN" ]]; then
  sanitize_identifier "$DOCKER_PIN" "SAFEDB_TEST_MYSQL_DOCKER"
fi
if [[ ! "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 1 || PORT > 65535 )); then
  echo "error: invalid SAFEDB_TEST_MYSQL_PORT (must be 1-65535)" >&2
  exit 1
fi

usage() {
  sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

safedb_app_data_dir() {
  case "$(uname -s)" in
    Darwin) printf '%s\n' "$HOME/Library/Application Support/com.safedb.app" ;;
    Linux) printf '%s\n' "${XDG_DATA_HOME:-$HOME/.local/share}/com.safedb.app" ;;
    MINGW* | MSYS* | CYGWIN*) printf '%s\n' "${APPDATA:-$HOME/AppData/Roaming}/com.safedb.app" ;;
    *) return 1 ;;
  esac
}

reset_safedb_local_state() {
  local data_dir
  if ! data_dir="$(safedb_app_data_dir)"; then
    echo "→ skipping safe-db app state reset (unknown platform)"
    return 0
  fi
  if [[ ! -d "$data_dir" ]]; then
    echo "→ no safe-db app data at $data_dir; skipping connection/history reset"
    return 0
  fi
  echo "→ resetting safe-db connections and query history ($data_dir)"
  printf '[]\n' > "$data_dir/connections.json"
  printf '[]\n' > "$data_dir/query_history.json"
  rm -f "$data_dir/query_history.v1.bak"
}

RESET=0
RESET_STATE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --reset) RESET=1; shift ;;
    --reset-state) RESET_STATE=1; shift ;;
    -h | --help) usage 0 ;;
    --) shift; break ;;
    -*) echo "unknown argument: $1" >&2; usage 1 ;;
    *) echo "unexpected positional argument: $1" >&2; usage 1 ;;
  esac
done

if [[ ! -f "$SQL_FILE" ]]; then
  echo "error: SQL file not found: $SQL_FILE" >&2
  exit 1
fi

# Read a single env var from a Docker container's Config.Env (value may contain '=').
docker_env_var() {
  local container="$1" key="$2"
  docker inspect "$container" --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | awk -F= -v k="$key" '$1 == k { print substr($0, index($0, "=") + 1); exit }'
}

# Decide how to reach the mysql client. A pinned SAFEDB_TEST_MYSQL_DOCKER always
# wins (even when a host mysql client is installed). Otherwise use the host client
# if present, or auto-detect a running mysql/mariadb container.
DOCKER_CONTAINER=""

if [[ -n "$DOCKER_PIN" ]]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo "error: SAFEDB_TEST_MYSQL_DOCKER set but 'docker' not found in PATH" >&2
    exit 1
  fi
  if ! docker inspect "$DOCKER_PIN" >/dev/null 2>&1; then
    echo "error: docker container '$DOCKER_PIN' not found" >&2
    echo "  running containers:" >&2
    docker ps --format '    {{.Names}} ({{.Image}})' >&2 || true
    exit 1
  fi
  DOCKER_CONTAINER="$DOCKER_PIN"
elif command -v mysql >/dev/null 2>&1; then
  : # host-client mode
elif command -v docker >/dev/null 2>&1; then
  matches="$(docker ps --filter "status=running" --format '{{.Names}}\t{{.Image}}' \
    | awk -F'\t' 'tolower($2) ~ /mysql|mariadb/ {print $1}')"
  count="$(printf '%s\n' "$matches" | grep -c . || true)"
  if [[ "$count" -eq 0 ]]; then
    echo "error: no 'mysql' client on PATH and no running mysql/mariadb container" >&2
    echo "  install a client (brew install mysql-client / apt install default-mysql-client)" >&2
    echo "  or start a MySQL container, or set SAFEDB_TEST_MYSQL_DOCKER=<name>" >&2
    exit 1
  fi
  if [[ "$count" -gt 1 ]]; then
    echo "error: multiple mysql/mariadb containers running; pin one:" >&2
    printf '    %s\n' $matches >&2
    echo "  hint: SAFEDB_TEST_MYSQL_DOCKER=<name>" >&2
    exit 1
  fi
  DOCKER_CONTAINER="$matches"
else
  echo "error: no 'mysql' client in PATH and 'docker' not found either" >&2
  echo "  install with: brew install mysql-client (macOS) or apt install default-mysql-client (Debian/Ubuntu)" >&2
  exit 1
fi

# In docker-exec mode, inherit the container's MYSQL_ROOT_PASSWORD when the caller
# did not set SAFEDB_TEST_MYSQL_PASSWORD (typical docker run -e MYSQL_ROOT_PASSWORD=…).
if [[ -n "$DOCKER_CONTAINER" && -z "$PASSWORD" && "$USER_NAME" == "root" ]]; then
  PASSWORD="$(docker_env_var "$DOCKER_CONTAINER" MYSQL_ROOT_PASSWORD)"
fi

# Host-client mode against localhost: if password is still empty, look for a mysql/mariadb
# container publishing the target port and borrow its MYSQL_ROOT_PASSWORD.
if [[ -z "$DOCKER_CONTAINER" && -z "$PASSWORD" && "$USER_NAME" == "root" ]] \
    && { [[ "$HOST" == "localhost" ]] || [[ "$HOST" == "127.0.0.1" ]]; } \
    && command -v docker >/dev/null 2>&1; then
  host_docker="$(docker ps --filter "status=running" --filter "publish=$PORT" \
    --format '{{.Names}}\t{{.Image}}' \
    | awk -F'\t' 'tolower($2) ~ /mysql|mariadb/ { print $1; exit }')"
  if [[ -n "$host_docker" ]]; then
    PASSWORD="$(docker_env_var "$host_docker" MYSQL_ROOT_PASSWORD)"
  fi
fi

# Host-client mode: temp 0600 [client] config so the password is not on the
# command line and `mysql -p` does not block on a TTY. Removed on exit.
MYCNF=""
mysql_run() {
  if [[ -n "$DOCKER_CONTAINER" ]]; then
    docker exec -i -e MYSQL_PWD="$PASSWORD" "$DOCKER_CONTAINER" \
      mysql -h 127.0.0.1 -P 3306 -u "$USER_NAME" "$@"
  else
    mysql --defaults-file="$MYCNF" "$@"
  fi
}

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

if [[ -n "$DOCKER_CONTAINER" ]]; then
  echo "→ using docker container: $DOCKER_CONTAINER  (connecting to 127.0.0.1:3306 as $USER_NAME)"
else
  echo "→ checking connection to $USER_NAME@$HOST:$PORT"
fi
if ! mysql_run -e "SELECT VERSION()" >/dev/null 2>&1; then
  if [[ -n "$DOCKER_CONTAINER" ]]; then
    echo "error: cannot connect to MySQL inside container '$DOCKER_CONTAINER' as $USER_NAME" >&2
    echo "  set SAFEDB_TEST_MYSQL_PASSWORD (or MYSQL_ROOT_PASSWORD on the container)" >&2
    echo "  and SAFEDB_TEST_MYSQL_USER if not using root" >&2
  else
    echo "error: cannot connect to MySQL at $USER_NAME@$HOST:$PORT" >&2
    echo "  set SAFEDB_TEST_MYSQL_HOST / PORT / USER / PASSWORD and retry" >&2
  fi
  exit 1
fi
mysql_run -N -e "SELECT VERSION()" | awk '{print "  server version: " $0}'

if [[ "$RESET_STATE" -eq 1 ]]; then
  reset_safedb_local_state
else
  echo "→ keeping safe-db connections and query history (pass --reset-state to wipe)"
fi

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