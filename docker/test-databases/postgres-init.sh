#!/usr/bin/env bash
set -euo pipefail

createdb --username "$POSTGRES_USER" safedb_test
psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname safedb_test \
  --file /fixtures/testdata_postgres.sql
