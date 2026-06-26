#!/usr/bin/env bash
# scripts/seed_testdb.sh — populate the safe-db MySQL test database.
#
# Usage:
#   scripts/seed_testdb.sh                  # seed safedb_test on localhost:3306
#   scripts/seed_testdb.sh --reset          # drop + recreate the database first
#   SAFEDB_TEST_MYSQL_HOST=db.local scripts/seed_testdb.sh
#   scripts/seed_testdb.sh --help
#
# Env vars (all optional; defaults shown):
#   SAFEDB_TEST_MYSQL_HOST      localhost
#   SAFEDB_TEST_MYSQL_PORT      3306
#   SAFEDB_TEST_MYSQL_USER      root
#   SAFEDB_TEST_MYSQL_PASSWORD  (empty)
#   SAFEDB_TEST_MYSQL_DATABASE  safedb_test
#
# Requires: the `mysql` client in PATH. On macOS, install with
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

usage() {
  sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

RESET=0
for arg in "$@"; do
  case "$arg" in
    --reset) RESET=1 ;;
    -h | --help) usage 0 ;;
    *) echo "unknown argument: $arg" >&2; usage 1 ;;
  esac
done

if ! command -v mysql >/dev/null 2>&1; then
  echo "error: 'mysql' client not found in PATH" >&2
  echo "  install with: brew install mysql-client (macOS) or apt install default-mysql-client (Debian/Ubuntu)" >&2
  exit 1
fi

if [[ ! -f "$SQL_FILE" ]]; then
  echo "error: SQL file not found: $SQL_FILE" >&2
  exit 1
fi

# Build a temporary mysql config file so the password is not exposed on the
# command line and so `mysql -p` does not block waiting for a TTY. The file is
# 0600 and removed on exit.
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

mysql_run() {
  mysql --defaults-file="$MYCNF" "$@"
}

echo "→ checking connection to $USER_NAME@$HOST:$PORT"
if ! mysql_run -e "SELECT VERSION()" >/dev/null 2>&1; then
  echo "error: cannot connect to MySQL at $USER_NAME@$HOST:$PORT" >&2
  echo "  set SAFEDB_TEST_MYSQL_HOST / PORT / USER / PASSWORD and retry" >&2
  exit 1
fi
mysql_run -e "SELECT VERSION()" | awk '{print "  server version: " $0}'

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
echo "  connect with: mysql --defaults-file=$MYCNF $DATABASE"
