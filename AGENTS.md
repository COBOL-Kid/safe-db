# safe-db — Agent Notes

## Project

safe-db is a Jetpack Compose Desktop app with a Kotlin/JDBC backend. The Gradle project lives at the repository root (`shared` module plus Compose UI).

See the git history for the removed desktop prototype if old implementation context is needed.

## Commands

- `./gradlew run` — boot the desktop app
- `./gradlew check` — unit tests for `:shared` (`test`) and the desktop app (`test`); no database required
- `./gradlew koverHtmlReport` — merged desktop/shared JVM coverage report; `check` enforces the checked-in coverage and discovery ratchets
- `./gradlew integrationTest` — env-gated JDBC integration tests in `:shared` (`integrationTest`, `@Tag("integration")`); supports seeded MySQL and PostgreSQL fixtures
- `./gradlew renderPreview` — headless render of the main screens (light + dark) to `/tmp/safedb-preview/*.png` via `ImageComposeScene` with a fake service; use for visual verification without a display
- `./gradlew seedMysql` — generated local MySQL fixture (~50k orders)
- `./gradlew seedMysql -PseedMysqlArgs="--orders 20000"` — pass seeder args through Gradle
- `./gradlew packageDistributionForCurrentOS` — native package (deb/AppImage/rpm on Linux)
- `scripts/seed_mysql.sh` — thin root wrapper around the Gradle-backed Compose/Kotlin MySQL seeder

## Features

- **Connections** — CRUD via `SafeDbService`; passwords in OS keyring when available, metadata in app data dir; form has show/hide password toggle
- **Schema introspection** — per-dialect JDBC adapters in `shared/`; system schemas blocked in query validation
- **Visual query builder** — Kotlin query IR compiled to dialect-specific SQL in `shared/src/main/kotlin/com/safedb/query/`; recursive filter groups with per-child AND/OR connector overrides
- **Explore modes** — session-only Pivot and Worksheet analysis of the current immutable query sample; Worksheet supports direct sort/group/filter controls, row/group formulas, summaries, and database-neutral window calculations; Visualization is currently a recipe-capable placeholder
- **Explore recipes** — local/importable/exportable bundles of one or more Explore mode configurations with an optional Builder `QuerySpec`; recipe files never contain sample rows or credentials
- **Safety** — read-only selects, row limit (default 100, fixed choices with an interactive max of 5,000, guidance above 1,000), 10 s query timeout, blocked schemas, filter literal type validation, and a cost-preview guard that requires confirmation when cost is unavailable or above threshold
- **Saved queries & history** — persisted through `QueryStore` in the app data dir; timestamps are Unix-seconds strings
- **Settings** — theme, `explain_cost_threshold`, and `blocked_schemas` through the Compose/Kotlin settings store

## Tech Stack

- Jetpack Compose Desktop / Compose Multiplatform `1.9.3`
- Kotlin `2.4.0`
- Gradle wrapper with `jvmToolchain(25)`
- JDBC via HikariCP and dialect adapters in `shared/`
- Credentials through Java keyring-backed platform stores where available; `disabled` is in-memory only

## Lint / Typecheck

Run `./gradlew check` after editing Kotlin/Compose files. There is no separate linter configured yet.

## Testing

- **Fast gate:** `./gradlew check` — kotlin-test unit tests on JUnit Platform; both `:shared` and the desktop app use `test`
- **Integration gate (optional):** `./gradlew integrationTest` after seeding MySQL (`scripts/seed_mysql.sh --static`) or PostgreSQL (`testdata_postgres.sql`). Tests skip cleanly when the selected fixture is unreachable unless its `SAFEDB_TEST_REQUIRE_*` flag is true.
- **Shared module only:** `./gradlew :shared:test`
- **UI preview:** `./gradlew renderPreview`, then inspect `/tmp/safedb-preview/*.png`
- **Seeder CLI surface:** `./gradlew seedMysql -PseedMysqlArgs="--help"` and a bad-arg check such as `./gradlew seedMysql -PseedMysqlArgs="--orders nope"` when changing seeding behavior
- **MySQL env vars for integration/smoke:** `SAFEDB_TEST_MYSQL_HOST`, `SAFEDB_TEST_MYSQL_PORT`, `SAFEDB_TEST_MYSQL_USER`, `SAFEDB_TEST_MYSQL_PASSWORD`, `SAFEDB_TEST_MYSQL_DATABASE`, optional `SAFEDB_TEST_MYSQL_DOCKER`
- **Required integration execution:** `SAFEDB_TEST_REQUIRE_MYSQL=true` or `SAFEDB_TEST_REQUIRE_POSTGRES=true`; PostgreSQL connection variables use the parallel `SAFEDB_TEST_POSTGRES_*` names

