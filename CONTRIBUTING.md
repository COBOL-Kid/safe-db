# Contributing

Fix a bug, polish copy, add a test, or improve docs. All of that counts. Issues and pull requests are welcome; if you are unsure where something belongs, open a small PR or an issue.

Read [docs/query-engine.md](docs/query-engine.md) before changing the parser, validator, compiler, risk gate, or an adapter.

## Before you open a pull request

Run `./gradlew check`. That is the fast gate: unit tests, test discovery, Docker harness orchestration, and Kover coverage ratchets.

Need a real database? [docs/testing.md](docs/testing.md) covers optional JDBC suites and the Docker stack. Integration tests skip when a fixture isn't around, so you can ship a unit-test change without standing up four engines.

If you change a documented command, environment variable, packaging path, or engine contract, update the matching docs in the same change.

## CI

CI is on demand. A maintainer applies the `ci:run` label to run `check` plus the required static-MySQL suite. After new commits, remove and reapply the label. Cross-platform durability is a manual **Run workflow** in GitHub Actions; details are in [docs/testing.md](docs/testing.md) and [.github/workflows](.github/workflows/).

## AI-assisted submissions

AI-assisted submissions are welcome. This repo deliberately does not ship `AGENTS.md` or `CLAUDE.md`. The absence of those files does not mean anything goes: keep changes scoped, match the existing style, and be respectful of reviewers' time.
