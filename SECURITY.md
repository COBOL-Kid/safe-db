# Security

Report vulnerabilities through [GitHub private advisory](https://github.com/COBOL-Kid/safe-db/security/advisories/new). Do not open a public issue.

The query path compiles a `QuerySpec` to bound-parameter SQL and does not send typed SQL text to the database. Limits, the risk gate, and adapter session settings are documented in [docs/query-engine.md](docs/query-engine.md).