Smoke tests that need a real database should use the app or seeder directly. The MySQL seeder skips unrelated app state by default and streams generated SQL into the target database.

## Project Structure

```text
safe-db/
├── src/main/kotlin/com/safedb/
│   ├── App.kt / Main.kt              # app shell and service wiring
│   ├── platform/                     # data directory compatibility helpers
│   ├── secrets/                      # platform credential delegates
│   ├── ui/                           # Compose screens/components/theme
│   └── viewmodel/                    # UI state and service orchestration
├── src/main/resources/               # fonts and resources
├── src/test/kotlin/com/safedb/       # UI state and preview tests
├── shared/
│   ├── src/main/kotlin/com/safedb/
│   │   ├── adapters/                 # JDBC dialect adapters
│   │   ├── connection/               # connection presets and parser
│   │   ├── model/                    # shared app/query/store models
│   │   ├── query/                    # validation, hydration, compilation, execution
│   │   ├── secrets/                  # credential session/store selection
│   │   ├── service/                  # SafeDbService and implementation
│   │   ├── store/                    # config, query, settings stores
│   │   └── tools/                    # SeedMysql CLI
│   └── src/test/kotlin/com/safedb/
├── scripts/seed_mysql.sh
├── testdata_mysql.sql
└── testdata_postgres.sql
```

## Key Conventions

- Empty database passwords are valid connection credentials, especially for local MySQL; preserve `""` through form submission, credential storage, and builder/query paths.
- Query filter values must use literal kinds matching the schema-derived column category; hydration should normalize old saved/history specs before rerun.
- Empty result sets should still expose selected column metadata in results where the adapter can infer it from compiled SQL.
- Compose UI theme uses slate neutrals plus a single indigo accent (`SafeDbTheme.colors.actionPrimary` / `accentContainer`); all hex colors live in `src/main/kotlin/com/safedb/ui/theme/Color.kt` except scrollbar alphas in `Theme.kt`.
- Bundled Inter (UI) and JetBrains Mono (`DataMono` for data cells, identifiers, hosts) fonts live in `src/main/resources/fonts/`.
- Advanced connection settings target technical users; use direct, explicit labels with "SSL" terminology, e.g. "SSL with hostname verification" and "SSL encrypt only (no cert check)".

## App Data And Credentials

- App data: `~/.local/share/com.safedb.app/` on Linux, `~/Library/Application Support/com.safedb.app/` on macOS, `%APPDATA%\com.safedb.app\` on Windows.
- Store filenames: `connections.json`, `settings.json`, `saved_queries.json`, `query_history.json`, and `explore_recipes.json`.
- `SAFEDB_KEYCHAIN_BACKEND=auto` uses Java keyring-backed platform stores when available.
- `SAFEDB_KEYCHAIN_BACKEND=disabled` is in-memory only and is appropriate for tests/CI; passwords do not survive restart.
- Compose Linux uses keyutils when available and otherwise falls back to in-memory `disabled` credentials.
- **Test Connection** does not use the keyring; **Save Connection** does.
- Query and schema paths use the in-process credential session in `shared/src/main/kotlin/com/safedb/secrets/CredentialSession.kt` after the first unlock.

## Cursor Cloud Specific Notes

- **Toolchain:** Temurin **JDK 25** at `/opt/jdk-25` (`JAVA_HOME` is exported in `~/.bashrc`), Gradle **9.6.1** at `/opt/gradle`. Prefer the project wrapper `./gradlew`.
- **Running the app:** `./gradlew run` needs an X display; use `DISPLAY=:1`. On launch Skiko may log `Cannot create Linux GL context` and `Fallback to next API`; this is expected in the no-GPU VM and renders via software rasterization. The window title is `safe-db (Compose)`.
- **Gradle contention:** do not run a daemon-less build and `./gradlew run` at the same time from two shells if they would contend on `build/`; stop the running app first or use separate tasks.
- **Testing DB connectivity:** for MySQL, `scripts/seed_mysql.sh` streams a deterministic generated e-commerce schema. Use `scripts/seed_mysql.sh --static` to load the smaller bundled `testdata_mysql.sql` fixture. PostgreSQL is installed but not auto-started; start it with `sudo pg_ctlcluster 16 main start`.

## Learned User Preferences

- Trust DB admin/infra teams for transport security configuration; do not surface secure-transport acknowledgment or notification checkboxes to end users.
- Avoid security status indicators that can be temporarily misleading; prefer omitting such indicators over showing potentially wrong state.
- Keep default connection flows simple: auto-detect first, hide advanced/manual controls behind recovery or administrator guidance.
- Prefer warning-first guidance over hard blocking for reporting-oriented query limits.
