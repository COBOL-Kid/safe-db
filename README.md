# safe-db

A desktop app for exploring production databases safely: browse the schema, build a typed query, and inspect a bounded sample without handing out a full SQL client.

Works with PostgreSQL, MySQL, SQL Server, and Oracle. The app stays read-only, caps how much you can pull, and scores query risk from the plan before you touch a live system.

macOS and Windows, built with Compose Desktop.

## What it does

- **Read-only by design.** Queries are validated against the loaded schema and compiled with bound parameters. No ad-hoc writes, no accidental `UPDATE`.
- **Limits you can live with.** Default sample is 500 rows, hard cap is 10,000, and queries time out after 10 seconds. Above 1,000 rows you get a nudge. "Download everything" is not a feature.
- **Plan-aware caution.** Missing plan evidence or an expensive optimizer cost requires an explicit confirmed retry rather than a shrug.
- **Credentials stay out of JSON.** Named connection profiles are saved without passwords, and the OS credential store holds secrets when it is available.
- **Exports are snapshots, not connections.** CSV, PNG, and the interactive HTML report all carry the sample you already pulled. A report you email is a file, not a back door into production.
- **Explore without mutating the query.** Schema maps, Pivot, Worksheet, and Visualization all work from an immutable sample. Saved recipes keep layout, not credentials or result rows.

If you want a general-purpose DBA workbench, or a place to paste a 400-line query from a Slack thread, this isn't that.

## Get started

You need **JDK 25** and a database you can reach over the network. Unsupported operating systems exit before any profile, credential store, or app data is touched.

```sh
./gradlew run
```

Use the Gradle wrapper rather than a system Gradle install.

| I want to... | Run |
| --- | --- |
| Start the app | `./gradlew run` |
| Run the usual checks | `./gradlew check` |
| Package an unsigned installer | `./gradlew packageDistributionForCurrentOS` |

More commands (integration tests, Docker fixtures, headless UI renders, seeders) live in [docs/testing.md](docs/testing.md). TLS launch profiles and managed trust stores are in [docs/trust-stores.md](docs/trust-stores.md).

## A quick tour

1. **Save a connection.** Give it a name and test it. Passwords go to the platform store on save; `Test Connection` does not write the keyring.
2. **Browse the schema.** Tables, keys, indexes, and referenced external tables, with system schemas kept out of the way. The map is read-only and does not rewrite the query you are building.
3. **Build a query.** Typed and parameterized, with joins, nested filters, and a row limit you control.
4. **Or type it.** The SQL screen takes a single `SELECT` in your database's dialect, with schema-aware autocomplete and a schema picker so `FROM` needs no prefix. Typed SQL is parsed into the same structured, parameter-bound query the builder produces (your text never reaches the database), so the same limits, risk scoring, and confirmations apply. Writes, subqueries, and aggregates are rejected with an explanation (aggregate in Explore instead).
5. **Look at the sample.** Pivot it, spreadsheet it, chart it. The view changes; the underlying rows stay put.
6. **Share what you found.** Every Explore mode exports CSV, charts also export PNG, and `Export HTML` writes a single self-contained report: the sampled rows travel with it, tables sort and filter, the chart renders inline, and clicking a cell or a mark drills into the same source rows the app would show. No database connection, no credentials, no live query waiting to be re-run by whoever opens it.

| Database | What to know |
| --- | --- |
| PostgreSQL | Verified TLS keeps pgjdbc's normal trust and client-certificate behavior unless you pick a launch profile. |
| MySQL | Uses read-only transactions for non-locking reads where the server supports it. |
| SQL Server | Certificate verification uses the JVM or a launch-profile trust store. |
| Oracle | Verified TCPS uses an Oracle wallet. |

App data lives in `~/Library/Application Support/com.safedb.app/` on macOS and `%APPDATA%\com.safedb.app\` on Windows. For local development or CI, `SAFEDB_KEYCHAIN_BACKEND=disabled` keeps credentials in memory.

## Contributing

This is open source, and guardrails are a team sport.

- **Fix a bug, polish copy, add a test, improve docs.** All of that counts.
- **Run `./gradlew check`** before you open a pull request. That is the fast gate: unit tests, test discovery, and coverage.
- **Need a real database?** [docs/testing.md](docs/testing.md) covers optional JDBC suites and the Docker stack. Integration tests skip when a fixture isn't around, so you can ship a unit-test change without standing up four engines.
- **CI is on demand.** A maintainer applies the `ci:run` label to run `check` plus the required static-MySQL suite. After new commits, remove and reapply the label. Cross-platform durability is a manual **Run workflow** in GitHub Actions; see [.github/workflows](.github/workflows/).

Issues and pull requests are welcome. If you are unsure where something belongs, open a small PR or an issue and we will sort it out.

If you change a documented command, environment variable, packaging path, or safety guarantee, update the matching docs in the same change.
