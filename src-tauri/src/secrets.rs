use anyhow::Result;
use keyring_core::api::{CredentialApi, CredentialStoreApi};
use keyring_core::{Entry, Error};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use zeroize::Zeroizing;

const SERVICE_NAME: &str = "safe-db";
const CACHE_TTL: Duration = Duration::from_secs(15 * 60);
const ENV_DISABLED_BACKEND: &str = "SAFEDB_KEYCHAIN_BACKEND";
const ENV_DISABLED_VALUE: &str = "disabled";

#[cfg(any(test, feature = "test-helpers"))]
pub fn cache_age_for_test(connection_id: &str) -> Option<Duration> {
    CACHE
        .get()
        .and_then(|c| c.lock().ok())
        .and_then(|guard| guard.get(connection_id).map(|entry| entry.fetched_at.elapsed()))
}

#[cfg(any(test, feature = "test-helpers"))]
pub fn cache_set_age_for_test(connection_id: &str, age: Duration) {
    if let Some(c) = CACHE.get()
        && let Ok(mut guard) = c.lock()
        && let Some(entry) = guard.get_mut(connection_id)
    {
        entry.fetched_at = Instant::now() - age;
    }
}

#[derive(Clone)]
struct CachedEntry {
    password: Zeroizing<String>,
    fetched_at: Instant,
}

static CACHE: OnceLock<Mutex<HashMap<String, CachedEntry>>> = OnceLock::new();

fn cache() -> &'static Mutex<HashMap<String, CachedEntry>> {
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cache_take(connection_id: &str) -> Option<String> {
    let mut guard = cache().lock().expect("password cache poisoned");
    let entry = guard.get(connection_id)?;
    if entry.fetched_at.elapsed() >= CACHE_TTL {
        guard.remove(connection_id);
        return None;
    }
    let cloned = entry.password.clone();
    drop(guard);
    let value = cloned.to_string();
    cache_put(connection_id, cloned);
    Some(value)
}

fn cache_put(connection_id: &str, password: Zeroizing<String>) {
    let mut guard = cache().lock().expect("password cache poisoned");
    guard.insert(
        connection_id.to_string(),
        CachedEntry {
            password,
            fetched_at: Instant::now(),
        },
    );
}

fn cache_invalidate(connection_id: &str) {
    if let Ok(mut guard) = cache().lock() {
        guard.remove(connection_id);
    }
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
            Some(value) if !value.is_empty() => Ok(value.to_vec()),
            _ => Err(Error::NoEntry),
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

fn env_requests_disabled_backend() -> bool {
    std::env::var(ENV_DISABLED_BACKEND)
        .map(|v| v.eq_ignore_ascii_case(ENV_DISABLED_VALUE))
        .unwrap_or(false)
}

pub fn init_store() -> Result<()> {
    if env_requests_disabled_backend() {
        log::warn!(
            "{ENV_DISABLED_BACKEND}={ENV_DISABLED_VALUE}: credentials will be held in process \
             memory only and will not survive an app restart. Do not ship this configuration."
        );
        keyring_core::set_default_store(DisabledMemoryStore::arc());
        return Ok(());
    }

    #[cfg(target_os = "macos")]
    {
        let store = apple_native_keyring_store::protected::Store::new().map_err(|e| {
            anyhow::anyhow!(
                "failed to init macOS protected data store ({e}). This usually means the app is \
                 not sandboxed/signed. Build a signed bundle with 'pnpm tauri build', or set \
                 {ENV_DISABLED_BACKEND}={ENV_DISABLED_VALUE} for development iteration."
            )
        })?;
        keyring_core::set_default_store(store);
    }
    #[cfg(target_os = "windows")]
    {
        let store = windows_native_keyring_store::Store::new()
            .map_err(|e| anyhow::anyhow!("failed to init Windows credential store: {e}"))?;
        keyring_core::set_default_store(store);
    }
    #[cfg(target_os = "linux")]
    {
        let store = linux_keyutils_keyring_store::Store::new()
            .map_err(|e| anyhow::anyhow!("failed to init linux-keyutils store: {e}"))?;
        keyring_core::set_default_store(store);
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    {
        let store = keyring_core::mock::Store::new()
            .map_err(|e| anyhow::anyhow!("failed to init keyring mock store: {e}"))?;
        keyring_core::set_default_store(store);
    }

    Ok(())
}

pub fn save_password(connection_id: &str, password: &str) -> Result<()> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    entry.set_password(password)?;
    cache_put(connection_id, Zeroizing::new(password.to_string()));
    Ok(())
}

pub fn get_password(connection_id: &str) -> Result<Option<String>> {
    if let Some(cached) = cache_take(connection_id) {
        return Ok(Some(cached));
    }
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    match entry.get_password() {
        Ok(password) => {
            let stored = Zeroizing::new(password);
            let returned = stored.to_string();
            cache_put(connection_id, stored);
            Ok(Some(returned))
        }
        Err(Error::NoEntry) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn delete_password(connection_id: &str) -> Result<()> {
    cache_invalidate(connection_id);
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    match entry.delete_credential() {
        Ok(()) | Err(Error::NoEntry) => Ok(()),
        Err(e) => Err(e.into()),
    }
}
