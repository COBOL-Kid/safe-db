use std::sync::Mutex;

use safe_db_lib::secrets::{
    RequestedBackend, active_backend_label, format_save_credential_error, init_store,
    is_missing_entitlement_error, parse_requested_backend_from,
};

static ENV_LOCK: Mutex<()> = Mutex::new(());

#[test]
fn save_credential_error_mentions_entitlements() {
    let err =
        format_save_credential_error("Platform failure: A required entitlement isn't present.");
    assert!(err.contains("entitlements"));
    assert!(err.contains("SAFEDB_KEYCHAIN_BACKEND"));
}

#[test]
fn save_credential_error_passes_through_other_failures() {
    let err = format_save_credential_error("disk full");
    assert!(err.contains("disk full"));
}

#[test]
fn missing_entitlement_error_detection_is_case_insensitive() {
    assert!(is_missing_entitlement_error(
        &"Platform Failure: A Required Entitlement isn't present."
    ));
    assert!(!is_missing_entitlement_error(&"connection refused"));
}

#[test]
fn parse_requested_backend_none_is_auto() {
    assert!(matches!(
        parse_requested_backend_from(None),
        RequestedBackend::Auto
    ));
}

#[test]
fn parse_requested_backend_disabled() {
    assert!(matches!(
        parse_requested_backend_from(Some("disabled")),
        RequestedBackend::Disabled
    ));
    assert!(matches!(
        parse_requested_backend_from(Some("DISABLED")),
        RequestedBackend::Disabled
    ));
}

#[test]
fn parse_requested_backend_empty_string_is_auto() {
    assert!(matches!(
        parse_requested_backend_from(Some("")),
        RequestedBackend::Auto
    ));
}

#[test]
fn parse_requested_backend_auto_is_auto() {
    assert!(matches!(
        parse_requested_backend_from(Some("auto")),
        RequestedBackend::Auto
    ));
}

#[test]
fn parse_requested_backend_legacy_aliases_fall_back_to_auto() {
    for raw in ["keychain", "legacy", "KEYCHAIN", "Legacy"] {
        assert!(
            matches!(
                parse_requested_backend_from(Some(raw)),
                RequestedBackend::Auto
            ),
            "expected Auto for {raw}"
        );
    }
}

#[test]
fn parse_requested_backend_unknown_value_falls_back_to_auto() {
    for raw in ["nope", "autoextra", "  "] {
        assert!(
            matches!(
                parse_requested_backend_from(Some(raw)),
                RequestedBackend::Auto
            ),
            "expected Auto for {raw}"
        );
    }
}

#[test]
#[cfg(target_os = "macos")]
fn parse_requested_backend_protected_on_macos() {
    assert!(matches!(
        parse_requested_backend_from(Some("protected")),
        RequestedBackend::Protected
    ));
}

#[test]
#[cfg(not(target_os = "macos"))]
fn parse_requested_backend_protected_off_macos_falls_back_to_auto() {
    assert!(matches!(
        parse_requested_backend_from(Some("protected")),
        RequestedBackend::Auto
    ));
}

#[test]
fn active_backend_label_reports_disabled_backend() {
    let _guard = ENV_LOCK.lock().unwrap();
    unsafe {
        std::env::set_var("SAFEDB_KEYCHAIN_BACKEND", "disabled");
    }
    init_store().expect("disabled backend should initialize");
    assert_eq!(active_backend_label(), "disabled");
}
