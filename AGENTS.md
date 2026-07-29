# safe-db — Agent Notes

## Project

safe-db is a root Gradle, Jetpack Compose Desktop application for safely exploring production databases. The desktop application is in `src/`; the `:shared` JVM module owns the domain model, query engine, JDBC adapters, persistence, credentials, and service layer. It supports PostgreSQL, MySQL, SQL Server, and Oracle.

## Commands

- `./gradlew help` — validate root Gradle configuration; run this first after changing root build logic or `buildSrc/`.
- `./gradlew run` — start the desktop app; it requires a graphical display.
- `./gradlew check` — fast verification gate: desktop and shared unit tests, JUnit-XML discovery verification, and Kover ratchets. No database is required.
- `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache` — fresh coverage proof after broad Kotlin/build changes. Run the cache-reuse check separately if it matters.
- `./gradlew integrationTest` — run the tagged `:shared` JDBC integration suite. It is environment-gated and may skip locally when no selected fixture is available.
- `./gradlew renderPreview --rerun-tasks` — headlessly render UI states to `/tmp/safedb-preview/`; use it for Compose changes when a display is unavailable.
- `./gradlew renderThemeGallery` — render the Connections/settings surfaces across all color schemes.
- `./gradlew seedMysql` — create the default generated MySQL fixture (50,000 orders).
- `./gradlew seedMysql -PseedMysqlArgs="--orders 20000"` — pass seeder arguments through Gradle.
- `scripts/seed_mysql.sh --static` — load the smaller checked-in MySQL fixture.
- `./gradlew packageDistributionForCurrentOS` — build the configured Compose distribution. The current target formats are Linux Deb, AppImage, and RPM; do not assume packaging is configured for macOS or Windows.

Use the project wrapper, not a system Gradle installation. Do not run `./gradlew run` concurrently with daemon-less builds that would contend for `build/`.

## Verification

- `check` currently enforces minimum unit discovery of 95 desktop and 246 shared test cases, with no JUnit XML failures or errors. Raise a floor when deliberately adding tests; do not lower it to hide a discovery regression.
- Kover line-coverage floors are 72% for desktop code and 66% for shared code. Stale incremental reports can be misleading, so use `--rerun-tasks --no-build-cache` for fresh coverage claims.
- The integration source set is `shared/src/integrationTest/kotlin/`. Set `SAFEDB_TEST_REQUIRE_MYSQL=true` and/or `SAFEDB_TEST_REQUIRE_POSTGRES=true` to make the corresponding engine mandatory; required suites must meet their discovery floor without skipped tests.
- MySQL settings use `SAFEDB_TEST_MYSQL_HOST`, `SAFEDB_TEST_MYSQL_PORT`, `SAFEDB_TEST_MYSQL_USER`, `SAFEDB_TEST_MYSQL_PASSWORD`, and `SAFEDB_TEST_MYSQL_DATABASE`; `SAFEDB_TEST_MYSQL_DOCKER` can select a Docker container. PostgreSQL uses the matching `SAFEDB_TEST_POSTGRES_*` names.
- The preview renderer currently writes 28 PNGs: 10 main-app images at 1280×832 and 18 Explore/recipe images at 1120×760, in light and dark modes.
- `.github/workflows/compose.yml` runs the unit/coverage gate plus required static-MySQL integration on selected application/build changes. `.github/workflows/durability.yml` runs each Monday at 09:00 UTC and on demand, covering three-platform unit checks, required PostgreSQL and generated-MySQL integration, previews, and Linux packaging.

## Project Structure

