#!/usr/bin/env bash
# Wrapper for the deterministic Oracle fixture generator.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
args=""
for arg in "$@"; do
  escaped="${arg//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  [[ -z "$args" ]] || args+=" "
  args+="\"$escaped\""
done

exec "$REPO_ROOT/gradlew" -p "$REPO_ROOT" seedOracle -PseedOracleArgs="$args"
