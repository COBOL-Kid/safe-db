# safe-db — Agent Notes

## Project

`src/` is the desktop UI; `:shared` owns domain models, JDBC adapters, query/risk logic, persistence, credentials, and services.

## Implementation style

Prefer the simplest change that is correct and maintainable. Extend existing types, flows, and helpers before inventing parallel abstractions or new layers.

Stay inside the requested scope. Do not expand into nearby cleanups, refactors, or “while we’re here” features unless asked. A few focused files beats a new subsystem.

If a fix or feature seems to need a large redesign, many new types, or a lot of branching, stop and ask instead of shipping a sprawling solution. Asking for a preference, a smaller scope, or help is encouraged.

Comments: use sparingly, prefer inline `//`, and only where behavior is non-obvious or easy to break (quirks, order dependencies, safety edges). Do not narrate what the code already says.

Builder queries default to 500 rows, allow at most 10,000 rows, show guidance above 1,000 rows, and retain the 10-second timeout.

## Testing

Add or update tests for behavior that matters, especially regressions. Prefer a few focused, high-signal tests over many thin or overlapping ones. Extend an existing test when that covers the change; do not add suites, fixtures, or permutations that mostly restate the same path.

## Working conventions

Inspect `git status --short` first and preserve unrelated work. Never print or commit credentials, tokens, or user state. Keep changes focused and use the relevant render task for visual changes. For build, test, harness, and related how-to details, look in [docs/](docs/). If you alter a documented command, environment variable, packaging path, or safety guarantee, update the corresponding docs in the same change.
