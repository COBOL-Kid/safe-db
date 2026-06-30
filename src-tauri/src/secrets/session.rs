use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

use zeroize::Zeroizing;

use super::SESSION_IDLE_TIMEOUT;
#[cfg(any(test, feature = "test-helpers"))]
use super::STORE_READ_COUNT;

struct SessionCredential {
    password: Zeroizing<String>,
    last_used: Instant,
}

static SESSION: OnceLock<Mutex<HashMap<String, SessionCredential>>> = OnceLock::new();

fn session() -> &'static Mutex<HashMap<String, SessionCredential>> {
    SESSION.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(super) fn session_get(connection_id: &str) -> Option<String> {
    let mut guard = session().lock().ok()?;
    let expired = guard
        .get(connection_id)
        .is_some_and(|value| value.last_used.elapsed() >= SESSION_IDLE_TIMEOUT);
    if expired {
        guard.remove(connection_id);
        return None;
    }
    let value = guard.get_mut(connection_id)?;
    value.last_used = Instant::now();
    Some(value.password.to_string())
}

pub(super) fn session_put(connection_id: &str, password: Zeroizing<String>) {
    if let Ok(mut guard) = session().lock() {
        guard.insert(
            connection_id.to_string(),
            SessionCredential {
                password,
                last_used: Instant::now(),
            },
        );
    }
}

pub(super) fn session_invalidate(connection_id: &str) {
    if let Ok(mut guard) = session().lock() {
        guard.remove(connection_id);
        let prefix = format!("{connection_id}:");
        guard.retain(|key, _| !key.starts_with(&prefix));
    }
}

pub fn lock_credentials() {
    if let Ok(mut guard) = session().lock() {
        guard.clear();
    }
}

#[cfg(any(test, feature = "test-helpers"))]
pub fn store_read_count_for_test() -> usize {
    STORE_READ_COUNT
        .get()
        .and_then(|c| c.lock().ok())
        .map(|g| *g)
        .unwrap_or(0)
}

#[cfg(any(test, feature = "test-helpers"))]
pub fn reset_store_read_count_for_test() {
    if let Some(c) = STORE_READ_COUNT.get()
        && let Ok(mut guard) = c.lock()
    {
        *guard = 0;
    }
}

#[cfg(any(test, feature = "test-helpers"))]
pub fn session_contains_for_test(connection_id: &str) -> bool {
    session()
        .lock()
        .ok()
        .is_some_and(|guard| guard.contains_key(connection_id))
}
