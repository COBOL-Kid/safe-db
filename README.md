# safe-db

A desktop app that gives non-technical users a safe window into a production database. It's built for browsing the schema, building queries without writing SQL, and pulling bounded samples or shareable reports from PostgreSQL, MySQL, SQL Server, or Oracle. Everything is read-only, result sizes are capped, and query risk is scored from the plan before anything touches a live system. Granting access doesn't mean granting a SQL client.

macOS and Windows, built with Compose Desktop.

## What it does

- **Read-only by design.** Queries are validated against the loaded schema and compiled with bound parameters. No ad-hoc writes, no accidental `UPDATE`.
- **Limits you can live with.** Default sample is 500 rows, hard cap is 10,000, and queries time out after 10 seconds. Above 1,000 rows you get a nudge, and an `IN (...)` predicate is capped at 1,000 values. "Download everything" is not a feature.
- **Plan-aware caution, tunable.** Missing plan evidence or an expensive optimizer cost requires an explicit confirmed retry rather than a shrug. The gate has four settings (Cautious, Standard, Flexible, or Disabled), so a team can dial risk scoring to the level of trust that fits.
- **Credentials stay out of JSON.** Named connection profiles are saved without passwords, and the OS credential store holds secrets when it is available. Custom JDBC driver properties are supported per connection, with reserved and credential-shaped property names rejected outright.
- **Exports are snapshots, not connections.** CSV, PNG, and the interactive HTML report all carry the sample you already pulled. A report you email is a file, not a back door into production.
- **Explore without mutating the query.** Pivot, Worksheet, and Visualization all work from an immutable sample, with formulas in Pivot/Worksheet and a real set of chart types and aggregate measures in Visualization. Saved recipes keep layout, not credentials or result rows, and can be exported to a file and reopened later, even on another machine.

## Get started

**JDK 25** && **Gradle**

*Run the app:* `./gradlew run` 
*Unit tests, discovery, coverage:* `./gradlew check` 
*Unsigned installer:* `./gradlew packageDistributionForCurrentOS` 
*Integration tests, Docker fixtures, headless UI renders, and seeders:* [docs/testing.md](docs/testing.md). 
*TLS launch profiles and managed trust stores:* [docs/trust-stores.md](docs/trust-stores.md).

## Reference

- **Connections.** Named profiles with per-connection JDBC driver properties, a default connection/schema, and per-connection last-schema memory. Passwords go to the platform store on save; `Test Connection` does not write the keyring.
- **Map.** Read-only schema graph: tables, keys, indexes, referenced external tables. System schemas and Settings-blocked schemas are hidden. Never rewrites the query being built.
- **Builder.** Typed, parameterized queries with joins, nested filters, and a user-set row limit.
- **SQL screen.** Single-`SELECT` input in the target dialect, with schema-aware autocomplete, a schema picker, and Cmd/Ctrl+Enter to run. Text is parsed into the same structured, parameter-bound query the builder produces, so identical limits, risk scoring, and confirmations apply; the raw text never reaches the database. Writes, subqueries, and aggregates are rejected with an explanation (aggregate in Explore instead).
- **Explore.** Pivot (with formulas), Worksheet, and Visualization (bar, line, scatter, histogram, KPI; grouped/stacked; sort and top-N; measures from count through stddev/variance), all over an immutable sample.
- **Exports.** CSV from every Explore mode, PNG from charts, and `Export HTML` for a self-contained interactive report: rows travel with the file, tables sort and filter, charts render inline, cells and marks drill into source rows. No connection, credentials, or live query inside.
- **Navigation.** Command palette for screens and actions; History for re-running or saving past queries; recipes export to a file and import elsewhere.

| Database | Need to know |
| --- | --- |
| PostgreSQL | Verified TLS keeps pgjdbc's normal trust and client-certificate behavior unless you pick a launch profile. |
| MySQL | Uses read-only transactions for non-locking reads where the server supports it. |
| SQL Server | Certificate verification uses the JVM or a launch-profile trust store. |
| Oracle | Verified TCPS uses an Oracle wallet. |

App data lives in `~/Library/Application Support/com.safedb.app/` on macOS and `%APPDATA%\com.safedb.app\` on Windows. For local development or CI, `SAFEDB_KEYCHAIN_BACKEND=disabled` keeps credentials in memory.

## Contributing

- **Fix a bug, polish copy, add a test, improve docs.** All of that counts.
- **Run `./gradlew check`** before you open a pull request. That is the fast gate: unit tests, test discovery, and coverage.
- **Need a real database?** [docs/testing.md](docs/testing.md) covers optional JDBC suites and the Docker stack. Integration tests skip when a fixture isn't around, so you can ship a unit-test change without standing up four engines.
- **CI is on demand.** A maintainer applies the `ci:run` label to run `check` plus the required static-MySQL suite. After new commits, remove and reapply the label. Cross-platform durability is a manual **Run workflow** in GitHub Actions; see [.github/workflows](.github/workflows/).
- **AI-assisted submissions are welcome.** We have deliberately not shipped `AGENTS.md` or `CLAUDE.md` instruction files. The absence of docs does not mean anything goes: keep changes scoped, match the existing style, and be respectful of reviewers' time.

Issues and pull requests are welcome. If you are unsure where something belongs, open a small PR or an issue and we will sort it out.

If you change a documented command, environment variable, packaging path, or safety guarantee, update the matching docs in the same change.
