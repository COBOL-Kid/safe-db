# Commands and verification

`scripts/verify.sh` is the tiered local verification entry point (`fast`, `live`, `packaged`, `gui`, `all`; default `fast`). `./gradlew check` is the fast PR gate: desktop, `:shared`, and `:mcp` unit tests, `compileRenderKotlin`, `compileIntegrationTestKotlin` (root, `:shared`, and `:mcp`), `ktfmtCheck`, `verifyUnitTestDiscovery`, `testDockerDatabaseHarness` (non-Windows), `testAssertConveyorSite` (non-Windows), `testVerifyScript` (non-Windows), `testAgentGuiReset` (Linux), `:mcp:npmCliTest` (when `node` is on `PATH`), and Kover ratchets.

Use the wrapper, never system Gradle. Do not run `run` alongside daemon-less builds. `check` runs `verifyUnitTestDiscovery` (requiring every compiled desktop, `:shared`, and `:mcp` test class in JUnit XML with zero failures or skips) and Kover ratchets with 95% desktop, 92% `:shared`, and 94% `:mcp` line-coverage floors. Inspect JUnit XML if discovery is uncertain. Root Kover excludes `desktopIntegrationTest` and `integrationTest`, Shared Kover excludes `testFixtures`, `integrationTest`, platform keyring delegates, and live-JDBC adapter objects (while tracking pure `*AdapterKt` helpers), and MCP Kover likewise ignores `integrationTest`, `npmPackagedTest`, and CLI entrypoint/stdio plumbing so all three ratchets reflect unit-testable code. The root Kover allowlist includes pure desktop UI helpers (`com.safedb.ui.util.*`, `com.safedb.ui.chart.*`, `ConnectionFormState*`, `ExploreUiModel*`, `ExploreTemplates*`, `TransportSecurityOptions*`, `WorksheetColumnInteractions*`), `com.safedb.launch.*`, and `com.safedb.canvas.*`, and attributes split packages (`com.safedb.export` and `com.safedb.schema`) by source file. Unknown split-package sourcefiles fail the ratchet.

## Tiered verification runner

`scripts/verify.sh [fast|live|packaged|gui|all] [--up] [--skip-gui] [--qodana]` runs the local verification tiers in fail-fast order, stops on the first failing stage while preserving its exit code, and prints a `Stage` / `Status` (`PASS`, `FAIL`, `SKIP`) / `Duration` summary table (`exit 2` on invalid or conflicting options).

| Tier or option | Behavior |
| --- | --- |
| `scripts/verify.sh` / `scripts/verify.sh fast` | Default tier: runs `./gradlew check jar` (also builds the desktop JAR and `extractMacSkikoNative`). |
| `scripts/verify.sh live` | Verifies all six Docker test containers (`safedb-test-mysql-connectivity`, `safedb-test-mysql`, `safedb-test-postgres`, `safedb-test-mssql`, `safedb-test-oracle`, `safedb-test-db2`) are `running:healthy`, then runs `scripts/docker_test_databases.sh verify`. |
| `scripts/verify.sh packaged` | Runs `./gradlew jar` then `./gradlew :mcp:assembleNpm :mcp:npmCliTest :mcp:verifyNpmPackagedTest --rerun-tasks --no-build-cache --stacktrace`. Sets `SAFEDB_KEYCHAIN_BACKEND=disabled` and all five `SAFEDB_TEST_REQUIRE_*=true` flags when `--up` is passed or all six Docker containers are healthy; otherwise runs offline. |
| `scripts/verify.sh gui` | Runs `scripts/agent_gui.sh smoke`, passing `--require-db` when `--up` is set or `safedb-test-mysql-connectivity` is healthy. Cannot be combined with `--skip-gui` (`exit 2`). |
| `scripts/verify.sh all` | Runs `fast` -> `live` -> `packaged` -> `gui`. |
| `--up` | Starts and seeds the 5-engine Docker stack via `scripts/docker_test_databases.sh up` before running `live`, `packaged`, `gui`, or `all` (prints a warning and has no effect on `fast`). |
| `--skip-gui` | Skips `gui` in `all` and records `SKIP` (`0s`) in the summary table. |
| `--qodana` | Appends `./gradlew qodanaScan` as a final `qodana` stage after any selected tier. |

`scripts/test_verify.sh` (wired into `check` on non-Windows as `./gradlew testVerifyScript`) exercises `scripts/verify.sh` tier routing, flags, Docker health checks, and fail-fast exit behavior using a fake `PATH`.