```text
safe-db/
├── src/main/kotlin/com/safedb/
│   ├── App.kt / Main.kt              # application wiring and entry point
│   ├── export/                       # CSV and chart PNG export
│   ├── platform/                     # application-data paths and legacy import
│   ├── tools/                        # headless preview/theme-gallery renderers
│   ├── ui/                           # Compose screens, components, and theme
│   └── viewmodel/                    # UI state and service orchestration
├── src/test/kotlin/com/safedb/        # desktop unit tests
├── shared/
│   ├── src/main/kotlin/com/safedb/
│   │   ├── adapter/                  # JDBC dialect adapters
│   │   ├── connection/               # connection parsing and presets
│   │   ├── explore/                  # Pivot, Worksheet, Visualization, Recipes
│   │   ├── model/                    # shared models and settings
│   │   ├── persist/                  # atomic file persistence
│   │   ├── query/                    # hydration, validation, compilation, execution
│   │   ├── secrets/                  # credential stores and session cache
│   │   ├── service/                  # SafeDbService implementation
│   │   ├── store/                    # connection, query, settings, recipe stores
│   │   └── tools/                    # MySQL fixture generator and CLI
│   ├── src/test/kotlin/com/safedb/    # shared unit tests
│   └── src/integrationTest/kotlin/    # live JDBC integration tests
├── buildSrc/                          # Gradle verification tasks
├── scripts/seed_mysql.sh              # root MySQL seeder wrapper
├── testdata_mysql.sql                 # static MySQL fixture
└── testdata_postgres.sql              # PostgreSQL fixture
```

## Product And Safety Constraints

- Connections are persisted through `SafeDbService`; profile JSON never contains passwords. Empty passwords are valid credentials, particularly for local MySQL, and must remain `""` through form, service, and query paths.
- The visual builder works from a typed Kotlin query IR. Preserve recursive filter connectors, schema-derived literal kinds, query hydration for old saved/history specs, and selected-column metadata for empty result sets where an adapter can infer it.
- Query execution is read-only and guarded by blocked schemas, a default 100-row limit, a 5,000-row maximum, guidance above 1,000 rows, a 10-second timeout, and explicit confirmation when EXPLAIN is unavailable or above the per-dialect cost threshold.
- Explore operates on the immutable query sample. Pivot, Worksheet, and Visualization configurations may be saved/exported as Recipes, but recipe files must never include credentials or sample rows.
- Use semantic Compose colors (`MaterialTheme.colorScheme` and `SafeDbTheme.colors`) rather than copying hex values into UI code. The selectable Control Blue, Signal Teal, Oxide, and Command Violet palettes live in `src/main/kotlin/com/safedb/ui/theme/`.
- Keep default connection flows simple. Advanced settings are for technical users and should use direct SSL labels such as “SSL with hostname verification” and “SSL encrypt only (no cert check)”. Avoid security-state indicators that can be transiently misleading.

## App Data And Credentials

- State is stored in `com.safedb.app`: `$XDG_DATA_HOME/com.safedb.app/` when set, otherwise `~/.local/share/com.safedb.app/` on Linux; `~/Library/Application Support/com.safedb.app/` on macOS; and `%APPDATA%\\com.safedb.app\\` on Windows.
- The persisted files are `connections.json`, `settings.json`, `saved_queries.json`, `query_history.json`, and `explore_recipes.json`.
- `SAFEDB_KEYCHAIN_BACKEND=auto` chooses the platform credential backend; on Linux it uses keyutils when available and otherwise falls back to in-memory credentials. `SAFEDB_KEYCHAIN_BACKEND=disabled` is in-memory only and appropriate for tests/CI, so passwords do not survive restart.
- **Test Connection** uses the form password without writing the keyring; **Save Connection** writes the selected credential backend. Query and schema operations then use the in-process `CredentialSession` cache after the first unlock.

## Working Conventions

- Inspect `git status --short` before editing and preserve unrelated work.
- Treat database credentials, tokens, and user state as sensitive; never print or commit them.
- Keep implementation changes focused, add a regression test for corrected behavior, and select verification proportional to the touched surface.
- Inspect JUnit XML in `build/test-results/` or `shared/build/test-results/` when test discovery or integration execution is in doubt; it is the decisive record of test cases, skips, failures, and errors.
