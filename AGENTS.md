# safe-db — Agent Notes

## Project

safe-db is a root Gradle Jetpack Compose Desktop app for safely exploring PostgreSQL, MySQL, SQL Server, and Oracle on macOS and Windows. Reject other operating systems before launch-profile, credential-store, or data-directory initialization. `src/` is the desktop UI; `:shared` owns domain models, JDBC adapters, query/risk logic, persistence, credentials, and services.

## Commands and verification

| Command | Use |
| --- | --- |
| `./gradlew help` | Validate root Gradle configuration after build logic changes. |
| `./gradlew run` | Start the graphical desktop app on macOS or Windows. |
| `./gradlew check` | Fast gate: desktop/shared unit tests, discovery, and Kover ratchets. |
| `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache` | Fresh coverage proof for broad Kotlin/build changes. |
| `./gradlew integrationTest` | Environment-gated `:shared` JDBC suite. |
| `./gradlew renderPreview --rerun-tasks` | Render 36 UI PNGs to `/tmp/safedb-preview/` after Compose changes. |
| `./gradlew joinClickProof` | Real-window AWT-event proof of dashed join-line clicks (env-configurable: `SAFEDB_PROOF_MODE`, `SAFEDB_PROOF_ZOOM`, `SAFEDB_PROOF_PAN`, `SAFEDB_PROOF_DRAG`, `SAFEDB_PROOF_TABLEDRAG`). |
| `./gradlew renderThemeGallery` | Render Connections/settings across palettes. |
| `./gradlew seedMysql` | Generate the default 50,000-order MySQL fixture. |
| `scripts/seed_mysql.sh --static` | Load the smaller checked-in MySQL fixture. |
| `scripts/verify_ssl_compat.sh` | Environment-gated SSL/TLS launch-profile and dialect compatibility suite. |
| `./gradlew packageDistributionForCurrentOS` | Native unsigned DMG (macOS) or MSI (Windows). |

Use the wrapper, never system Gradle. Do not run `run` alongside daemon-less builds. `check` requires at least 191 desktop and 341 shared tests, with 72% and 66% line-coverage floors respectively. Inspect JUnit XML if discovery is uncertain.

Integration tests use `SAFEDB_TEST_REQUIRE_MYSQL=true` and/or `SAFEDB_TEST_REQUIRE_POSTGRES=true` to require a configured engine. MySQL uses `SAFEDB_TEST_MYSQL_{HOST,PORT,USER,PASSWORD,DATABASE}` and optional `SAFEDB_TEST_MYSQL_DOCKER`; PostgreSQL has matching variables. Reproduce the required MySQL job with:

```sh
scripts/seed_mysql.sh --static
SAFEDB_KEYCHAIN_BACKEND=disabled SAFEDB_TEST_REQUIRE_MYSQL=true ./gradlew integrationTest --stacktrace
```

CI is on demand: a maintainer applies the `ci:run` label to a pull request to run `check` and required static-MySQL integration; remove and reapply the label after new commits to rerun. Workflow-only pull requests run workflow lint when labeled. The cross-platform durability suite is on-demand through GitHub Actions, and dependency submission remains automatic for qualifying trusted `main` changes.

## Structure

```text
src/main/kotlin/com/safedb/     App/Main, ui/, viewmodel/, schema/, export/, platform/, tools/
shared/src/main/kotlin/com/safedb/
  adapter/, connection/, explore/, launch/, model/, persist/, platform/, query/, secrets/, service/, store/, tools/
shared/src/integrationTest/     live JDBC tests
buildSrc/                       verification tasks
docs/trust-stores.md            managed trust-store guide
packaging/resources/            distribution and launch-profile examples
```

## Non-negotiable behavior

- Profiles never contain passwords; preserve empty passwords as `""`. `Test Connection` does not write credentials; `Save Connection` does. `disabled` credentials are memory-only.
- The Builder uses typed query IR. Preserve recursive connectors, schema-derived literals, legacy hydration, and selected-column metadata for empty results.
- The schema map is read-only. Keep table IDs schema-qualified, preserve external nodes and deterministic layout, scope session state by connection and schema, and never mutate the Builder query.
- Queries are read-only: blocked schemas, default 100 rows, 5,000 maximum, guidance above 1,000, 10-second timeout, and explicit confirmation for missing/unusable plan evidence. Explore uses immutable samples; recipes contain neither credentials nor rows.
- Use semantic Compose colors, not copied hex values. Keep default connection flows simple and label SSL modes directly.

### Transport and trust

`TransportSecurity` owns JDBC TLS settings; block TLS driver properties in generic properties and preserve legacy hydration/dialect normalization. Launch-profile JSON is the sole custom trust-store path for verified PostgreSQL, MySQL, and SQL Server. It runs before Compose/JDBC and strictly rejects bad or missing inputs without fallback; passwords must never reach JSON, arguments, environment, or logs. PostgreSQL gets only a generated trusted-roots PEM and retains its normal SSL factory/client-certificate behavior; Oracle stays wallet-based. Keep [docs/trust-stores.md](docs/trust-stores.md), packaged examples, `LaunchProfileTest`, and adapter TLS tests aligned.

### Risk scoring

`shared/src/main/kotlin/com/safedb/query/QueryRisk.kt` is canonical; `QueryCore.kt` executes it. Version 2 replaces unambiguous static signals with normalized plan evidence and preserves uncertainty when plans are unavailable. Keep category caps (Access 6, Joins 2, Operations 4, Volume 2), the dominant-category-plus-half-remaining formula, severity bands (Minimal 0–2, Elevated 3–5, High 6–7, Very high 8+), confidence, signals, and uncertainty records.

Standard is the default gate: Cautious blocks Elevated+, Standard High+, Flexible Very high; Disabled removes descriptive scoring only. High-confidence mandatory plan findings block whenever a descriptive gate is enabled. Decisions are query-fingerprint-scoped; plan confirmation also binds connection, credential fingerprint, and exceptional plan condition. Treat scoring changes as safety-sensitive: update `QueryRiskTest`, `QueryCoreTest`, and `shared/src/test/resources/query-risk/v2/normalized-corpus.json`; change the score version only when semantics require it.

### Compose gesture-handler gotcha (join-line click regression, Aug 2026)

Never hand a composable-local function reference (`::someLocalFun`) to `rememberUpdatedState` or long-lived pointer-input callbacks. The Compose compiler memoizes the reference with the scope captured at first composition, so it silently reads stale locals (the join-line click handler kept hit-testing routes from the tables' original positions after a drag, while the explicit-lambda hover handler stayed fresh). Use lambda literals, and have gesture handlers resolve their target once at pointer-down and act on that captured value at release — never re-hit-test on up. `ImageComposeScene` tests miss input-pipeline bugs and any bug requiring state changes after first composition; arrange state post-composition in tests (see `clickingSuggestedRelationshipCreatesAJoinAfterTableMovesPostComposition`) and use `./gradlew joinClickProof` for real-window verification.

## Working conventions

Inspect `git status --short` first and preserve unrelated work. Keep this file, the README, trust-store guide, and packaged examples aligned when their behavior changes. Never print or commit credentials, tokens, or user state. Keep changes focused, add regression coverage for fixes, and use the relevant render task for visual changes.
