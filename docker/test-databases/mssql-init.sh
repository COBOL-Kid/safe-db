#!/usr/bin/env bash
set -euo pipefail

/opt/mssql-tools18/bin/sqlcmd \
  -S mssql \
  -U sa \
  -P "$MSSQL_SA_PASSWORD" \
  -C \
  -b \
  -Q "IF DB_ID(N'safedb_ssl') IS NULL CREATE DATABASE safedb_ssl"

/opt/mssql-tools18/bin/sqlcmd \
  -S mssql \
  -U sa \
  -P "$MSSQL_SA_PASSWORD" \
  -C \
  -b \
  -d safedb_ssl \
  -i /fixtures/testdata_mssql.sql
