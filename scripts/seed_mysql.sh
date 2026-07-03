#!/usr/bin/env bash
# scripts/seed_mysql.sh - wrapper for the Kotlin MySQL seed task.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ ! -x "$REPO_ROOT/gradlew" ]]; then
  echo "error: Gradle wrapper not found at $REPO_ROOT/gradlew" >&2
  exit 1
fi

if [[ $# -eq 0 ]]; then
  exec "$REPO_ROOT/gradlew" -p "$REPO_ROOT" seedMysql
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

exec "$REPO_ROOT/gradlew" -p "$REPO_ROOT" seedMysql -PseedMysqlArgs="$args"
