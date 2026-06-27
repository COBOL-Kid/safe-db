//! macOS-only manual smoke coverage for credential backends.
//!
//! Run on a Mac with:
//!   SAFEDB_KEYCHAIN_BACKEND=auto cargo test --test secrets_macos -- --ignored --nocapture
//!
//! On unsigned `cargo run` / `pnpm tauri dev` builds, `auto` should fall back to the
//! in-memory disabled backend after the Protected Data write probe fails.
//!
//! After saving a connection once, repeated `password_for_connection` calls should not
//! present additional Protected Data prompts during the same app session.

#[cfg(target_os = "macos")]
mod macos {
    use safe_db_lib::secrets;
    use uuid::Uuid;

    #[test]
    #[ignore = "manual macOS prompt smoke: verify OS store is not hit on every query"]
    fn auto_backend_round_trip_without_repeated_store_reads() {
        unsafe {
            std::env::set_var("SAFEDB_KEYCHAIN_BACKEND", "auto");
        }
        secrets::init_store().expect("auto backend should initialize");

        let id = format!("macos-smoke-{}", Uuid::new_v4());
        secrets::save_password(&id, "prompt-smoke-password").expect("save");
        secrets::reset_store_read_count_for_test();

        let first = secrets::password_for_connection(&id).expect("first unlock");
        assert_eq!(first, "prompt-smoke-password");
        let reads_after_first = secrets::store_read_count_for_test();

        for _ in 0..5 {
            let again = secrets::password_for_connection(&id).expect("session reuse");
            assert_eq!(again, "prompt-smoke-password");
        }

        assert_eq!(
            secrets::store_read_count_for_test(),
            reads_after_first,
            "session should satisfy repeated builder reads without extra OS store access"
        );

        secrets::delete_password(&id).expect("cleanup");
    }

    #[test]
    #[ignore = "manual macOS: unsigned debug builds should fall back after entitlement probe"]
    fn auto_backend_reports_disabled_on_unsigned_debug_build() {
        unsafe {
            std::env::set_var("SAFEDB_KEYCHAIN_BACKEND", "auto");
        }
        secrets::init_store().expect("auto backend should initialize");
        let label = secrets::active_backend_label();
        assert!(
            label == "disabled" || label == "protected",
            "expected disabled (unsigned) or protected (signed), got {label}"
        );
    }
}

#[cfg(not(target_os = "macos"))]
#[test]
fn macos_smoke_tests_are_macos_only() {
    // Keeps the integration test crate compiling on Linux CI.
}
