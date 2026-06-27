use anyhow::Result;
use keyring_core::api::{CredentialApi, CredentialStoreApi};
use keyring_core::{Entry, Error};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use zeroize::Zeroizing;

const SERVICE_NAME: &str = "safe-db";
const ENV_BACKEND: &str = "SAFEDB_KEYCHAIN_BACKEND";
#[cfg(target_os = "macos")]
const PROTECTED_PROBE_KEY: &str = "__safedb_entitlement_probe__";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BackendMode {
    Disabled,
    #[cfg(target_os = "macos")]
    Protected,
    #[cfg(target_os = "windows")]
    Windows,
    #[cfg(target_os = "linux")]
    LinuxKeyutils,
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    Mock,
}

static ACTIVE_BACKEND: OnceLock<BackendMode> = OnceLock::new();
static SESSION: OnceLock<Mutex<HashMap<String, Zeroizing<String>>>> = OnceLock::new();

#[cfg(any(test, feature = "test-helpers"))]
static STORE_READ_COUNT: OnceLock<Mutex<usize>> = OnceLock::new();

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RequestedBackend {
    Auto,
    Disabled,
    #[cfg(target_os = "macos")]
    Protected,
}

fn parse_requested_backend() -> RequestedBackend {
    let Ok(raw) = std::env::var(ENV_BACKEND) else {
        return RequestedBackend::Auto;
    };
    match raw.to_ascii_lowercase().as_str() {
        "disabled" => RequestedBackend::Disabled,
        #[cfg(target_os = "macos")]
        "protected" => RequestedBackend::Protected,
        #[cfg(not(target_os = "macos"))]
        "protected" => {
            log::warn!(
                "{ENV_BACKEND}=protected is only supported on macOS; using platform default backend"
            );
            RequestedBackend::Auto
        }
        "keychain" | "legacy" => {
            log::warn!(
                "legacy macOS Keychain is not supported; use {ENV_BACKEND}=protected, \
                 {ENV_BACKEND}=disabled, or {ENV_BACKEND}=auto"
            );
            RequestedBackend::Auto
        }
        "auto" | "" => RequestedBackend::Auto,
        other => {
            log::warn!(
                "unknown {ENV_BACKEND}={other}; supported values: auto, disabled \
                 (macOS: protected)"
            );
            RequestedBackend::Auto
        }
    }
}

fn session() -> &'static Mutex<HashMap<String, Zeroizing<String>>> {
    SESSION.get_or_init(|| Mutex::new(HashMap::new()))
}

fn session_get(connection_id: &str) -> Option<String> {
    session()
        .lock()
        .ok()
        .and_then(|guard| guard.get(connection_id).map(|v| v.to_string()))
}

fn session_put(connection_id: &str, password: Zeroizing<String>) {
    if let Ok(mut guard) = session().lock() {
        guard.insert(connection_id.to_string(), password);
    }
}

fn session_invalidate(connection_id: &str) {
    if let Ok(mut guard) = session().lock() {
        guard.remove(connection_id);
    }
}

pub fn lock_credentials() {
    if let Ok(mut guard) = session().lock() {
        guard.clear();
    }
}

pub fn active_backend_label() -> &'static str {
    match active_backend() {
        BackendMode::Disabled => "disabled",
        #[cfg(target_os = "macos")]
        BackendMode::Protected => "protected",
        #[cfg(target_os = "windows")]
        BackendMode::Windows => "windows",
        #[cfg(target_os = "linux")]
        BackendMode::LinuxKeyutils => "linux-keyutils",
        #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
        BackendMode::Mock => "mock",
    }
}

fn active_backend() -> BackendMode {
    *ACTIVE_BACKEND.get().expect("secrets store not initialized")
}

type DisabledMemoryMap = HashMap<(String, String), Zeroizing<Vec<u8>>>;

static DISABLED_STORE: OnceLock<Mutex<DisabledMemoryMap>> = OnceLock::new();

fn disabled_store() -> &'static Mutex<DisabledMemoryMap> {
    DISABLED_STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

struct DisabledMemoryStore;

impl DisabledMemoryStore {
    fn arc() -> Arc<Self> {
        Arc::new(Self)
    }
}

impl CredentialStoreApi for DisabledMemoryStore {
    fn vendor(&self) -> String {
        "safe-db disabled (in-memory) credential store".to_string()
    }

    fn id(&self) -> String {
        "safe-db::disabled".to_string()
    }

