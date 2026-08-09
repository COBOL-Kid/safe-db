#!/usr/bin/env bash
# Live SSL/TLS compatibility verification for Safe-DB adapters.
# Expects Docker sockets/containers prepared, or reuses /tmp/safedb-ssl fixtures.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSL_ROOT="${SAFEDB_SSL_ROOT:-/tmp/safedb-ssl}"
REPORT_DIR="${SAFEDB_SSL_REPORT_DIR:-$SSL_ROOT/reports}"
mkdir -p "$REPORT_DIR"

if [[ ! -f "$SSL_ROOT/trust/production.json" ]]; then
  echo "Missing $SSL_ROOT/trust/production.json — generate certs/fixtures first." >&2
  exit 1
fi

export SAFEDB_TEST_REQUIRE_SSL=true
export SAFEDB_TEST_SSL_LAUNCH_PROFILE="$SSL_ROOT/trust/production.json"
export SAFEDB_TEST_SSL_WRONG_LAUNCH_PROFILE="$SSL_ROOT/trust/wrong.json"
export SAFEDB_TEST_ORACLE_WALLET="${SAFEDB_TEST_ORACLE_WALLET:-$SSL_ROOT/wallet/client}"

export SAFEDB_TEST_MYSQL_SSL_HOST="${SAFEDB_TEST_MYSQL_SSL_HOST:-127.0.0.1}"
export SAFEDB_TEST_MYSQL_SSL_PORT="${SAFEDB_TEST_MYSQL_SSL_PORT:-3307}"
export SAFEDB_TEST_MYSQL_SSL_USER="${SAFEDB_TEST_MYSQL_SSL_USER:-safedb}"
export SAFEDB_TEST_MYSQL_SSL_PASSWORD="${SAFEDB_TEST_MYSQL_SSL_PASSWORD:-safedb}"
export SAFEDB_TEST_MYSQL_SSL_DATABASE="${SAFEDB_TEST_MYSQL_SSL_DATABASE:-safedb_ssl}"

export SAFEDB_TEST_POSTGRES_SSL_HOST="${SAFEDB_TEST_POSTGRES_SSL_HOST:-127.0.0.1}"
export SAFEDB_TEST_POSTGRES_SSL_PORT="${SAFEDB_TEST_POSTGRES_SSL_PORT:-5433}"
export SAFEDB_TEST_POSTGRES_SSL_USER="${SAFEDB_TEST_POSTGRES_SSL_USER:-postgres}"
export SAFEDB_TEST_POSTGRES_SSL_PASSWORD="${SAFEDB_TEST_POSTGRES_SSL_PASSWORD:-postgres}"
export SAFEDB_TEST_POSTGRES_SSL_DATABASE="${SAFEDB_TEST_POSTGRES_SSL_DATABASE:-safedb_ssl}"

export SAFEDB_TEST_MSSQL_SSL_HOST="${SAFEDB_TEST_MSSQL_SSL_HOST:-localhost}"
export SAFEDB_TEST_MSSQL_SSL_PORT="${SAFEDB_TEST_MSSQL_SSL_PORT:-14333}"
export SAFEDB_TEST_MSSQL_SSL_USER="${SAFEDB_TEST_MSSQL_SSL_USER:-sa}"
export SAFEDB_TEST_MSSQL_SSL_PASSWORD="${SAFEDB_TEST_MSSQL_SSL_PASSWORD:-SafeDb_Ssl_Passw0rd!}"
export SAFEDB_TEST_MSSQL_SSL_DATABASE="${SAFEDB_TEST_MSSQL_SSL_DATABASE:-safedb_ssl}"

export SAFEDB_TEST_ORACLE_SSL_HOST="${SAFEDB_TEST_ORACLE_SSL_HOST:-127.0.0.1}"
export SAFEDB_TEST_ORACLE_SSL_PORT="${SAFEDB_TEST_ORACLE_SSL_PORT:-1522}"
export SAFEDB_TEST_ORACLE_SSL_USER="${SAFEDB_TEST_ORACLE_SSL_USER:-safedb}"
export SAFEDB_TEST_ORACLE_SSL_PASSWORD="${SAFEDB_TEST_ORACLE_SSL_PASSWORD:-safedb}"
export SAFEDB_TEST_ORACLE_SSL_DATABASE="${SAFEDB_TEST_ORACLE_SSL_DATABASE:-FREEPDB1}"

export SAFEDB_KEYCHAIN_BACKEND=disabled

cd "$ROOT"
./gradlew :shared:integrationTest \
  --tests 'com.safedb.integration.SslCompatIntegrationTest' \
  --info \
  --stacktrace \
  "$@" \
  2>&1 | tee "$REPORT_DIR/ssl-compat-integration.log"

echo "SSL compatibility report: $REPORT_DIR/ssl-compat-integration.log"
