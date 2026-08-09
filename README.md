# safe-db

safe-db is a Jetpack Compose Desktop application for safely exploring PostgreSQL, MySQL, SQL Server, and Oracle on macOS and Windows. It provides visual schema exploration and read-only query building with enforced limits, blocked system schemas, and EXPLAIN-informed risk scoring.

## Requirements, start, and verify

safe-db supports macOS and Windows only. It requires JDK 25 (`jvmToolchain(25)`) and network access to the database; unsupported operating systems exit before any profile, credential-store, or app-data initialization.

```sh
./gradlew run
./gradlew check
```

| Command | Purpose |
| --- | --- |
| `./gradlew check` | Unit tests, test-discovery, and coverage gate. |
| `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache` | Fresh coverage proof after broad Kotlin or build changes. |
| `./gradlew integrationTest` | Optional PostgreSQL/MySQL JDBC contracts. |
| `./gradlew renderPreview --rerun-tasks` | Headless UI renders in `/tmp/safedb-preview/`. |
| `./gradlew renderThemeGallery` | Connections and settings renders for every color scheme. |
| `./gradlew seedMysql` | Generate and seed the local MySQL fixture. |
| `scripts/verify_ssl_compat.sh` | Environment-gated TLS launch-profile and dialect-compatibility suite. |
| `./gradlew packageDistributionForCurrentOS` | Unsigned DMG on macOS or MSI on Windows. |

Use the Gradle wrapper rather than a system Gradle installation. Do not run `./gradlew run` alongside daemon-less builds.

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

For a local PostgreSQL instance using the test defaults, create `safedb_test` and load the checked-in fixture before running the required suite:

```sh
PGPASSWORD=postgres psql -h localhost -U postgres -d safedb_test \
  -f testdata_postgres.sql

SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_POSTGRES=true \
  ./gradlew integrationTest --stacktrace
```

Set matching `SAFEDB_TEST_POSTGRES_*` variables when using another PostgreSQL endpoint. `scripts/verify_ssl_compat.sh` expects pre-provisioned TLS endpoints and launch profiles under `/tmp/safedb-ssl` by default. Set `SAFEDB_SSL_ROOT` (and the documented endpoint overrides in the script) when using another location.

## Safety, data, and trust

Queries are validated against the loaded schema and compile with bound parameters. They are read-only, default to 100 rows, cannot exceed 5,000 rows, time out after 10 seconds, and show guidance above 1,000 rows. Missing plan evidence or optimizer cost requires an explicit confirmed retry.

App data lives in `~/Library/Application Support/com.safedb.app/` on macOS and `%APPDATA%\com.safedb.app\` on Windows. `SAFEDB_KEYCHAIN_BACKEND=disabled` uses in-memory credentials for development or CI; `Test Connection` does not write the keyring, while `Save Connection` does.

Managed installations can supply a PKCS12 trust store only through startup launch-profile JSON. It is never saved with a connection; the profile contains a password source, never the password itself. Use either the strict platform credential store or a protected one-line password file. See [external trust stores](docs/trust-stores.md).

CI is on demand: a maintainer applies the `ci:run` label to a pull request to run `check` and the required static-MySQL integration suite. Remove and reapply the label after new commits to request another run. The cross-platform durability suite is available through **Run workflow** in GitHub Actions, while dependency submission continues automatically for qualifying trusted `main` changes. See [workflows](.github/workflows/) for details.
