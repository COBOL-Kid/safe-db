# safe-db — Dependency Migration Plan

Migrate Rust dependencies to current versions: **sqlx 0.8.6 → 0.9.0**, **keyring v3.6.3 → keyring-core v1.0.0**, and pin all direct dependencies for reproducibility.

## Context

A `cargo update --dry-run` showed the lockfile is already at the latest compatible versions for all 13 direct deps (toolchain is 1.96.0). Only two deps have newer major releases requiring `Cargo.toml` changes:

| Dep        | Current | Latest    | Migration cost |
|------------|---------|-----------|----------------|
| `sqlx`     | 0.8.6   | 0.9.0     | Medium — code changes required |
| `keyring`  | 3.6.3   | 4.1.2     | Low (via `keyring-core` v1) — maintainer-recommended path |

The other 11 direct deps (tauri, tauri-build, tauri-plugin-log, tiberius, oracle, tokio, tokio-util, serde, serde_json, uuid, anyhow) are already at latest. This plan pins them to exact versions for reproducibility and migrates the two with breaking changes.

## Part A — sqlx 0.8.6 → 0.9.0

### A1. `src-tauri/Cargo.toml`
- `sqlx = "0.8"` → `sqlx = { version = "0.9.0", default-features = false, features = ["runtime-tokio", "tls-rustls-ring", "postgres", "mysql"] }`
- `rust-version = "1.77.2"` → `"1.94.0"` (sqlx 0.9 MSRV; toolchain is 1.96 ✅)

### A2. `AssertSqlSafe` wrapping — 7 call sites

