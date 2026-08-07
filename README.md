# safe-db

safe-db is a Jetpack Compose Desktop application for safely exploring PostgreSQL, MySQL, SQL Server, and Oracle on macOS and Windows. It provides visual schema exploration and read-only query building with enforced limits, blocked system schemas, and EXPLAIN-informed risk scoring.

## Start and verify

Requires JDK 25 (`jvmToolchain(25)`) and network access to the database.

```sh
./gradlew run
./gradlew check
```

| Command | Purpose |
| --- | --- |
| `./gradlew check` | Unit tests, test-discovery, and coverage gate. |
| `./gradlew integrationTest` | Optional PostgreSQL/MySQL JDBC contracts. |
| `./gradlew renderPreview --rerun-tasks` | Headless UI renders in `/tmp/safedb-preview/`. |
| `./gradlew renderThemeGallery` | Connections and settings renders for every color scheme. |
| `./gradlew seedMysql` | Generate and seed the local MySQL fixture. |
| `./gradlew packageDistributionForCurrentOS` | Unsigned DMG on macOS or MSI on Windows. |

## What it does

- Saves named connection profiles without passwords in profile JSON; credentials use the platform store when available.
- Browses schemas and maps selected-schema tables, keys, indexes, and referenced external tables. The map is read-only and does not alter the Builder query.
- Builds typed, parameterized queries with joins, nested filters, and controlled row limits.
- Explores the immutable result sample through Pivot, Worksheet, and Visualization views; recipes store configuration only, never credentials or result rows.

| Database | Transport notes |
| --- | --- |
| PostgreSQL | Verified TLS keeps pgjdbc's normal trust and client-certificate behavior unless a launch profile is selected. |
| MySQL | Uses read-only transactions for non-locking reads where supported. |
| SQL Server | Certificate verification uses JVM or launch-profile trust. |
| Oracle | Verified TCPS uses an Oracle wallet. |

## Local MySQL fixture

The fixture targets `localhost:3306`, database `safedb_test`, user `root`; an empty password is valid. The script accepts `SAFEDB_TEST_MYSQL_*` overrides and can use a running Docker MySQL/MariaDB container when no local client is available.

```sh
scripts/seed_mysql.sh                # generated reporting fixture (~50k orders)
scripts/seed_mysql.sh --static       # checked-in fixture used by pull-request CI
scripts/seed_mysql.sh --orders 20000 --customers 5000 --seed 7

SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_MYSQL=true \
  ./gradlew integrationTest --stacktrace
```

Integration tests skip when no selected fixture is available. Set `SAFEDB_TEST_REQUIRE_MYSQL=true` or `SAFEDB_TEST_REQUIRE_POSTGRES=true` to require execution; PostgreSQL uses matching `SAFEDB_TEST_POSTGRES_*` variables and `testdata_postgres.sql`.

## Safety, data, and trust

Queries are validated against the loaded schema and compile with bound parameters. They are read-only, default to 100 rows, cannot exceed 5,000 rows, time out after 10 seconds, and show guidance above 1,000 rows. Missing plan evidence or optimizer cost requires an explicit confirmed retry.

App data lives in `~/Library/Application Support/com.safedb.app/` on macOS and `%APPDATA%\com.safedb.app\` on Windows. `SAFEDB_KEYCHAIN_BACKEND=disabled` uses in-memory credentials for development or CI; `Test Connection` does not write the keyring, while `Save Connection` does.

Managed installations can supply a PKCS12 trust store only through startup launch-profile JSON. It is never saved with a connection, and its password must come from the platform credential store or a protected file. See [external trust stores](docs/trust-stores.md).

CI runs `check` on relevant pull requests, required MySQL integration where applicable, and a weekly durability suite across macOS, Windows, and Ubuntu. See [workflows](.github/workflows/) for exact triggers and path filters.