| Command | Use |
| --- | --- |
| `./gradlew help` | Validate root Gradle configuration after build logic changes. |
| `./gradlew run` | Start the graphical desktop app on macOS or Windows. |
| `scripts/agent_gui.sh up` / `./gradlew runAgentGui` | `scripts/agent_gui.sh up` is the harness that starts Xvfb and launches `./gradlew --no-daemon runAgentGui`. `./gradlew runAgentGui` is the unpackaged entry point that harness launches; it does not start Xvfb and uses the ambient `DISPLAY`. See [agent-gui.md](agent-gui.md). |
| `./gradlew check` | Fast gate: desktop, `:shared`, and `:mcp` unit tests, `compileRenderKotlin`, `compileIntegrationTestKotlin`, `ktfmtCheck`, `verifyUnitTestDiscovery`, `testDockerDatabaseHarness`, `testAssertConveyorSite`, `testVerifyScript`, `testAgentGuiReset` (Linux), `:mcp:npmCliTest`, and `koverVerify` ratchets. |
| `./gradlew ktfmtCheck` | Kotlin format check (`ktfmtFormat` to rewrite). Included in `check`. |
| `./gradlew qodanaScan` | JVM Community Qodana pass (`qodana.yaml`). Local static analysis; not labeled CI. |
| `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache` | Fresh unit-coverage proof for broad Kotlin/build changes. Desktop, `:shared`, and `:mcp` Kover configs all ignore `integrationTest`. |
| `./gradlew :mcp:koverXmlReportUnit verifyMcpCoverageRatchet` | Generate the unit-only MCP XML report and enforce its independent coverage floor. |
| `./gradlew integrationTest` | Run `:shared:integrationTest`, `./gradlew desktopIntegrationTest`, `:mcp:integrationTest`, and `verifyIntegrationTestDiscovery`. |
| `./gradlew desktopIntegrationTest` | Run root `DesktopLiveUserJourneyIntegrationTest` (`@Tag("integration")`, headless Compose + `AppViewModel` + `SafeDbServiceImpl` + on-disk stores). |
| `./gradlew :mcp:integrationTest` | Run the packaged MCP stdio smoke, in-process MySQL/PostgreSQL suites, `McpPackagedDialectsIntegrationTest`, and `McpPackagedUserJourneyIntegrationTest`. |
| `./gradlew renderPreview --rerun-tasks` | Render 48 UI PNGs to `/tmp/safedb-preview/` after Compose changes. |
| `./gradlew renderHeroFrames` | Render the query-builder build-up sequence to `/tmp/safedb-preview/hero/`. |
| `./gradlew renderThemeGallery` | Render Connections/settings across palettes. |
| `./gradlew renderReportExport` | Export a genuine Explore HTML report to `/tmp/safedb-preview/report`. |
| `./gradlew seedMysql` | Generate the default 50,000-order MySQL fixture. |
| `./gradlew seedPostgres` | Generate the default fixture in the Docker PostgreSQL endpoint. |
| `./gradlew seedMssql` | Generate the default fixture in the Docker SQL Server endpoint. |
| `./gradlew seedOracle` | Generate the default fixture in the Docker Oracle endpoint. |
| `./gradlew seedDb2` | Generate the default fixture in the Docker Db2 endpoint. |
| `scripts/seed_mysql.sh --static` | Load or reload `testdata_mysql.sql` (shop + bulk tables) into the MySQL connectivity endpoint. |
| `scripts/seed_postgres.sh --static` | Load or reload `testdata_postgres.sql` into the Docker PostgreSQL endpoint. |
| `scripts/seed_mssql.sh --static` | Load or reload `testdata_mssql.sql`. |
| `scripts/seed_oracle.sh --static` | Load or reload `testdata_oracle.sql`. |
| `scripts/seed_db2.sh --static` | Load or reload `testdata_db2.sql`. |
| `scripts/verify_ssl_compat.sh` | Environment-gated SSL/TLS launch-profile and dialect-compatibility suite. |
| `scripts/docker_test_databases.sh up` | Start and seed all five engines and the plain/TLS test endpoints from the root Compose stack (Db2 requires privileged mode, linux/amd64, and minutes to become healthy; no TLS by default). The Db2 database is `SAFEDB` because a LUW name is at most 8 characters and cannot contain `_`. |
| `./gradlew :mcp:run` | Stdio MCP server. Speaks JSON-RPC on stdin/stdout; use an MCP client or Inspector. Behavior and tools: [mcp.md](mcp.md). |
| `./gradlew :mcp:run --args='connections list'` | List saved MCP connections (human CLI; not JSON-RPC). |
| `./gradlew :mcp:run --args='setup --dialect postgres --database app --username readonly --password-file /absolute/path'` | Non-interactive connection add. Gradle typically has a null `System.console()`, so this is not a password prompt. |
| `./gradlew :mcp:shadowJar` | Fat JAR at `mcp/build/libs/safe-db-mcp-1.1.4-all.jar`. |
| `./gradlew :mcp:assembleNpm` | jlink the current OS into `mcp/build/npm/@safe-db/mcp` plus one platform package, stamped 1.1.4 from Gradle (`mcp/npm/package.json` stays `0.0.0-dev` until assemble). Downloads a pinned Temurin 25 jlink JDK only on Linux x64; JMODs for the current platform go into `mcp/build/npm-cache/`. |
| `./gradlew :mcp:assembleNpmAllPlatforms` | jlink every platform into `mcp/build/npm-all/@safe-db/mcp` plus platform packages. Linux x64 only (cross-links with Temurin JMODs). |
| `./gradlew :mcp:npmCliTest` | `node --test` for the npm CLI shim. Part of `check` when `node` is on `PATH`; skips otherwise. |
| `./gradlew :mcp:npmPackagedTest` | Runs MCP integration tests against the current-OS jlink bundle via `node cli.js` (`NODE_PATH`) when `node` is on `PATH`, falling back to bundled `java` (`bundledJava`) and the packaged shadow JAR when `node` is absent. Not part of `check`. |
| `./gradlew :mcp:verifyNpmPackagedTest` | Runs `:mcp:npmPackagedTest` and verifies `mcp/build/test-results/npmPackagedTest` executed stdio tests and all unskipped dialect/user-journey suites required by `SAFEDB_TEST_REQUIRE_*`. |
| `./scripts/assert-conveyor-site.sh ./output` | Check Conveyor site version, AppInstaller URLs, MSIX signature presence, the Apple Silicon zip, and the appcast URL. |

