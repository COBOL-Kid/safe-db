#!/usr/bin/env bash
# Wrapper for the deterministic PostgreSQL fixture generator.
set -euo pipefail

exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/seed_relational.sh" postgres "$@"
