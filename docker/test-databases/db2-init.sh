#!/usr/bin/env bash
set -euo pipefail

# Seed the Db2 container: wait for the instance, reload the sample schema, and
# create the EXPLAIN tables that back the adapter's plan evidence.
# Runs inside the db2 container as the instance owner (see seed_db2 in
# scripts/docker_test_databases.sh, which execs this with -u db2inst1).
INSTANCE_HOME="$(getent passwd db2inst1 | cut -d: -f6)"
INSTANCE_HOME="${INSTANCE_HOME:-/home/db2inst1}"
# shellcheck disable=SC1091
. "$INSTANCE_HOME/sqllib/db2profile"

DBNAME="${DBNAME:-safedb_test}"

# The instance can take several minutes to accept connections on first start.
for _ in $(seq 1 60); do
  if db2 CONNECT TO "$DBNAME" >/dev/null 2>&1; then
    break
  fi
  sleep 10
done
db2 CONNECT TO "$DBNAME"

# Db2 LUW has no DROP ... IF EXISTS: best-effort drops, ignoring SQL0204N for
# objects that do not exist yet so both fresh and repeat seeds work.
for object in \
  "DROP VIEW CUSTOMER_ORDER_SUMMARY" \
  "DROP TABLE ORDER_ITEMS" \
  "DROP TABLE INVENTORY_LOG" \
  "DROP TABLE ORDERS" \
  "DROP TABLE PRODUCTS" \
  "DROP TABLE CUSTOMERS" \
  "DROP TABLE CATEGORIES"; do
  db2 "$object" >/dev/null 2>&1 || true
done

db2 -s -t -f /fixtures/testdata_db2.sql
# sqllib ships the LUW EXPLAIN DDL; without these tables EXPLAIN reports
# UnsupportedShape instead of a normalized plan.
db2 -s -t -f "$INSTANCE_HOME/sqllib/misc/EXPLAIN.DDL"
db2 CONNECT RESET
