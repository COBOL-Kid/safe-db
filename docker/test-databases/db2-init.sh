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

# LUW database names are at most 8 characters and cannot contain "_".
DBNAME="${DBNAME:-SAFEDB}"

# The instance can take several minutes to accept connections on first start.
for _ in $(seq 1 60); do
  if db2 CONNECT TO "$DBNAME" >/dev/null 2>&1; then
    break
  fi
  sleep 10
done
db2 CONNECT TO "$DBNAME"

# The 1M-row bulk fixture needs more transaction log than the image default
# (LOGFILSIZ 1024 x LOGPRIMARY 16 + LOGSECOND 22, ~152 MiB). Secondary logs
# grow on demand, so raise their ceiling before loading the fixture.
db2 "UPDATE DB CFG USING LOGSECOND 200" >/dev/null

# Db2 LUW has no DROP ... IF EXISTS: best-effort drops, ignoring SQL0204N for
# objects that do not exist yet so both fresh and repeat seeds work.
# Same list as SeedRelational.dropDb2ObjectsIfPresent.
DROP_LIST="/fixtures/db2-drop-statements.sql"
[[ -f "$DROP_LIST" ]] || {
  echo "Db2 drop list not found: $DROP_LIST" >&2
  exit 1
}
while IFS= read -r object || [[ -n "$object" ]]; do
  object="${object%$'\r'}"
  [[ -z "$object" || "$object" == --* ]] && continue
  db2 "$object" >/dev/null 2>&1 || true
done <"$DROP_LIST"

db2 -s -t -f /fixtures/testdata_db2.sql
# sqllib ships the LUW EXPLAIN DDL; without these tables EXPLAIN reports
# UnsupportedShape instead of a normalized plan. The DDL is plain CREATE TABLE,
# so a second seed (scripts/docker_test_databases.sh seed) must skip it once
# EXPLAIN_INSTANCE exists. COUNT(*) still returns a row when the table is
# absent, which keeps this query off the CLP's SQL0100W warning path.
explain_tables="$(db2 -x "SELECT COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA = CURRENT SCHEMA AND TABNAME = 'EXPLAIN_INSTANCE'")"
explain_tables="${explain_tables//[[:space:]]/}"
if [ "$explain_tables" = "0" ]; then
  db2 -s -t -f "$INSTANCE_HOME/sqllib/misc/EXPLAIN.DDL"
fi
db2 CONNECT RESET
