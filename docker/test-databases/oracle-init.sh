#!/usr/bin/env bash
set -euo pipefail

# Run inside the database container so Oracle OS authentication keeps the
# administrator password out of command arguments, environment, and logs.
sqlplus -s /nolog <<'SQL'
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE
CONNECT / AS SYSDBA
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = SAFEDB;
@/fixtures/testdata_oracle.sql
EXIT SUCCESS
SQL
