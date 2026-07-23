# safe-db

Desktop app for safely exploring production databases. Connect to PostgreSQL, MySQL, SQL Server, or Oracle, browse schema visually, build read-only queries in a canvas UI, and run them with guardrails: non-locking reads, enforced row limits, blocked system schemas, and EXPLAIN cost warnings.

safe-db is a **Jetpack Compose Desktop** app with a Kotlin/JDBC backend. The Gradle project lives at the repository root.

## Quick Start

```sh
./gradlew run
```

## Common Commands

```sh
./gradlew check                         # unit tests and verification gate
./gradlew koverHtmlReport               # merged desktop/shared coverage report
./gradlew integrationTest               # optional seeded MySQL/PostgreSQL contracts
./gradlew renderPreview                 # headless UI previews in /tmp/safedb-preview
./gradlew seedMysql                     # generated local MySQL fixture
./gradlew packageDistributionForCurrentOS
```

The root seed wrapper is still available:

```sh
scripts/seed_mysql.sh
```

## Features

- **Connections** — save named profiles; passwords stored in the OS credential store when available, never in profile JSON.
- **Schema browser** — tables, columns, and indexes with system/catalog schemas filtered out.
- **Visual query builder** — drag tables onto a canvas, join, filter, select columns, and set row limits; recursive filter groups support per-child AND/OR connector overrides.
- **Explore modes** — analyze the current immutable result sample as a nested pivot or worksheet. Worksheet mode adds direct sort/group/filter controls, row and group formulas, summaries, and window calculations such as running totals, previous values, percentages, and ranks; Visualization is reserved as the next mode.
- **Explore recipes** — save any combination of Pivot, Worksheet, and Visualization configurations, optionally with the Builder query, then reuse locally or share through versioned `.safedb-recipe.json` files.
- **Safety rails** — read-only `SELECT` queries, default 100 rows with fixed choices up to an interactive max of 5,000, guidance above 1,000 rows, 10 s timeout, custom blocked schemas, filter literal type validation, and a cost-preview guard.
- **Saved queries and history** — persisted through the Kotlin stores in the app data directory; timestamps are Unix-seconds strings.
- **Settings** — theme, `explain_cost_threshold`, and `blocked_schemas`.

## Supported Databases

| Database | Driver | Notes |
| --- | --- | --- |
| PostgreSQL | JDBC | |
| MySQL | JDBC | Uses read-only transactions for non-locking reads where supported. |
| SQL Server | JDBC | |
| Oracle | JDBC | Requires the Oracle JDBC driver/runtime available to the app. |

## Prerequisites

- **JDK 25**. The Gradle projects use `jvmToolchain(25)`.
- Network access to the databases you want to inspect.

## Local MySQL Test Database

Load a generated e-commerce fixture (`categories`, `products`, `customers`, `orders`, `order_items`, `inventory_log`) into `safedb_test`:

```sh
scripts/seed_mysql.sh                # generate + seed a larger reporting fixture (~50k orders)
scripts/seed_mysql.sh --static       # load the bundled testdata_mysql.sql fixture
scripts/seed_mysql.sh --reset-state  # also wipe safe-db connections + history
scripts/seed_mysql.sh --reset        # drop DB, then load generated data
```

Generated data is streamed directly into MySQL and is not checked in as a large SQL file. Tune it with script args:

```sh
scripts/seed_mysql.sh --orders 20000 --customers 5000 --seed 7
./gradlew seedMysql -PseedMysqlArgs="--orders 20000 --customers 5000 --seed 7"
```

The script targets `localhost:3306` as `root` by default. Override with `SAFEDB_TEST_MYSQL_*` env vars. If no `mysql` client is on `PATH`, it auto-detects a running MySQL/MariaDB Docker container and runs the client via `docker exec` (pin one with `SAFEDB_TEST_MYSQL_DOCKER=<name>`).

Integration tests skip locally when their seeded database is unavailable. CI sets `SAFEDB_TEST_REQUIRE_MYSQL=true` or `SAFEDB_TEST_REQUIRE_POSTGRES=true` so a configured engine must execute rather than silently skip. PostgreSQL uses the `SAFEDB_TEST_POSTGRES_*` variables and the minimal `testdata_postgres.sql` fixture.

Connect in the app: host `localhost`, port `3306`, database `safedb_test`, user `root` (empty password is valid for local Docker).

## Credentials & Keyring

Set `SAFEDB_KEYCHAIN_BACKEND=disabled` for in-memory credentials in debug or CI. Default `auto` uses Java keyring-backed platform stores where available:

| Platform | Store |
| --- | --- |
| macOS | Keychain-backed Java credential store |
| Windows | Credential Manager-backed Java credential store |
| Linux | Kernel keyutils when available |

On Linux hosts where the Java keyring delegate is unavailable, the app falls back to the in-memory `disabled` backend rather than writing a credential file. Saved connection profiles remain on disk, but passwords must be re-entered after restart.

**Test Connection** uses the password from the form only and does not touch the keyring. **Save Connection** stores the password in the selected credential backend. After the first unlock, builder and query paths reuse an in-process credential session so repeated schema loads and queries do not re-hit the OS store.

## Data Directory

The app stores JSON state under `com.safedb.app`:

| OS | Path |
| --- | --- |
| Linux | `~/.local/share/com.safedb.app/` |
| macOS | `~/Library/Application Support/com.safedb.app/` |
| Windows | `%APPDATA%\com.safedb.app\` |

Existing `connections.json`, `settings.json`, `saved_queries.json`, `query_history.json`, and `explore_recipes.json` files in that directory are reused automatically.

## Query Safety Behavior

The builder sends a structured query IR to the Kotlin query engine. The engine validates table/column references, blocked schemas, join eligibility, filter depth, literal types, and row limits before compiling dialect-specific SQL with bound parameters. The default row limit is 100, the builder offers fixed choices up to an interactive max of 5,000, and limits above 1,000 add guidance about filters, selected columns, and indexed predicates instead of blocking reporting-oriented work.

EXPLAIN runs against the post-validation SQL. If EXPLAIN fails or the estimated cost exceeds the configured threshold, the first run is blocked and the UI asks for explicit confirmation. Forced retries still run with the same row limit and timeout.

## Project Layout

```text
safe-db/
├── src/main/kotlin/com/safedb/        # Compose UI, app shell, platform helpers
├── src/main/resources/                # fonts and app resources
├── src/test/kotlin/                   # UI state tests
├── shared/
│   ├── src/main/kotlin/com/safedb/    # models, query engine, JDBC adapters, stores, secrets
│   └── src/test/kotlin/               # shared unit tests
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/                            # Gradle wrapper
├── scripts/seed_mysql.sh              # local MySQL fixture wrapper
├── testdata_mysql.sql                 # small static MySQL fixture
└── AGENTS.md                          # agent-oriented commands and workspace notes
```