## Integration tests

The root `integrationTest` task runs `:shared:integrationTest`, `:desktopIntegrationTest`, and `:mcp:integrationTest` sequentially (`mustRunAfter` keeps the three suites from contending on Docker containers) and validates all three JUnit XML directories via `verifyIntegrationTestDiscovery`:

- **`:shared:integrationTest`** covers the five `*AdapterIntegrationTest` classes (including 1s timeout cancellation and live `EXPLAIN` large-scan classification on `scan_events`, 24-column `wide_rows` and NULL/Unicode `event_notes` decoding, and uncapped `payload_rows` reads hitting the 10 MiB `RESULT_TRUNCATED_WARNING` output cap), all five `SafeDbService{MySql,Postgres,Mssql,Oracle,Db2}IntegrationTest` suites, and `SreResilienceIntegrationTest` (DML/DDL and multi-statement rejection leaving `customers` untouched, unreachable-port and wrong-password fast-fail without password leakage, and 5x connect/query/close Hikari pool shutdown checks). Requiring an engine via `SAFEDB_TEST_REQUIRE_{MYSQL,POSTGRES,MSSQL,ORACLE,DB2}=true` enforces unskipped per-engine minimums (`MySql=13`, `Postgres=11`, `Mssql=11`, `Oracle=11`, `Db2=11`).
- **`:desktopIntegrationTest`** (`./gradlew desktopIntegrationTest`) runs `DesktopLiveUserJourneyIntegrationTest` (`integrationTest` source set, compiled by `check` via `compileIntegrationTestKotlin`, excluded from Kover unit floors). It drives real `AppViewModel`, `SafeDbServiceImpl`, and on-disk stores against live Docker databases—running the full journey on MySQL and PostgreSQL (including `Cautious` risk gate blocking `scan_events`, `ExploreViewModel` with headless `ImageComposeScene` rendering, HTML/CSV export, and restart persistence) and live connect, schema load, Builder, and SQL Editor queries on SQL Server, Oracle, and Db2. `verifyIntegrationTestDiscovery` requires `DesktopLiveUserJourneyIntegrationTest` for every `SAFEDB_TEST_REQUIRE_*=true` engine.
- **`:mcp:integrationTest`** runs the shadow JAR over stdio (`McpPackagedStdioIntegrationTest`), in-process `McpMySqlIntegrationTest` / `McpPostgresIntegrationTest`, `McpPackagedDialectsIntegrationTest`, and `McpPackagedUserJourneyIntegrationTest`. `verifyIntegrationTestDiscovery` checks both `McpPackagedDialectsIntegrationTest` and `McpPackagedUserJourneyIntegrationTest` for required engines.
- **`:mcp:npmPackagedTest` & `:mcp:verifyNpmPackagedTest`**: `:mcp:npmPackagedTest` launches the assembled `@safe-db/mcp` package via `node cli.js` (`NODE_PATH`) against the current-OS jlink runtime when `node` is on `PATH` (falling back to `bundledJava` and the packaged shadow JAR when `node` is absent). Each spawned process runs in an isolated environment (`XDG_DATA_HOME` on Linux, `APPDATA` on Windows, and `JAVA_TOOL_OPTIONS=-Duser.home=...` on macOS). `McpPackagedUserJourneyIntegrationTest` drives real CLI commands (`setup --password-file`, `connections list`, `connections test`, `settings risk-gate cautious`), two stdio MCP server restarts sharing one isolated directory (full tool chain on MySQL and PostgreSQL; core journey on SQL Server, Oracle, and Db2), and two-step `delete_connection` followed by `connections list` returning `No connections.`. `:mcp:verifyNpmPackagedTest` (`VerifyMcpNpmPackagedDiscovery`) requires `McpPackagedStdioIntegrationTest` (`>= 4` unskipped tests even offline) and zero skipped cases across `McpMySqlIntegrationTest` (`>= 2`), `McpPostgresIntegrationTest` (`>= 2`), `McpPackagedDialectsIntegrationTest` (`>= 1`), and `McpPackagedUserJourneyIntegrationTest` (`>= 1`) for every required engine.

