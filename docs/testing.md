# Commands and verification

`./gradlew check` is enough for most pull requests: desktop, `:shared`, and `:mcp` unit tests, test discovery, Docker harness orchestration, and Kover ratchets.

Use the wrapper, never system Gradle. Do not run `run` alongside daemon-less builds. `check` requires at least 309 desktop, 541 shared, and 87 MCP tests, with 90% desktop, 85% shared, and 91% MCP line-coverage floors. Inspect JUnit XML if discovery is uncertain.

| Command | Use |
| --- | --- |
| `./gradlew help` | Validate root Gradle configuration after build logic changes. |
| `./gradlew run` | Start the graphical desktop app on macOS or Windows. |
| `./gradlew check` | Fast gate: desktop, shared, and mcp unit tests, discovery, Docker harness orchestration, and Kover ratchets. |
| `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache` | Fresh coverage proof for broad Kotlin/build changes. |
| `./gradlew :mcp:koverXmlReportUnit verifyMcpCoverageRatchet` | Generate the unit-only MCP XML report and enforce its independent coverage floor. |
| `./gradlew integrationTest` | Run the optional `:shared` JDBC and `:mcp` integration suites, including the packaged MCP stdio smoke. |
| `./gradlew :mcp:integrationTest` | Run the packaged MCP stdio smoke and the environment-gated MCP MySQL case. |
| `./gradlew renderPreview --rerun-tasks` | Render 44 UI PNGs to `/tmp/safedb-preview/` after Compose changes. |
| `./gradlew renderHeroFrames` | Render the query-builder build-up sequence to `/tmp/safedb-preview/hero/`. |
| `./gradlew renderThemeGallery` | Render Connections/settings across palettes. |
| `./gradlew renderReportExport` | Export a genuine Explore HTML report to `/tmp/safedb-preview/report`. |
| `./gradlew seedMysql` | Generate the default 50,000-order MySQL fixture. |
| `./gradlew seedPostgres` | Generate the default fixture in the Docker PostgreSQL endpoint. |
| `./gradlew seedMssql` | Generate the default fixture in the Docker SQL Server endpoint. |
| `./gradlew seedOracle` | Generate the default fixture in the Docker Oracle endpoint. |
| `scripts/seed_mysql.sh --static` | Load the smaller checked-in MySQL fixture. |
| `scripts/verify_ssl_compat.sh` | Environment-gated SSL/TLS launch-profile and dialect-compatibility suite. |
| `scripts/docker_test_databases.sh up` | Start and seed all four engines and the plain/TLS test endpoints from the root Compose stack. |
| `./gradlew :mcp:run` | Stdio MCP server. Speaks JSON-RPC on stdin/stdout; use an MCP client or Inspector. Behavior and tools: [mcp.md](mcp.md). |
| `./gradlew :mcp:run --args='connections list'` | List saved MCP connections (human CLI; not JSON-RPC). |
| `./gradlew :mcp:run --args='setup --dialect postgres --database app --username readonly --password-file /absolute/path'` | Non-interactive connection add. Gradle typically has a null `System.console()`, so this is not a password prompt. |
| `./gradlew :mcp:shadowJar` | Fat JAR at `mcp/build/libs/safe-db-mcp-*-all.jar`. |
| `./gradlew :mcp:assembleNpm` | jlink the current OS into `mcp/build/npm/@safe-db/mcp` plus one platform package. Downloads a pinned Temurin 25 jlink JDK only on Linux x64; JMODs for the current platform go into `mcp/build/npm-cache/`. |
| `./gradlew :mcp:assembleNpmAllPlatforms` | jlink every platform into `mcp/build/npm-all/@safe-db/mcp` plus platform packages. Linux x64 only (cross-links with Temurin JMODs). |
| `./gradlew :mcp:npmCliTest` | `node --test` for the npm CLI shim. Part of `check` when `node` is on `PATH`; skips otherwise. |
| `./gradlew :mcp:npmPackagedTest` | Packaged MCP stdio/MySQL smoke against the current-OS jlink image. Not part of `check`. |
| `./gradlew packageDistributionForCurrentOS` | Native unsigned DMG (macOS) or MSI (Windows). |
| `./scripts/assert-conveyor-site.sh ./output` | Check Conveyor site version, update URL, and MSIX signature presence. |

## Integration tests

