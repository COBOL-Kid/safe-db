#!/usr/bin/env bash
# Provision and operate the complete local JDBC/TLS integration-test stack.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSL_ROOT="${SAFEDB_SSL_ROOT:-$ROOT/.docker/safedb-ssl}"
export SAFEDB_SSL_ROOT="$SSL_ROOT"
COMPOSE=(docker compose --project-directory "$ROOT" --file "$ROOT/compose.yaml")
TRUST_PASSWORD="safedb-test-trust"
MYSQL_PASSWORD="${SAFEDB_DOCKER_MYSQL_PASSWORD:-safedb}"
POSTGRES_PASSWORD="${SAFEDB_DOCKER_POSTGRES_PASSWORD:-postgres}"
MSSQL_PASSWORD="${SAFEDB_DOCKER_MSSQL_PASSWORD:-SafeDb_Ssl_Passw0rd!}"
ORACLE_PASSWORD="${SAFEDB_DOCKER_ORACLE_PASSWORD:-safedb}"

usage() {
  cat <<'EOF'
Usage: scripts/docker_test_databases.sh <command>

Commands:
  up       Generate local test certificates and start all four databases.
  seed     Reload the SQL Server and Oracle sample schemas and data.
  down     Remove the containers and their anonymous database volumes.
  reset    Regenerate certificates and recreate the ephemeral stack.
  verify   Run required PostgreSQL/MySQL JDBC tests and the four-dialect TLS suite.
  certs    Regenerate certificates and profiles while the stack is stopped.

The generated CA keys, server keys, trust stores, profiles, and reports live under
.docker/safedb-ssl (or SAFEDB_SSL_ROOT) and are excluded from Git.
Certificate rotation refuses to run while project services are active; use reset
to rotate certificates and recreate the ephemeral stack safely.
EOF
}

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required tool not found: $1" >&2
    exit 1
  fi
}

write_server_extensions() {
  local path="$1"
  local service="$2"
  cat >"$path" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:$service
EOF
}

create_ca() {
  local directory="$1"
  local common_name="$2"
  mkdir -p "$directory"
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
    -subj "/CN=$common_name" \
    -keyout "$directory/ca.key" \
    -out "$directory/ca.crt" >/dev/null 2>&1
}

create_server_certificate() {
  local service="$1"
  local directory="$SSL_ROOT/servers/$service"
  local extensions="$directory/server.ext"
  mkdir -p "$directory"
  write_server_extensions "$extensions" "$service"
  openssl req -new -newkey rsa:3072 -sha256 -nodes \
    -subj "/CN=localhost" \
    -keyout "$directory/server.key" \
    -out "$directory/server.csr" >/dev/null 2>&1
  openssl x509 -req -sha256 -days 825 \
    -in "$directory/server.csr" \
    -CA "$SSL_ROOT/ca/ca.crt" \
    -CAkey "$SSL_ROOT/ca/ca.key" \
    -CAserial "$SSL_ROOT/ca/ca.srl" \
    -CAcreateserial \
    -extfile "$extensions" \
    -out "$directory/server.crt" >/dev/null 2>&1
  cp "$SSL_ROOT/ca/ca.crt" "$directory/ca.crt"
  cat "$directory/server.crt" "$SSL_ROOT/ca/ca.crt" >"$directory/server-fullchain.crt"
  rm -f "$directory/server.csr" "$extensions"
}

write_profiles() {
  require_tool keytool
  local trust_dir="$SSL_ROOT/trust"
  local production_store="$trust_dir/production.p12"
  local wrong_store="$trust_dir/wrong.p12"
  local password_file="$trust_dir/password"
  mkdir -p "$trust_dir" "$SSL_ROOT/wallet/client"
  printf '%s\n' "$TRUST_PASSWORD" >"$password_file"
  chmod 0600 "$password_file"
  rm -f "$production_store" "$wrong_store"
  keytool -importcert -noprompt -storetype PKCS12 \
    -alias safedb-test-ca \
    -file "$SSL_ROOT/ca/ca.crt" \
    -keystore "$production_store" \
    -storepass:file "$password_file" >/dev/null 2>&1
  keytool -importcert -noprompt -storetype PKCS12 \
    -alias safedb-wrong-test-ca \
    -file "$SSL_ROOT/wrong-ca/ca.crt" \
    -keystore "$wrong_store" \
    -storepass:file "$password_file" >/dev/null 2>&1
  cp "$production_store" "$SSL_ROOT/wallet/client/ewallet.p12"
  cat >"$trust_dir/production.json" <<EOF
{"schemaVersion":1,"trustStore":{"type":"PKCS12","path":"$production_store","password":{"source":"file","path":"$password_file"}}}
EOF
  cat >"$trust_dir/wrong.json" <<EOF
{"schemaVersion":1,"trustStore":{"type":"PKCS12","path":"$wrong_store","password":{"source":"file","path":"$password_file"}}}
EOF
}