Integration tests use `SAFEDB_TEST_REQUIRE_MYSQL=true`, `SAFEDB_TEST_REQUIRE_POSTGRES=true`, `SAFEDB_TEST_REQUIRE_MSSQL=true`, `SAFEDB_TEST_REQUIRE_ORACLE=true`, and `SAFEDB_TEST_REQUIRE_DB2=true` to require configured engines. Each engine has matching `SAFEDB_TEST_{MYSQL,POSTGRES,MSSQL,ORACLE,DB2}_{HOST,PORT,USER,PASSWORD,DATABASE}` variables; MySQL also accepts optional `SAFEDB_TEST_MYSQL_DOCKER`. The disposable `compose.yaml` stack uses fixed, checked-in credentials, and the test defaults match it, so `./gradlew integrationTest` needs no `SAFEDB_TEST_*` variables after `scripts/docker_test_databases.sh up`:

| Engine | Host port | Database | User | Password |
| --- | --- | --- | --- | --- |
| MySQL (`mysql-connectivity`) | 3306 | `safedb_test` | `safedb` (root: `safedb-root`) | `safedb` |
| PostgreSQL | 5433 | `safedb_test` | `postgres` | `postgres` |
| SQL Server | 14333 | `safedb_ssl` | `sa` | `SafeDb_Ssl_Passw0rd!` |
| Oracle | 1522 | `FREEPDB1` | `safedb` (admin: `SafeDb_Oracle_Passw0rd!`) | `safedb` |
| Db2 | 50000 | `SAFEDB` | `db2inst1` | `SafeDb_Db2_Passw0rd!` |

Reproduce the required MySQL CI job, which uses a passwordless `root` MySQL service, with:

```sh
SAFEDB_TEST_MYSQL_USER=root SAFEDB_TEST_MYSQL_PASSWORD= scripts/seed_mysql.sh --static
SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_MYSQL=true SAFEDB_TEST_MYSQL_USER=root SAFEDB_TEST_MYSQL_PASSWORD= ./gradlew integrationTest --stacktrace
```

`scripts/verify_ssl_compat.sh` expects pre-provisioned TLS endpoints and profiles under `/tmp/safedb-ssl` unless `SAFEDB_SSL_ROOT` is set; it calls `:shared:integrationTest` directly and, by default, writes its report beneath that fixture root. It is not a substitute for the normal optional JDBC suite.

For a self-contained local harness, `scripts/docker_test_databases.sh up` generates disposable trusted/wrong CAs, server certificates, PKCS12 launch profiles, and the Oracle wallet-path fixture under `.docker/safedb-ssl`, then starts ordinary MySQL plus the five dialect endpoints used by the SSL suite (Db2 stays outside the TLS suite; z/OS needs a manual `db2jcc_license_cisuz.jar` step and a real subsystem, not Docker). Checked-in fixtures seed every dialect; `scripts/docker_test_databases.sh seed` reloads the SQL Server, Oracle, and Db2 sample schemas without recreating the stack. Database data directories use anonymous volumes that the helper renews on `up` and removes on `down`; generated TLS inputs remain on the host. `scripts/docker_test_databases.sh verify` requires the standard MySQL, PostgreSQL, SQL Server, Oracle, and Db2 adapter contracts before the SSL compatibility checks, all with fresh, uncached integration-test execution. Live TLS handshakes cover MySQL, PostgreSQL, and SQL Server. Oracle coverage in that suite is TCP plus missing-wallet validation; it does not prove TCPS unless `SAFEDB_TEST_ORACLE_TCPS=true`.

