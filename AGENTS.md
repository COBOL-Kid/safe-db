# safe-db — Agent Notes

## Project

safe-db is a root Gradle, Jetpack Compose Desktop application for safely exploring production databases. The desktop application is in `src/`; the `:shared` JVM module owns the domain model, query engine and risk scoring, JDBC adapters, persistence, credentials, and service layer. It supports PostgreSQL, MySQL, SQL Server, and Oracle.

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

- `check` currently enforces minimum unit discovery of 159 desktop and 331 shared test cases, with no JUnit XML failures or errors. Raise a floor when deliberately adding tests; do not lower it to hide a discovery regression.
- Kover line-coverage floors are 72% for desktop code and 66% for shared code. Stale incremental reports can be misleading, so use `--rerun-tasks --no-build-cache` for fresh coverage claims.
- The integration source set is `shared/src/integrationTest/kotlin/`. Set `SAFEDB_TEST_REQUIRE_MYSQL=true` and/or `SAFEDB_TEST_REQUIRE_POSTGRES=true` to make the corresponding engine mandatory; required suites must meet their discovery floor without skipped tests.
- MySQL settings use `SAFEDB_TEST_MYSQL_HOST`, `SAFEDB_TEST_MYSQL_PORT`, `SAFEDB_TEST_MYSQL_USER`, `SAFEDB_TEST_MYSQL_PASSWORD`, and `SAFEDB_TEST_MYSQL_DATABASE`; `SAFEDB_TEST_MYSQL_DOCKER` can select a Docker container. PostgreSQL uses the matching `SAFEDB_TEST_POSTGRES_*` names.
- The preview renderer currently writes 34 PNGs: 14 main-app images at 1280×832, 2 narrow connection-form images at 840×900, and 18 Explore/recipe images at 1120×760, in light and dark modes.
- `.github/workflows/compose.yml` runs the unit/coverage gate plus required static-MySQL integration on selected application/build changes. `.github/workflows/durability.yml` runs each Monday at 09:00 UTC and on demand, covering three-platform unit checks, required PostgreSQL and generated-MySQL integration, previews, and Linux packaging.

## Tech Stack

- Jetpack Compose Desktop / Compose Multiplatform `1.9.3`
- Kotlin `2.4.0`
- Gradle wrapper with `jvmToolchain(25)`
- JDBC via HikariCP and dialect adapters in `shared/`
- Credentials through Java keyring-backed platform stores where available; `disabled` is in-memory only

## Lint / Typecheck

Run `./gradlew check` after editing Kotlin/Compose files. There is no separate Kotlin linter configured; workflow YAML is validated by the Workflow lint action.

## Testing

- **Fast gate:** `./gradlew check` — JUnit Platform unit tests for `:shared` and the desktop app plus test-discovery and coverage ratchets; no database required
- **Integration gate (optional):** `./gradlew integrationTest` after seeding MySQL (`scripts/seed_mysql.sh --static`) or PostgreSQL (`testdata_postgres.sql`). Tests skip cleanly when the selected fixture is unreachable unless its `SAFEDB_TEST_REQUIRE_*` flag is true.
- **Shared module only:** `./gradlew :shared:test`
- **UI preview:** `./gradlew renderPreview`, then inspect `/tmp/safedb-preview/*.png`
- **Seeder CLI surface:** `./gradlew seedMysql -PseedMysqlArgs="--help"` and a bad-arg check such as `./gradlew seedMysql -PseedMysqlArgs="--orders nope"` when changing seeding behavior
- **MySQL env vars for integration/smoke:** `SAFEDB_TEST_MYSQL_HOST`, `SAFEDB_TEST_MYSQL_PORT`, `SAFEDB_TEST_MYSQL_USER`, `SAFEDB_TEST_MYSQL_PASSWORD`, `SAFEDB_TEST_MYSQL_DATABASE`, optional `SAFEDB_TEST_MYSQL_DOCKER`
- **Required integration execution:** `SAFEDB_TEST_REQUIRE_MYSQL=true` or `SAFEDB_TEST_REQUIRE_POSTGRES=true`; PostgreSQL connection variables use the parallel `SAFEDB_TEST_POSTGRES_*` names

To reproduce the pull-request MySQL job with the default local connection:

```sh
scripts/seed_mysql.sh --static
SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_MYSQL=true ./gradlew integrationTest --stacktrace
```

### CI Policy

