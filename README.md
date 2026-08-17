# safe-db

A desktop app for exploring production databases without handing a full SQL client to someone who once wrote `SELECT *` in a training sandbox and now 'knows' SQL.

safe-db is for them: users who still need to look at production Postgres, MySQL, SQL Server, or Oracle databases but cannot be trusted with a query.

Browse the schema. Build a typed query. Inspect a bounded sample. The app stays read-only, caps how much they can pull, and scores risk from the query plan before they lean on a live system.

macOS and Windows. Built with Compose Desktop. Unapologetically not Excel.

## Why it exists

Most SQL clients assume you meant to do that. Safe-db assumes you did not.

safe-db is opinionated on purpose — the opinions of people who have watched a cartesian product become a status meeting:

- **Read-only by design.** Queries are validated against the loaded schema and compiled with bound parameters. No ad-hoc writes. No “just this once” `UPDATE`. No “I thought it was a SELECT.”
- **Limits you can live with.** Default sample is 500 rows, hard cap is 10,000, and queries time out after 10 seconds. You’ll get a nudge above 1,000 rows. “Download everything” is not a feature.
- **Plan-aware caution.** Missing plan evidence or a spicy optimizer cost needs an explicit confirmed retry — not a shrug, not a “it was fine in staging.”
- **Credentials stay out of JSON.** Named connection profiles are saved without passwords. The OS credential store holds secrets when it’s available. Sticky notes are not a backend.
- **Explore without mutating the query.** Schema maps, Pivot, Worksheet, and Visualization work from an immutable sample. Saved recipes keep layout, not credentials or result rows. Pivot all you want; you still cannot accidentally relaunch the query as a warehouse-melting encore.

If you want a general-purpose DBA workbench, or a place to paste a 400-line query from a Slack thread titled “quick question,” this isn’t that.

## Get started

You need **JDK 25** and a database you can reach over the network. Unsupported operating systems exit before any profile, credential store, or app data is touched.

```sh
./gradlew run
```

That’s the whole “hello, world.” Use the Gradle wrapper, not a system Gradle install, and don’t run `./gradlew run` next to daemon-less builds.

| I want to… | Run |
| --- | --- |
| Start the app | `./gradlew run` |
| Run the usual checks | `./gradlew check` |
| Package an unsigned installer | `./gradlew packageDistributionForCurrentOS` |

More commands (integration tests, Docker fixtures, headless UI renders, seeders) live in [docs/testing.md](docs/testing.md). TLS launch profiles and managed trust stores are in [docs/trust-stores.md](docs/trust-stores.md).

## A quick tour

1. **Save a connection.** Give it a name. Test it. Passwords go to the platform store on save. `Test Connection` does not write the keyring.
2. **Browse the schema.** Tables, keys, indexes, and referenced external tables. System schemas stay out of the way. The map is read-only and does not rewrite the query you’re building.
3. **Build a query.** Typed, parameterized, with joins, nested filters, and a row limit you actually control.
4. **Or type it.** The SQL screen takes a single `SELECT` in your database's dialect, with schema-aware autocomplete and a schema picker so `FROM` needs no prefix. Typed SQL is parsed into the same structured, parameter-bound query the builder produces — your text never reaches the database — so the same limits, risk scoring, and confirmations apply. Writes, subqueries, and aggregates are rejected with an explanation (aggregate in Explore instead).
5. **Look at the sample.** Pivot it, spreadsheet it, chart it. Tinker with the view; the underlying rows stay put. If someone needs a million rows, they can submit a ticket.

| Database | What to know |
| --- | --- |
| PostgreSQL | Verified TLS keeps pgjdbc’s normal trust and client-certificate behavior unless you pick a launch profile. |
| MySQL | Uses read-only transactions for non-locking reads where the server supports it. |
| SQL Server | Certificate verification uses the JVM or a launch-profile trust store. |
| Oracle | Verified TCPS uses an Oracle wallet. |

App data lives in `~/Library/Application Support/com.safedb.app/` on macOS and `%APPDATA%\com.safedb.app\` on Windows. For local development or CI, `SAFEDB_KEYCHAIN_BACKEND=disabled` keeps credentials in memory.

## Contributing

This is open source. If you’ve ever been paged because someone “just needed a count,” you already understand the product.

- **Fix a bug, polish copy, add a test, improve docs.** All of that counts. Guardrails are a team sport.
- **Run `./gradlew check`** before you open a pull request. That’s the fast gate: unit tests, test discovery, and coverage.
- **Need a real database?** [docs/testing.md](docs/testing.md) covers optional JDBC suites and the Docker stack. Integration tests skip when a fixture isn’t around, so you can still ship a unit-test change without standing up four engines.
- **CI is on demand.** A maintainer applies the `ci:run` label to run `check` plus the required static-MySQL suite. After new commits, remove and reapply the label. Cross-platform durability is a manual **Run workflow** in GitHub Actions; see [.github/workflows](.github/workflows/).

Issues and pull requests are welcome. If you’re unsure where something belongs, open a small PR or an issue and we’ll sort it out.

If you change a documented command, environment variable, packaging path, or safety guarantee, update the matching docs in the same change. Future-you (and the next person who has to explain why `SELECT *` is not a plan) will be grateful.
