# Commands and verification

`./gradlew check` is enough for most pull requests: desktop and `:shared` unit tests, test discovery, Docker harness orchestration, and Kover ratchets. Integration tests skip when fixtures are absent.

Use the wrapper, never system Gradle. Do not run `run` alongside daemon-less builds. `check` requires at least 309 desktop and 526 shared tests, with 90% and 85% line-coverage floors respectively. Inspect JUnit XML if discovery is uncertain.

| Command | Use |
| --- | --- |
| `./gradlew help` | Validate root Gradle configuration after build logic changes. |
| `./gradlew run` | Start the graphical desktop app on macOS or Windows. |
| `./gradlew check` | Fast gate: desktop/shared unit tests, discovery, Docker harness orchestration, and Kover ratchets. |
| `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache` | Fresh coverage proof for broad Kotlin/build changes. |
| `./gradlew integrationTest` | Environment-gated `:shared` JDBC suite. |
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
| `./gradlew packageDistributionForCurrentOS` | Native unsigned DMG (macOS) or MSI (Windows). |

## Integration tests

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

Cross-platform durability is a manual **Run workflow** in GitHub Actions (`.github/workflows/durability.yml`). That suite adds full four-engine JDBC/TLS compatibility to the cross-platform, generated-MySQL, PostgreSQL, UI, and packaging jobs. Dependency submission remains automatic for qualifying trusted `main` changes.