    fn build(
        &self,
        service: &str,
        user: &str,
        modifiers: Option<&HashMap<&str, &str>>,
    ) -> Result<Entry, Error> {
        if modifiers.is_some_and(|m| !m.is_empty()) {
            return Err(Error::NotSupportedByStore(
                "disabled store does not accept entry modifiers".to_string(),
            ));
        }
        let key = (service.to_string(), user.to_string());
        let credential: Arc<keyring_core::Credential> = Arc::new(DisabledCredential { key });
        Ok(Entry::new_with_credential(credential))
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

struct DisabledCredential {
    key: (String, String),
}

impl CredentialApi for DisabledCredential {
    fn set_secret(&self, secret: &[u8]) -> Result<(), Error> {
        let mut guard = disabled_store()
            .lock()
            .expect("disabled store poisoned for set");
        guard.insert(self.key.clone(), Zeroizing::new(secret.to_vec()));
        Ok(())
    }

    fn get_secret(&self) -> Result<Vec<u8>, Error> {
        let guard = disabled_store()
            .lock()
            .expect("disabled store poisoned for get");
        match guard.get(&self.key) {
            Some(value) => Ok(value.to_vec()),
            None => Err(Error::NoEntry),
        }
    }

    fn delete_credential(&self) -> Result<(), Error> {
        let mut guard = disabled_store()
            .lock()
            .expect("disabled store poisoned for delete");
        guard.remove(&self.key);
        Ok(())
    }

    fn get_credential(&self) -> Result<Option<Arc<keyring_core::Credential>>, Error> {
        Ok(None)
    }

    fn get_specifiers(&self) -> Option<(String, String)> {
        Some(self.key.clone())
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

fn set_active_backend(mode: BackendMode) {
    let _ = ACTIVE_BACKEND.set(mode);
}

fn init_disabled() -> Result<()> {
    log::warn!(
        "{ENV_BACKEND}=disabled: credentials are held in process memory only and will not \
         survive an app restart. Do not ship this configuration."
    );
    keyring_core::set_default_store(DisabledMemoryStore::arc());
    set_active_backend(BackendMode::Disabled);
    Ok(())
}

#[cfg(target_os = "macos")]
fn setup_macos_protected_store() -> Result<()> {
    let store = apple_native_keyring_store::protected::Store::new().map_err(|e| {
        anyhow::anyhow!(
            "failed to init macOS Protected Data store ({e}). Build a signed app bundle with \
             entitlements (see src-tauri/Entitlements.plist), or set {ENV_BACKEND}=disabled \
             for local development."
        )
    })?;
    keyring_core::set_default_store(store);
    Ok(())
}

#[cfg(target_os = "macos")]
fn finish_macos_protected_init() {
    set_active_backend(BackendMode::Protected);
    log::info!("credential backend: macOS Protected Data");
}

/// Verifies the default store can write credentials (requires keychain entitlements when signed).
#[cfg(target_os = "macos")]
fn probe_protected_store() -> Result<()> {
    write_to_default_store(PROTECTED_PROBE_KEY, "probe")?;
    delete_from_default_store(PROTECTED_PROBE_KEY)?;
    Ok(())
}

#[cfg(target_os = "macos")]
fn init_macos_protected() -> Result<()> {
    setup_macos_protected_store()?;
    probe_protected_store()?;
    finish_macos_protected_init();
    Ok(())
}

#[cfg(target_os = "macos")]
fn init_macos_auto() -> Result<()> {
    match setup_macos_protected_store() {
        Ok(()) => match probe_protected_store() {
            Ok(()) => {
                finish_macos_protected_init();
                Ok(())
            }
            Err(probe_err) => {
                if cfg!(debug_assertions) {
                    log::warn!(
                        "Protected Data write probe failed in debug build ({probe_err}); \
                         unsigned pnpm tauri dev builds lack keychain entitlements — using \
                         in-memory disabled backend for this session"
                    );
                    init_disabled()
                } else {
                    Err(probe_err)
                }
            }
        },
        Err(protected_err) => {
            if cfg!(debug_assertions) {
                log::warn!(
                    "Protected Data unavailable in debug build ({protected_err}); using \
                     in-memory disabled backend for this session"
                );
                init_disabled()
            } else {
                Err(protected_err)
            }
        }
    }
}

pub fn init_store() -> Result<()> {
    #[cfg(any(test, feature = "test-helpers"))]
    let _ = STORE_READ_COUNT.set(Mutex::new(0));

    match parse_requested_backend() {
        RequestedBackend::Disabled => init_disabled(),
        #[cfg(target_os = "macos")]
        RequestedBackend::Protected => init_macos_protected(),
        RequestedBackend::Auto => {
            #[cfg(target_os = "macos")]
            {
                init_macos_auto()
            }
            #[cfg(target_os = "windows")]
            {
                let store = windows_native_keyring_store::Store::new()
                    .map_err(|e| anyhow::anyhow!("failed to init Windows credential store: {e}"))?;
                keyring_core::set_default_store(store);
                set_active_backend(BackendMode::Windows);
                Ok(())
            }
            #[cfg(target_os = "linux")]
            {
                let store = linux_keyutils_keyring_store::Store::new()
                    .map_err(|e| anyhow::anyhow!("failed to init linux-keyutils store: {e}"))?;
                keyring_core::set_default_store(store);
                set_active_backend(BackendMode::LinuxKeyutils);
                Ok(())
            }
            #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
            {
                let store = keyring_core::mock::Store::new()
                    .map_err(|e| anyhow::anyhow!("failed to init keyring mock store: {e}"))?;
                keyring_core::set_default_store(store);
                set_active_backend(BackendMode::Mock);
                Ok(())
            }
        }
    }
}

fn read_from_default_store(connection_id: &str) -> Result<Option<String>> {
    #[cfg(any(test, feature = "test-helpers"))]
    if let Some(counter) = STORE_READ_COUNT.get()
        && let Ok(mut guard) = counter.lock()
    {
        *guard += 1;
    }

    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    match entry.get_password() {
        Ok(password) => Ok(Some(password)),
        Err(Error::NoEntry) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

fn write_to_default_store(connection_id: &str, password: &str) -> Result<()> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    entry.set_password(password)?;
    Ok(())
}

fn delete_from_default_store(connection_id: &str) -> Result<()> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    match entry.delete_credential() {
        Ok(()) | Err(Error::NoEntry) => Ok(()),
        Err(e) => Err(e.into()),
    }
}

fn fetch_from_store(connection_id: &str) -> Result<Option<String>> {
    read_from_default_store(connection_id)
}

fn is_missing_entitlement_error(err: &impl std::fmt::Display) -> bool {
    let msg = err.to_string().to_ascii_lowercase();
    msg.contains("entitlement") || msg.contains("platform failure")
}

fn format_credential_error(err: impl std::fmt::Display) -> String {
    format!(
        "Could not unlock stored credentials ({err}). Open Connections, re-enter the password, \
         and save the connection again."
    )
}

fn format_save_credential_error(err: impl std::fmt::Display) -> String {
    if is_missing_entitlement_error(&err) {
        format!(
            "Could not store credentials: the app is not signed with keychain entitlements. \
             For local development, set {ENV_BACKEND}=disabled or run a signed release bundle \
             from pnpm tauri build."
        )
    } else {
        format!("Could not store credentials ({err}).")
    }
}

/// Builder/query hot path: use in-process session first; touch the OS store only on miss.
pub fn password_for_connection(connection_id: &str) -> Result<String, String> {
    if let Some(cached) = session_get(connection_id) {
        return Ok(cached);
    }

    match fetch_from_store(connection_id) {
        Ok(Some(password)) => {
            let stored = Zeroizing::new(password);
            let returned = stored.to_string();
            session_put(connection_id, stored);
            Ok(returned)
        }
        Ok(None) => Err(
            "Password not found for this connection. Open Connections, enter the password, and \
             save the connection again."
                .to_string(),
        ),
        Err(e) => Err(format_credential_error(e)),
    }
}

pub fn save_password(connection_id: &str, password: &str) -> Result<()> {
    write_to_default_store(connection_id, password)
        .map_err(|e| anyhow::anyhow!(format_save_credential_error(e)))?;
    session_put(connection_id, Zeroizing::new(password.to_string()));
    Ok(())
}

pub fn get_password(connection_id: &str) -> Result<Option<String>> {
    if let Some(cached) = session_get(connection_id) {
        return Ok(Some(cached));
    }

    match fetch_from_store(connection_id) {
        Ok(Some(password)) => {
            let stored = Zeroizing::new(password);
            let returned = stored.to_string();
            session_put(connection_id, stored);
            Ok(Some(returned))
        }
        Ok(None) => Ok(None),
        Err(e) => Err(e),
    }
}

pub fn delete_password(connection_id: &str) -> Result<()> {
    session_invalidate(connection_id);
    delete_from_default_store(connection_id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn save_credential_error_mentions_entitlements() {
        let err =
            format_save_credential_error("Platform failure: A required entitlement isn't present.");
        assert!(err.contains("entitlements"));
        assert!(err.contains(ENV_BACKEND));
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
}