Checked-in `testdata_*.sql` files keep the original small shop tables and add generated bulk data (recursive CTEs, `generate_series`, or digit cross-joins — not giant literal inserts):

| Object | Rows | Why |
| --- | --- | --- |
| Existing `customers` / `orders` (and MySQL's shop tables) | unchanged | JDBC contract tests |
| `scan_events` | 1,000,000 | Large size class, full-scan query timeout. A plan-confirmed large scan scores Elevated (4): allowed at Standard, blocked at Cautious. |
| `wide_rows` | 50,000 | Medium, 24 mixed-type columns, indexed and non-indexed |
| `payload_rows` | 20,000 | ~2 KiB text per row so a column select approaches the 10 MiB output cap |
| `event_notes` | 10,000 | FK without an index (InnoDB still auto-indexes MySQL FKs), NULLs, unicode. FK-without-index is ignored under a small LIMIT. |

Postgres, SQL Server, and Db2 put the bulk tables in extra schemas (`analytics` / `ops`, or `ANALYTICS` on Db2). MySQL uses a second database `safedb_lab` (granted to `safedb`). Oracle keeps them in the `SAFEDB` app schema.

Load on first boot: Compose `mysql-connectivity` (port 3306) and `postgres` (port 5433) run the SQL files. `scripts/docker_test_databases.sh up` also seeds SQL Server, Oracle, and Db2. Reload MySQL or Postgres without recreating the stack with `scripts/seed_mysql.sh --static` and `scripts/seed_postgres.sh --static`. Reload SQL Server, Oracle, and Db2 with `scripts/docker_test_databases.sh seed` (same as `scripts/seed_{mssql,oracle,db2}.sh --static`). `seed` does not reload MySQL or Postgres.

PostgreSQL and SQL Server fixture seeders use the explicit `SAFEDB_TEST_*_PASSWORD` when set, otherwise the `compose.yaml` password. `scripts/docker_test_databases.sh certs` is an offline operation and refuses to replace certificates while project services are running; use `reset` to rotate certificates and recreate an active stack safely.

## CI

Pushes to `main` run `./gradlew check` automatically without running Docker integration tests. On pull requests, only the repository owner can apply the `ci:run` label; that label starts both `check` and the required static-MySQL root `./gradlew integrationTest --stacktrace` job (covering `:shared:integrationTest`, `:desktopIntegrationTest`, and `:mcp:integrationTest`) only when the owner applies it (`.github/workflows/compose.yml` ignores the label from anyone else). Remove and reapply the label after new commits to rerun. Workflow-only pull requests run workflow lint when the owner applies that same label.

Dependency submission remains automatic for qualifying trusted `main` changes.

MCP npm packaging is a separate manual **Run workflow** (`.github/workflows/npm.yml`). Its `pack` job seeds static MySQL 8.4 and runs `./gradlew :mcp:assembleNpmAllPlatforms :mcp:npmCliTest :mcp:verifyNpmPackagedTest --stacktrace`, then packs `@safe-db/mcp` from `mcp/build/npm-all`. Its `smoke` matrix (`darwin-arm64`, `win32-x64`, `linux-arm64`) installs the packed tarballs, runs `npx --no-install safe-db-mcp --help`, and executes a stdio JSON-RPC `initialize` -> `notifications/initialized` -> `tools/list` handshake via `npx --no-install safe-db-mcp`. Publish runs only from `main` after `smoke` when the `publish` input is true, using OIDC trusted publishing with `environment: npm` (no long-lived npm token). Required reviewers for Environment `npm` are configured in the GitHub UI (workflow reference auto-creates an unprotected environment until you add rules).

Windows and macOS Conveyor packaging is a separate manual **Run workflow** (`.github/workflows/conveyor.yml`). It builds a signed site on Linux with Azure Trusted Signing and the Developer ID `.p12`, checks Authenticode on Windows and the Developer ID signature on the Apple Silicon app, uploads `output/` as a 14-day artifact, and publishes to `gs://safedb-download` only from `main` when the `publish` input is true. See [packaging.md](packaging.md). Environment `conveyor` plus Azure OIDC, the existing Conveyor root key, the Apple `.p12` secrets, and GCP WIF are configured in the GitHub UI. Required reviewers for Environment `conveyor` are configured in the GitHub UI (workflow reference auto-creates an unprotected environment until you add rules). Do not commit signing keys or cloud tokens.
