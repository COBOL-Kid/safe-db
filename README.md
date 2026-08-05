# safe-db

Desktop app for safely exploring production databases. Connect to PostgreSQL, MySQL, SQL Server, or Oracle, browse schema visually, build read-only queries in a canvas UI, and run them with guardrails: non-locking reads, enforced row limits, blocked system schemas, and EXPLAIN-informed risk scoring.

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

## Pull Request Checks

For changes covered by the Compose workflow's path filters, its jobs do not run for draft pull requests. Marking such a pull request ready for review runs the standard `./gradlew check` gate.

MySQL integration is limited to changes under `shared/`, `buildSrc/`, `gradle/`, root Gradle and wrapper files, `scripts/`, or `testdata_mysql.sql`. It is skipped for changes limited to `src/`, `testdata_postgres.sql`, or the Compose workflow itself. Same-repository pull requests run eligible integration automatically; fork pull requests also need the `ci:integration` label from a maintainer. External-contributor approval gates the entire fork workflow, while the label controls only MySQL eligibility and does not bypass approval.

Before public launch, maintainers must create the exact `ci:integration` label and configure GitHub Actions to require approval for all external contributors. The workflow file does not create the label or enable that repository setting. Compose checks also support merge queues through `merge_group`, and the separate Workflow lint workflow validates workflow-only changes.

The Durability workflow runs every Monday at 09:00 UTC and on manual dispatch. It reruns `check` on Linux, macOS, and Windows; requires PostgreSQL and generated-fixture MySQL integration; validates UI previews; and builds the Linux package. Dependency submission is not a pull-request check: when the repository is public, it runs for selected dependency-file updates on trusted `main` or by manual dispatch. Dependabot checks Gradle and GitHub Actions monthly and groups minor and patch updates.

## Features

- **Connections** — save named profiles; passwords are stored in the OS credential store when available, never in profile JSON. Advanced settings expose explicit TLS verification modes, optional connection-specific CA certificates, and Oracle wallet configuration.
- **Schema browser** — tables, columns, and indexes with system/catalog schemas filtered out.
- **Visual query builder** — drag tables onto a canvas, join, filter, select columns, and set row limits; recursive filter groups support per-child AND/OR connector overrides.
- **Explore modes** — analyze the current immutable result sample as a nested pivot, worksheet, or visualization. Worksheet mode adds direct sort/group/filter controls, row and group formulas, summaries, and window calculations such as running totals, previous values, percentages, and ranks. Visualization offers templates and editable field shelves for bar, line, scatter, histogram, and KPI charts, with contributing-row drill-through plus PNG and chart-data CSV exports.
- **Explore recipes** — save any combination of Pivot, Worksheet, and Visualization configurations, optionally with the Builder query, then reuse locally or share through versioned `.safedb-recipe.json` files.
- **Safety rails** — read-only `SELECT` queries, default 100 rows with fixed choices up to an interactive max of 5,000, guidance above 1,000 rows, 10 s timeout, restricted schemas, filter literal type validation, and EXPLAIN-informed risk scoring.
- **Saved queries and history** — persisted through the Kotlin stores in the app data directory; timestamps are Unix-seconds strings.
- **Settings** — default query location, appearance, and query-risk gate.

## Supported Databases

| Database | Driver | Notes |
| --- | --- | --- |
| PostgreSQL | JDBC | Verified TLS preserves pgjdbc's standard trust and client-certificate behavior unless a connection CA or launch profile is selected. |
| MySQL | JDBC | Uses read-only transactions for non-locking reads where supported. |
| SQL Server | JDBC | Certificate verification supports the JVM/launch-profile trust store or a connection-specific CA. |
| Oracle | JDBC | Verified TCPS uses an Oracle wallet; external launch-profile trust stores do not replace it. |

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

To reproduce the pull-request MySQL job against the default local fixture:

```sh
scripts/seed_mysql.sh --static
SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_MYSQL=true ./gradlew integrationTest --stacktrace
```

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

## External Trust Stores

Managed installations can select an external PKCS12 trust store at startup without adding trust-store settings to saved database connections. Passwords are resolved from the platform credential store or a protected file, not JSON, command-line arguments, or environment variables. A connection-specific CA takes precedence. Without a launch profile, MySQL and SQL Server use the bundled JVM trust store, while PostgreSQL retains pgjdbc's standard trust and client-certificate behavior; Oracle remains wallet-based. See [External trust stores](docs/trust-stores.md) for the launch-profile schema, provisioning guidance, and managed-launch examples.

## Data Directory

The app stores JSON state under `com.safedb.app`:

| OS | Path |
| --- | --- |
| Linux | `$XDG_DATA_HOME/com.safedb.app/` when set; otherwise `~/.local/share/com.safedb.app/` |
| macOS | `~/Library/Application Support/com.safedb.app/` |
| Windows | `%APPDATA%\com.safedb.app\` |

Existing `connections.json`, `settings.json`, `saved_queries.json`, `query_history.json`, and `explore_recipes.json` files in that directory are reused automatically.

## Query Safety Behavior

The builder sends a structured query IR to the Kotlin query engine. The engine validates table/column references, blocked schemas, join eligibility, filter depth, literal types, and row limits before compiling dialect-specific SQL with bound parameters. The default row limit is 100, the builder offers fixed choices up to an interactive max of 5,000, and limits above 1,000 add guidance about filters, selected columns, and indexed predicates instead of blocking reporting-oriented work.

EXPLAIN runs against the post-validation SQL and refines the query-risk calculation with plan evidence. If the plan is unavailable or does not provide a usable optimizer cost, the first run is blocked and the UI asks for explicit confirmation. Confirmed retries still run with the same row limit and timeout.

## Project Layout

```text
safe-db/
├── src/main/kotlin/com/safedb/        # Compose UI, app shell, exports, platform helpers
├── src/main/resources/                # fonts and app resources
├── src/test/kotlin/com/safedb/        # desktop unit tests
├── shared/
│   ├── src/main/kotlin/com/safedb/    # domain, query/JDBC, launch profiles, stores, secrets
│   ├── src/test/kotlin/com/safedb/    # shared unit tests
│   └── src/integrationTest/kotlin/    # live JDBC integration tests
├── buildSrc/                          # verification task implementations
├── docs/trust-stores.md               # managed external-trust-store guide
├── packaging/resources/               # distribution resources and managed-launch examples
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/                            # Gradle wrapper
├── scripts/seed_mysql.sh              # local MySQL fixture wrapper
├── testdata_mysql.sql                 # small static MySQL fixture
├── testdata_postgres.sql              # PostgreSQL integration fixture
└── AGENTS.md                          # agent-oriented commands and workspace notes
```