generate_certificates() {
  require_tool openssl
  require_tool keytool
  if [[ "$SSL_ROOT" != /* || "$SSL_ROOT" == "/" || "$SSL_ROOT" == "$ROOT" ]]; then
    echo "Refusing unsafe SAFEDB_SSL_ROOT: $SSL_ROOT" >&2
    exit 1
  fi
  rm -rf "$SSL_ROOT/ca" "$SSL_ROOT/wrong-ca" "$SSL_ROOT/servers" "$SSL_ROOT/trust" "$SSL_ROOT/wallet"
  create_ca "$SSL_ROOT/ca" "safe-db local test CA"
  create_ca "$SSL_ROOT/wrong-ca" "safe-db intentionally wrong test CA"
  create_server_certificate mysql
  create_server_certificate postgres
  create_server_certificate mssql
  write_profiles
  chmod 0600 "$SSL_ROOT"/servers/*/server.key
  chmod 0644 "$SSL_ROOT/servers/mssql/server.key"
  echo "Generated disposable TLS fixtures in $SSL_ROOT"
}

certificates_missing() {
  [[ ! -f "$SSL_ROOT/trust/production.p12" ||
     ! -f "$SSL_ROOT/servers/mysql/server.crt" ||
     ! -f "$SSL_ROOT/servers/postgres/server.crt" ||
     ! -f "$SSL_ROOT/servers/mssql/server.crt" ]]
}

running_stack_services() {
  if ! command -v docker >/dev/null 2>&1; then
    return 0
  fi
  "${COMPOSE[@]}" ps --status running --services 2>/dev/null || true
}

refuse_certificate_rotation_if_running() {
  local running
  running="$(running_stack_services)"
  if [[ -z "$running" ]]; then
    return
  fi
  echo "Refusing to rotate safe-db TLS fixtures while project services are running:" >&2
  while IFS= read -r service; do
    [[ -z "$service" ]] || echo "  $service" >&2
  done <<<"$running"
  echo "Run scripts/docker_test_databases.sh reset to rotate certificates safely." >&2
  exit 1
}

ensure_certificates() {
  local allow_running_rotation="${1:-false}"
  if certificates_missing; then
    if [[ "$allow_running_rotation" != "true" ]]; then
      refuse_certificate_rotation_if_running
    fi
    generate_certificates
  else
    write_profiles
  fi
}

start_stack() {
  require_tool docker
  ensure_certificates true
  "${COMPOSE[@]}" up -d --wait --renew-anon-volumes \
    mysql-connectivity mysql postgres mssql oracle
  seed_mssql_and_oracle
  echo "All safe-db test databases are healthy and seeded."
}

seed_mssql_and_oracle() {
  "${COMPOSE[@]}" up --no-deps --exit-code-from mssql-init mssql-init
  "${COMPOSE[@]}" exec -T oracle /usr/local/bin/safedb-oracle-init.sh
}

verify_stack() {
  ensure_certificates
  cd "$ROOT"
  SAFEDB_KEYCHAIN_BACKEND=disabled \
  SAFEDB_TEST_REQUIRE_MYSQL=true \
  SAFEDB_TEST_MYSQL_HOST=127.0.0.1 \
  SAFEDB_TEST_MYSQL_PORT=3306 \
  SAFEDB_TEST_MYSQL_USER=safedb \
  SAFEDB_TEST_MYSQL_PASSWORD="$MYSQL_PASSWORD" \
  SAFEDB_TEST_MYSQL_DATABASE=safedb_test \
  SAFEDB_TEST_REQUIRE_POSTGRES=true \
  SAFEDB_TEST_POSTGRES_HOST=127.0.0.1 \
  SAFEDB_TEST_POSTGRES_PORT=5433 \
  SAFEDB_TEST_POSTGRES_USER=postgres \
  SAFEDB_TEST_POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  SAFEDB_TEST_POSTGRES_DATABASE=safedb_test \
    ./gradlew integrationTest --rerun-tasks --no-build-cache --stacktrace
  SAFEDB_TEST_MYSQL_SSL_PASSWORD="$MYSQL_PASSWORD" \
  SAFEDB_TEST_POSTGRES_SSL_PASSWORD="$POSTGRES_PASSWORD" \
  SAFEDB_TEST_MSSQL_SSL_PASSWORD="$MSSQL_PASSWORD" \
  SAFEDB_TEST_ORACLE_SSL_PASSWORD="$ORACLE_PASSWORD" \
  SAFEDB_SSL_ROOT="$SSL_ROOT" \
    ./scripts/verify_ssl_compat.sh --rerun-tasks --no-build-cache
}

command="${1:-}"
case "$command" in
  up)
    start_stack
    ;;
  seed)
    require_tool docker
    seed_mssql_and_oracle
    ;;
  down)
    require_tool docker
    "${COMPOSE[@]}" down --volumes --remove-orphans
    ;;
  reset)
    require_tool docker
    "${COMPOSE[@]}" down --volumes --remove-orphans
    generate_certificates
    start_stack
    ;;
  verify)
    verify_stack
    ;;
  certs)
    refuse_certificate_rotation_if_running
    generate_certificates
    ;;
  *)
    usage
    [[ -z "$command" ]] || exit 1
    ;;
esac
