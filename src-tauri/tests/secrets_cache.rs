use safe_db_lib::secrets;
use std::sync::{Mutex, MutexGuard};
use uuid::Uuid;

static ENV_LOCK: Mutex<()> = Mutex::new(());

fn unique_id() -> String {
    format!("cache-test-{}", Uuid::new_v4())
}

fn init_disabled_backend() -> MutexGuard<'static, ()> {
    let guard = ENV_LOCK.lock().unwrap();
    // SAFETY: env mutation in tests is serialized by ENV_LOCK. Edition 2024 makes set_var unsafe.
    unsafe {
        std::env::set_var("SAFEDB_KEYCHAIN_BACKEND", "disabled");
    }
    secrets::init_store().expect("disabled backend should initialize");
    secrets::reset_store_read_count_for_test();
    guard
}

#[test]
fn save_then_get_returns_session_value_without_rehitting_store() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "first-secret").expect("save");
    secrets::reset_store_read_count_for_test();

    let first = secrets::get_password(&id).expect("get").expect("present");
    assert_eq!(first, "first-secret");
    assert!(
        secrets::session_contains_for_test(&id),
        "session should hold saved credential"
    );
    assert_eq!(
        secrets::store_read_count_for_test(),
        0,
        "session hit should not read the backing store"
    );

    let second = secrets::get_password(&id).expect("get").expect("present");
    assert_eq!(second, "first-secret");
    assert_eq!(secrets::store_read_count_for_test(), 0);
}

#[test]
fn password_for_connection_reuses_session_without_store_reads() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "builder-secret").expect("save");
    secrets::reset_store_read_count_for_test();

    let first = secrets::password_for_connection(&id).expect("first builder read");
    assert_eq!(first, "builder-secret");
    assert_eq!(secrets::store_read_count_for_test(), 0);

    let second = secrets::password_for_connection(&id).expect("second builder read");
    assert_eq!(second, "builder-secret");
    assert_eq!(secrets::store_read_count_for_test(), 0);
}

#[test]
fn delete_invalidates_session() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "to-be-deleted").expect("save");
    let _ = secrets::get_password(&id).expect("get").expect("present");
    assert!(secrets::session_contains_for_test(&id));

    secrets::delete_password(&id).expect("delete");
    assert!(
        !secrets::session_contains_for_test(&id),
        "session should be cleared after delete"
    );
    assert!(
        secrets::get_password(&id)
            .expect("get after delete")
            .is_none(),
        "underlying store should also be empty"
    );
}

#[test]
fn save_overwrites_session_value() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "first").expect("save first");
    let _ = secrets::get_password(&id).expect("get").expect("present");

    secrets::save_password(&id, "second").expect("save second");

    let value = secrets::password_for_connection(&id).expect("get");
    assert_eq!(value, "second", "save should overwrite session value");
}

#[test]
fn delete_then_save_yields_new_value() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "v1").expect("save v1");
    secrets::delete_password(&id).expect("delete v1");
    secrets::save_password(&id, "v2").expect("save v2");

    let value = secrets::password_for_connection(&id).expect("get");
    assert_eq!(value, "v2");
}

#[test]
fn missing_entry_returns_none_without_populating_session() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    let result = secrets::get_password(&id).expect("get on missing");
    assert!(result.is_none());
    assert!(
        !secrets::session_contains_for_test(&id),
        "missing-entry lookup should not populate session"
    );
}

#[test]
fn password_for_connection_reports_actionable_error_when_missing() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    let err = secrets::password_for_connection(&id).expect_err("missing password");
    assert!(
        err.contains("Open Connections"),
        "error should guide user to re-save credentials: {err}"
    );
}

#[test]
fn empty_password_round_trips_and_populates_session() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "").expect("save empty password");

    let value = secrets::password_for_connection(&id).expect("get empty password");
    assert_eq!(value, "");
    assert!(
        secrets::session_contains_for_test(&id),
        "empty password should populate session like any other credential"
    );
}

#[test]
fn lock_credentials_clears_session() {
    let _guard = init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "locked-away").expect("save");
    let _ = secrets::password_for_connection(&id).expect("prime session");
    assert!(secrets::session_contains_for_test(&id));

    secrets::lock_credentials();
    assert!(!secrets::session_contains_for_test(&id));

    let value = secrets::password_for_connection(&id).expect("reload after lock");
    assert_eq!(value, "locked-away");
    assert!(
        secrets::session_contains_for_test(&id),
        "reload after lock should repopulate the session"
    );

    secrets::reset_store_read_count_for_test();
    let again = secrets::password_for_connection(&id).expect("session reuse after reload");
    assert_eq!(again, "locked-away");
    assert_eq!(
        secrets::store_read_count_for_test(),
        0,
        "session should satisfy subsequent reads without store access"
    );
}

#[test]
fn disabled_backend_label_is_reported() {
    let _guard = init_disabled_backend();
    assert_eq!(secrets::active_backend_label(), "disabled");
}