sqlx 0.9 replaced the `&str` parameter of `sqlx::query*()` with a new `SqlSafeStr` trait (changelog #3723), implemented for exactly two things:
- `&'static str` — string literals (no change needed)
- `AssertSqlSafe<T>` — an explicit wrapper for dynamic strings

It is **not** implemented for `&String` or non-`'static` `&str`. The safe-db codebase has **18 `sqlx::query(...)` call sites total**, all in `pg.rs` (9) and `mysql.rs` (9). **11 are `&'static str` literals — no change.** The **7 non-static sites** need wrapping:

| # | File:line    | Current argument                                             | Fix                                   |
|---|--------------|--------------------------------------------------------------|---------------------------------------|
| 1 | `pg.rs:129`  | `&format!("SET LOCAL statement_timeout = {}", timeout_ms)`   | wrap the `format!` result             |
| 2 | `pg.rs:133`  | `&compiled.sql`                                              | wrap `&compiled.sql`                  |
| 3 | `pg.rs:142`  | `&compiled.sql` (describe fallback)                          | wrap `&compiled.sql`                  |
| 4 | `pg.rs:203`  | `&explain_sql`                                               | wrap `&explain_sql`                   |
| 5 | `mysql.rs:116` | `&format!("SET SESSION MAX_EXECUTION_TIME = {}", timeout_ms)` | wrap the `format!` result           |
| 6 | `mysql.rs:127` | `&compiled.sql`                                            | wrap `&compiled.sql`                  |
| 7 | `mysql.rs:192` | `&explain_sql`                                             | wrap `&explain_sql`                   |

`CompiledQuery.sql` is `String` (`query/ir.rs:85`), so `&compiled.sql` is `&String` (non-`'static`). The intended pattern per the sqlx authors is: `sqlx::query(sqlx::AssertSqlSafe(<the dynamic string>))`.

No `query_as`, `query_scalar`, `query!` macro, or `sqlx::raw_sql` call sites exist anywhere in `src-tauri/src/`. `mssql.rs` uses `tiberius` and `oracle.rs` uses the `oracle` crate — neither is affected.

### A3. Verify connection-options escaping changes
- **`pg.rs:9`** (`PgConnectOptions::new()`) — sqlx 0.9 #3800 auto-escapes options passed via `.options()`. If the code only sets `.host/.port/.username/.password/.database` (no `.options()` calls), there's **nothing to change**.
- **`mysql.rs:9`** (`MySqlConnectOptions::new()`) — #3924 changes `charset`/`collation`/`SET NAMES` behavior. If the code doesn't call `.charset()`/`.collation()`/`.set_names()`, **nothing to change**.

Both will be confirmed during implementation by reading the connect-option builder chains.

### A4. Regenerate lockfile
`cargo check` in `src-tauri/` will produce a new `Cargo.lock` with sqlx 0.9.0 and its updated transitive deps (crypto crates, etc.).

---

## Part B — keyring v3.6.3 → keyring-core v1.0.0

The `keyring` v4 crate's maintainer explicitly warns that apps should migrate to `keyring-core` v1 rather than relying on the v4 `v1` convenience facade. This plan follows that recommendation.

### B1. `src-tauri/Cargo.toml` dependency changes

**Remove:**
```
keyring = { version = "3", features = ["apple-native", "windows-native", "linux-native", "crypto-rust"] }
```

**Add (normal dep):**
```
keyring-core = "1.0.0"
```

**Add (target-specific deps, mirroring how keyring v4 declares them):**
```toml
[target.'cfg(target_os = "macos")'.dependencies]
apple-native-keyring-store = { version = "1.0.0", features = ["keychain"] }

[target.'cfg(target_os = "windows")'.dependencies]
windows-native-keyring-store = "1.1.0"

[target.'cfg(target_os = "linux")'.dependencies]
linux-keyutils-keyring-store = "1.0.0"
```

**Key decision: Linux backend stays `linux-keyutils`** (same as v3's `linux-native` feature). This preserves the existing storage location/behavior — the keyring-core migration does **not** force the switch to Secret Service/zbus that the v4 `v1` facade would have.

### B2. `src-tauri/src/secrets.rs` — import path changes only

The `Entry` API is identical between `keyring` v3 and `keyring-core` v1 (`Entry::new`, `set_password`, `get_password`, `delete_credential`, `Error::NoEntry`). The only code change is the import paths:

| Current                | New                          |
|------------------------|------------------------------|
| `use keyring::Entry;`  | `use keyring_core::Entry;`   |
| `keyring::Error::NoEntry` | `keyring_core::Error::NoEntry` |

The `?` operator continues to work (`keyring_core::Error` implements `std::error::Error`, and `anyhow::Error: From<E>` applies). The three function bodies (`save_password`, `get_password`, `delete_password`) are otherwise unchanged.

### B3. `src-tauri/src/lib.rs` — initialize the default store at startup

This is the **one behavioral requirement** of keyring-core: `Entry::new()` returns `Error::NoDefaultStore` unless `set_default_store()` has been called first (v3 auto-selected the platform store; keyring-core requires explicit selection). Add to the `setup` closure in `run()`:

```rust
fn init_keyring() -> anyhow::Result<()> {
    #[cfg(target_os = "macos")]
    let store = apple_native_keyring_store::keychain::Store::new()
        .map_err(|e| anyhow::anyhow!("failed to init macOS keychain store: {e}"))?;
    #[cfg(target_os = "windows")]
    let store = windows_native_keyring_store::Store::new()
        .map_err(|e| anyhow::anyhow!("failed to init Windows credential store: {e}"))?;
    #[cfg(target_os = "linux")]
    let store = linux_keyutils_keyring_store::Store::new()
        .map_err(|e| anyhow::anyhow!("failed to init linux-keyutils store: {e}"))?;

    #[cfg(any(target_os = "macos", target_os = "windows", target_os = "linux"))]
    keyring_core::set_default_store(store);

    Ok(())
}
```

Called as `init_keyring()?` inside the existing `setup(|app| { ... Ok(()) })` closure. `set_default_store` takes `Arc<CredentialStore>`; each `Store::new()` returns `Result<Arc<CredentialStore>>`.

`unset_default_store()` at shutdown is skipped — the OS reclaims resources on process exit and Tauri has no clean shutdown hook.

### B4. Module import for `secrets` — no change
`mod secrets;` in `lib.rs:7` stays as-is; `commands.rs` references "keyring" only in error message strings (`"Password not found in keyring"`), which are user-facing text and need no change.

---

## Part C — Pin all direct dependency versions

Final `[dependencies]` / `[build-dependencies]` block in `src-tauri/Cargo.toml` (caret pins to the currently-resolved exact versions, confirmed from `Cargo.lock`):

```toml
[build-dependencies]
tauri-build = { version = "2.6.3", features = [] }

[dependencies]
serde_json = "1.0.150"
serde = { version = "1.0.228", features = ["derive"] }
log = "0.4"
tauri = { version = "2.11.3", features = [] }
tauri-plugin-log = "2.8.0"
sqlx = { version = "0.9.0", default-features = false, features = ["runtime-tokio", "tls-rustls-ring", "postgres", "mysql"] }
uuid = { version = "1.23.3", features = ["v4"] }
anyhow = "1.0.102"
tokio = { version = "1.52.3", features = ["rt-multi-thread", "macros", "net"] }
tiberius = { version = "0.12.3", default-features = false, features = ["tds73", "tokio"] }
tokio-util = { version = "0.7.18", features = ["compat"] }
oracle = { version = "0.6.3", optional = true }
keyring-core = "1.0.0"

# target-specific (from Part B)
apple-native-keyring-store = { version = "1.0.0", features = ["keychain"] }  # macos
windows-native-keyring-store = "1.1.0"                                        # windows
linux-keyutils-keyring-store = "1.0.0"                                        # linux
```

`log = "0.4"` stays loose (it's a facade crate; pinning to a patch version adds noise without value). All others get exact pins.

---

## Execution order

1. **Cargo.toml edits** (Parts A1, B1, C together — one coherent manifest change).
2. **`cargo check`** in `src-tauri/` — expect ~7 `SqlSafeStr` trait-bound errors + possibly options-escaping warnings. Read the exact errors to confirm `AssertSqlSafe` wrapping form.
3. **Code edits** — `secrets.rs` imports (B2), `lib.rs` store init (B3), then the 7 `AssertSqlSafe` wraps in `pg.rs`/`mysql.rs` (A2). Read `pg.rs:1-20` and `mysql.rs:1-20` to confirm the connect-options escaping (A3).
4. **`cargo check`** until clean, then **`cargo build`**.
5. **`pnpm check`** (per AGENTS.md; no TS/Svelte changes expected, but verifies nothing broke).
6. **Manual smoke test** — `pnpm tauri dev` (macOS is the dev platform): create a PG connection, save credentials, verify save/get/delete round-trips through the macOS Keychain, run a query, confirm `EXPLAIN` guard warning still appears and rows return.

## Files touched
- `src-tauri/Cargo.toml` — deps + MSRV + pins
- `src-tauri/Cargo.lock` — regenerated
- `src-tauri/src/secrets.rs` — 2 import-path changes
- `src-tauri/src/lib.rs` — add `init_keyring()` + call in `setup`
- `src-tauri/src/adapters/pg.rs` — 4 `AssertSqlSafe` wraps
- `src-tauri/src/adapters/mysql.rs` — 3 `AssertSqlSafe` wraps

## Risks / open items
- **`AssertSqlSafe` exact generic form** — resolved by reading the first `cargo check` error. Low risk; it's a wrapper struct.
- **Connect-options escaping (A3)** — likely no-op (the code probably uses only `.host`/`.port`/`.username`/`.password`/`.database`), but must be confirmed by reading the builder chains.
- **No `cargo-outdated`/test suite** — there's no Rust test harness in this repo, so verification is `cargo check` + `cargo build` + manual app smoke test. No automated regression coverage exists.
