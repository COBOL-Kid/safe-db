#!/usr/bin/env bash
# scripts/seed_mysql.sh - wrapper for the Compose/Kotlin MySQL seed task.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_DIR="$REPO_ROOT/compose-app"

if [[ ! -x "$COMPOSE_DIR/gradlew" ]]; then
  echo "error: Gradle wrapper not found at $COMPOSE_DIR/gradlew" >&2
  exit 1
fi

if [[ $# -eq 0 ]]; then
  exec "$COMPOSE_DIR/gradlew" -p "$COMPOSE_DIR" seedMysql
fi

args=""
for arg in "$@"; do
  escaped="${arg//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  if [[ -n "$args" ]]; then
    args+=" "
  fi
  args+="\"$escaped\""
done

exec "$COMPOSE_DIR/gradlew" -p "$COMPOSE_DIR" seedMysql -PseedMysqlArgs="$args"
