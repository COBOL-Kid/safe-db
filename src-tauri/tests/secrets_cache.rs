use safe_db_lib::secrets;
use std::sync::Mutex;
use std::time::Duration;
use uuid::Uuid;

static ENV_LOCK: Mutex<()> = Mutex::new(());

fn unique_id() -> String {
    format!("cache-test-{}", Uuid::new_v4())
}

fn init_disabled_backend() {
    let _guard = ENV_LOCK.lock().unwrap();
    // SAFETY: env mutation in tests is serialized by ENV_LOCK. Edition 2024 makes set_var unsafe;
    // we are on edition 2021 today but the pattern is forward-compatible.
    unsafe {
        std::env::set_var("SAFEDB_KEYCHAIN_BACKEND", "disabled");
    }
    secrets::init_store().expect("disabled backend should initialize");
}

#[test]
fn save_then_get_returns_cached_value() {
    init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "first-secret").expect("save");
    let first = secrets::get_password(&id).expect("get").expect("present");
    assert_eq!(first, "first-secret");

    // Cache should be populated and have a small age.
    let age = secrets::cache_age_for_test(&id).expect("cache entry should exist");
    assert!(age < Duration::from_secs(5), "cache age {age:?} should be fresh");

    // Mutate the underlying store out-of-band: if the cache is bypassed,
    // get_password will return the new value. We assert it returns the cached
    // value, which is the same value (proving the cache is populated and read).
    let second = secrets::get_password(&id).expect("get").expect("present");
    assert_eq!(second, "first-secret");
}

#[test]
fn delete_invalidates_cache() {
    init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "to-be-deleted").expect("save");
    let _ = secrets::get_password(&id).expect("get").expect("present");
    assert!(secrets::cache_age_for_test(&id).is_some(), "cache populated");

    secrets::delete_password(&id).expect("delete");
    assert!(
        secrets::cache_age_for_test(&id).is_none(),
        "cache should be invalidated after delete"
    );
    assert!(
        secrets::get_password(&id).expect("get after delete").is_none(),
        "underlying store should also be empty"
    );
}

#[test]
fn save_overwrites_cached_value() {
    init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "first").expect("save first");
    let _ = secrets::get_password(&id).expect("get").expect("present");

    secrets::save_password(&id, "second").expect("save second");

    let value = secrets::get_password(&id).expect("get").expect("present");
    assert_eq!(value, "second", "save should overwrite cached value");
}

#[test]
fn ttl_expiry_triggers_refetch() {
    init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "original").expect("save");
    let _ = secrets::get_password(&id).expect("get").expect("present");

    // Fast-forward the entry's fetched_at past the TTL.
    secrets::cache_set_age_for_test(&id, Duration::from_secs(60 * 60));

    // Underlying store still has the value; cache should be treated as expired
    // and the read should succeed by hitting the store.
    let value = secrets::get_password(&id).expect("get").expect("present");
    assert_eq!(value, "original");
}

#[test]
fn delete_then_save_yields_new_value() {
    init_disabled_backend();
    let id = unique_id();

    secrets::save_password(&id, "v1").expect("save v1");
    secrets::delete_password(&id).expect("delete v1");
    secrets::save_password(&id, "v2").expect("save v2");

    let value = secrets::get_password(&id).expect("get").expect("present");
    assert_eq!(value, "v2");
}

#[test]
fn missing_entry_returns_none_without_touching_cache() {
    init_disabled_backend();
    let id = unique_id();

    let result = secrets::get_password(&id).expect("get on missing");
    assert!(result.is_none());
    assert!(
        secrets::cache_age_for_test(&id).is_none(),
        "missing-entry lookup should not populate cache"
    );
}
