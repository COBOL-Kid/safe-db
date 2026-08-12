# Commands and verification

| Command | Use |
| --- | --- |
| `./gradlew help` | Validate root Gradle configuration after build logic changes. |
| `./gradlew run` | Start the graphical desktop app on macOS or Windows. |
| `./gradlew check` | Fast gate: desktop/shared unit tests, discovery, and Kover ratchets. |
| `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache` | Fresh coverage proof for broad Kotlin/build changes. |
| `./gradlew integrationTest` | Environment-gated `:shared` JDBC suite. |
| `./gradlew renderPreview --rerun-tasks` | Render 36 UI PNGs to `/tmp/safedb-preview/` after Compose changes. |
| `./gradlew renderThemeGallery` | Render Connections/settings across palettes. |
| `./gradlew seedMysql` | Generate the default 50,000-order MySQL fixture. |
| `./gradlew seedPostgres` | Generate the default fixture in the Docker PostgreSQL endpoint. |
| `./gradlew seedMssql` | Generate the default fixture in the Docker SQL Server endpoint. |
| `./gradlew seedOracle` | Generate the default fixture in the Docker Oracle endpoint. |
| `scripts/seed_mysql.sh --static` | Load the smaller checked-in MySQL fixture. |
| `scripts/verify_ssl_compat.sh` | Environment-gated SSL/TLS launch-profile and dialect-compatibility suite. |
| `scripts/docker_test_databases.sh up` | Start and seed all four engines and the plain/TLS test endpoints from the root Compose stack. |
| `./gradlew packageDistributionForCurrentOS` | Native unsigned DMG (macOS) or MSI (Windows). |

Use the wrapper, never system Gradle. Do not run `run` alongside daemon-less builds. `check` requires at least 212 desktop and 351 shared tests, with 72% and 66% line-coverage floors respectively. Inspect JUnit XML if discovery is uncertain.

Integration tests use `SAFEDB_TEST_REQUIRE_MYSQL=true` and/or `SAFEDB_TEST_REQUIRE_POSTGRES=true` to require a configured engine. MySQL uses `SAFEDB_TEST_MYSQL_{HOST,PORT,USER,PASSWORD,DATABASE}` and optional `SAFEDB_TEST_MYSQL_DOCKER`; PostgreSQL has matching variables. Reproduce the required MySQL job with:

```sh
scripts/seed_mysql.sh --static
SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_MYSQL=true ./gradlew integrationTest --stacktrace
```

`scripts/verify_ssl_compat.sh` expects pre-provisioned TLS endpoints and profiles under `/tmp/safedb-ssl` unless `SAFEDB_SSL_ROOT` is set; it calls `:shared:integrationTest` directly and, by default, writes its report beneath that fixture root. It is not a substitute for the normal optional JDBC suite.

For a self-contained local harness, `scripts/docker_test_databases.sh up` generates disposable trusted/wrong CAs, server certificates, PKCS12 launch profiles, and the Oracle wallet-path fixture under `.docker/safedb-ssl`, then starts ordinary MySQL plus the four dialect endpoints used by the SSL suite. Checked-in fixtures seed every dialect; `scripts/docker_test_databases.sh seed` reloads the SQL Server and Oracle sample schemas without recreating the stack. Database data directories use anonymous volumes that the helper renews on `up` and removes on `down`; generated TLS inputs remain on the host. `scripts/docker_test_databases.sh verify` runs both the required PostgreSQL/MySQL contracts and SSL compatibility checks with fresh, uncached integration-test execution.

PostgreSQL and SQL Server fixture seeders resolve passwords from the explicit `SAFEDB_TEST_*_PASSWORD`, then the matching `SAFEDB_DOCKER_*_PASSWORD`, then the development default. `scripts/docker_test_databases.sh certs` is an offline operation and refuses to replace certificates while project services are running; use `reset` to rotate certificates and recreate an active stack safely.

CI is on demand: a maintainer applies the `ci:run` label to a pull request to run `check` and required static-MySQL integration; remove and reapply the label after new commits to rerun. Workflow-only pull requests run workflow lint when labeled. The cross-platform durability suite is on-demand through GitHub Actions, and dependency submission remains automatic for qualifying trusted `main` changes.