The root `integrationTest` task runs both the shared JDBC suite and the MCP integration suite. The MCP suite always exercises the packaged shadow JAR over stdio; its MySQL tool test skips when MySQL is unavailable. Required MySQL verification checks the shared and MCP JUnit reports separately, so shared MySQL cases cannot hide a missing or skipped MCP case. PostgreSQL-only runs require shared PostgreSQL coverage but do not require the MCP MySQL case.

Integration tests use `SAFEDB_TEST_REQUIRE_MYSQL=true`, `SAFEDB_TEST_REQUIRE_POSTGRES=true`, `SAFEDB_TEST_REQUIRE_MSSQL=true`, and `SAFEDB_TEST_REQUIRE_ORACLE=true` to require configured engines. Each engine has matching `SAFEDB_TEST_{MYSQL,POSTGRES,MSSQL,ORACLE}_{HOST,PORT,USER,PASSWORD,DATABASE}` variables; MySQL also accepts optional `SAFEDB_TEST_MYSQL_DOCKER`. Reproduce the required MySQL job with:

```sh
scripts/seed_mysql.sh --static
SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_MYSQL=true ./gradlew integrationTest --stacktrace
```

`scripts/verify_ssl_compat.sh` expects pre-provisioned TLS endpoints and profiles under `/tmp/safedb-ssl` unless `SAFEDB_SSL_ROOT` is set; it calls `:shared:integrationTest` directly and, by default, writes its report beneath that fixture root. It is not a substitute for the normal optional JDBC suite.

For a self-contained local harness, `scripts/docker_test_databases.sh up` generates disposable trusted/wrong CAs, server certificates, PKCS12 launch profiles, and the Oracle wallet-path fixture under `.docker/safedb-ssl`, then starts ordinary MySQL plus the four dialect endpoints used by the SSL suite. Checked-in fixtures seed every dialect; `scripts/docker_test_databases.sh seed` reloads the SQL Server and Oracle sample schemas without recreating the stack. Database data directories use anonymous volumes that the helper renews on `up` and removes on `down`; generated TLS inputs remain on the host. `scripts/docker_test_databases.sh verify` requires the standard MySQL, PostgreSQL, SQL Server, and Oracle adapter contracts before running the four-dialect SSL compatibility checks, all with fresh, uncached integration-test execution.

PostgreSQL and SQL Server fixture seeders resolve passwords from the explicit `SAFEDB_TEST_*_PASSWORD`, then the matching `SAFEDB_DOCKER_*_PASSWORD`, then the development default. `scripts/docker_test_databases.sh certs` is an offline operation and refuses to replace certificates while project services are running; use `reset` to rotate certificates and recreate an active stack safely.

## CI

The repository owner applies the `ci:run` label to a pull request to run `check` and required static-MySQL integration. Remove and reapply the label after new commits to rerun. Workflow-only pull requests run workflow lint when labeled.

Cross-platform durability is a manual **Run workflow** in GitHub Actions (`.github/workflows/durability.yml`). That suite waits for the `durability` environment, then adds full four-engine JDBC/TLS compatibility to the cross-platform, generated-MySQL, PostgreSQL, UI, and packaging jobs. Dependency submission remains automatic for qualifying trusted `main` changes.

MCP npm packaging is a separate manual **Run workflow** (`.github/workflows/npm.yml`). It jlink-cross-compiles Temurin 25 runtimes for every platform, packs `@safe-db/mcp` from `mcp/build/npm-all`, smokes `--help` on macOS arm64, Windows, and Linux ARM, and publishes only from `main` after smoke when the `publish` input is true. Publish uses `environment: npm` plus `NPM_TOKEN` provenance. Required reviewers for Environment `npm` are configured in the GitHub UI (workflow reference auto-creates an unprotected environment until you add rules). Do not commit the token.

Windows Conveyor packaging is a separate manual **Run workflow** (`.github/workflows/conveyor.yml`). It runs `check` plus required static-MySQL `integrationTest`, builds a signed Windows site on Linux with Azure Trusted Signing, checks Authenticode on Windows, uploads `output/` as a 14-day artifact, and publishes to `gs://safedb-download` only from `main` when the `publish` input is true. See [packaging.md](packaging.md). Environment `conveyor` plus Azure OIDC, the existing Conveyor root key, and GCP WIF are configured in the GitHub UI. Required reviewers for Environment `conveyor` are configured in the GitHub UI (workflow reference auto-creates an unprotected environment until you add rules). Do not commit signing keys or cloud tokens.
