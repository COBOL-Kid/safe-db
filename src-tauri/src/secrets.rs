use std::sync::{Mutex, OnceLock};
use std::time::Duration;

const SERVICE_NAME: &str = "safe-db";
const ENV_BACKEND: &str = "SAFEDB_KEYCHAIN_BACKEND";
#[cfg(target_os = "macos")]
const PROTECTED_PROBE_KEY: &str = "__safedb_entitlement_probe__";
const SESSION_IDLE_TIMEOUT: Duration = Duration::from_secs(15 * 60);

#[cfg_attr(not(any(test, feature = "test-helpers")), allow(dead_code))]
static STORE_READ_COUNT: OnceLock<Mutex<usize>> = OnceLock::new();

mod backend;
mod session;
mod store;

#[cfg(any(test, feature = "test-helpers"))]
pub use backend::{RequestedBackend, parse_requested_backend_from};
pub use backend::{active_backend_label, init_store};
pub use session::lock_credentials;
#[cfg(any(test, feature = "test-helpers"))]
pub use session::{
    reset_store_read_count_for_test, session_contains_for_test, store_read_count_for_test,
};
pub use store::{
    delete_password, get_password, password_for_connection, password_for_definition, save_password,
    save_password_for_definition,
};
#[cfg(any(test, feature = "test-helpers"))]
pub use store::{format_save_credential_error, is_missing_entitlement_error};