- Compose jobs skip draft pull requests and support `merge_group`. The `check` job follows the workflow path filters.
- MySQL integration is relevant only for `shared/`, build logic, Gradle and wrapper files, scripts, and `testdata_mysql.sql`; it is skipped for `src/`-only, PostgreSQL-fixture-only, and Compose-workflow-only changes.
- Eligible same-repository pull requests run MySQL integration automatically. Forks require both GitHub approval for the external workflow and the exact maintainer-applied `ci:integration` label. The label controls MySQL eligibility only and must be created outside the workflow.
- Workflow-only changes run the separate Workflow lint workflow.
- Durability runs Mondays at 09:00 UTC and manually: fresh cross-platform `check`, required PostgreSQL and generated MySQL integration, preview validation, and Linux packaging.
- Dependency submission is not a pull-request check; for public repositories it runs on selected dependency-file updates to trusted `main` or by manual dispatch. Dependabot checks Gradle and GitHub Actions monthly, grouping minor and patch updates.

Smoke tests that need a real database should use the app or seeder directly. The MySQL seeder skips unrelated app state by default and streams generated SQL into the target database.

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
│   │   ├── query/                    # hydration, validation, compilation, execution, risk scoring
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
- Query execution is read-only and guarded by blocked schemas, a default 100-row limit, a 5,000-row maximum, guidance above 1,000 rows, a 10-second timeout, EXPLAIN-informed risk scoring, and explicit confirmation when plan evidence is unavailable or lacks a usable optimizer cost. Optimizer cost is recorded as evidence, not compared against a configurable threshold.
- Explore operates on the immutable query sample. Pivot, Worksheet, and Visualization configurations may be saved/exported as Recipes, but recipe files must never include credentials or sample rows.
- Use semantic Compose colors (`MaterialTheme.colorScheme` and `SafeDbTheme.colors`) rather than copying hex values into UI code. The selectable Control Blue, Signal Teal, Oxide, and Command Violet palettes live in `src/main/kotlin/com/safedb/ui/theme/`.
- Keep default connection flows simple. Advanced settings are for technical users and should use direct SSL labels such as “SSL with hostname verification” and “SSL encrypt only (no cert check)”. Avoid security-state indicators that can be transiently misleading.

### Query Risk Scoring

- The canonical scorer is `shared/src/main/kotlin/com/safedb/query/QueryRisk.kt`; the execution sequence is in `QueryCore.kt`. Score version 2 evaluates static schema/query evidence first, then replaces matching signals with normalized `EXPLAIN` plan evidence where it can be mapped unambiguously. An unavailable plan preserves the static assessment and adds an uncertainty; it must not silently lower risk.
- Score the four capped categories—Access (6), Joins (2), Operations (4), and Volume (2)—then calculate `dominant category + half of the remaining category total`. Severity bands are Minimal 0–2, Elevated 3–5, High 6–7, and Very high 8+. Preserve category caps, signal targets, confidence, and uncertainty records when changing rules; they make the resulting decision explainable.
- The persisted default gate is `Standard`: Cautious blocks Elevated and above, Standard blocks High and above, and Flexible blocks Very high. `Disabled` turns off descriptive scoring only; validation plus plan availability/usable-cost confirmation still apply. Mandatory high-confidence plan findings can block whenever a descriptive gate is enabled, regardless of the numeric band.
- A risk decision is query-fingerprint-scoped. A required plan confirmation is additionally scoped to the connection ID, credential fingerprint, query fingerprint, and exceptional plan condition; never reuse one after any of those inputs changes. Evaluate the risk gate before accepting a plan confirmation.
- History records score version, static/final scores, severity, signals, uncertainties, plan status/reason, gate result, optimizer cost, and confirmation outcome. Keep this audit metadata compatible when evolving the model; `risk_optimizer_cost_threshold` is legacy read-only metadata from older history entries.
- Treat scoring changes as safety-sensitive behavioral changes, not routine refactors. Before changing signal weights, category caps, severity bands, plan-replacement rules, or gate thresholds, identify affected corpus cases and execution paths; preserve conservative behavior for incomplete, ambiguous, or unavailable evidence. Do not weaken a block, discard an uncertainty, or change a confirmation scope without an explicit product/safety decision and regression coverage.
- For changes to scoring or gating, update focused `QueryRiskTest`/`QueryCoreTest` coverage and the versioned normalized corpus at `shared/src/test/resources/query-risk/v2/normalized-corpus.json`. Change `QUERY_RISK_SCORE_VERSION` deliberately when semantics invalidate comparisons with prior scores, and keep the UI/view-model tests aligned with the chosen gate behavior.

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
