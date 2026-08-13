#!/usr/bin/env bash
# scripts/seed_mysql.sh - wrapper for the Kotlin MySQL seed task.
set -euo pipefail

exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/seed_relational.sh" mysql "$@"
