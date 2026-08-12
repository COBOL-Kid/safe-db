#!/usr/bin/env bash
# scripts/seed_relational.sh <dialect> [seeder args...] - shared wrapper for the Kotlin seed tasks.
# The per-dialect scripts (seed_mysql.sh, seed_postgres.sh, seed_mssql.sh, seed_oracle.sh) exec this.

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: seed_relational.sh <mysql|postgres|mssql|oracle> [seeder args...]" >&2
  exit 1
fi

dialect="$1"
shift

case "$dialect" in
  mysql) task="seedMysql" ;;
  postgres) task="seedPostgres" ;;
  mssql) task="seedMssql" ;;
  oracle) task="seedOracle" ;;
  *)
    echo "error: unknown dialect '$dialect' (expected mysql, postgres, mssql, or oracle)" >&2
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ ! -x "$REPO_ROOT/gradlew" ]]; then
  echo "error: Gradle wrapper not found at $REPO_ROOT/gradlew" >&2
  exit 1
fi

# The seeder arguments travel as one Gradle property, so re-quote each of them.
args=""
for arg in "$@"; do
  escaped="${arg//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  [[ -z "$args" ]] || args+=" "
  args+="\"$escaped\""
done

exec "$REPO_ROOT/gradlew" -p "$REPO_ROOT" "$task" "-P${task}Args=$args"
