use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::Result;
use keyring_core::api::{CredentialApi, CredentialStoreApi};
use keyring_core::{Entry, Error};
use zeroize::Zeroizing;

#[cfg(any(test, feature = "test-helpers"))]
use super::STORE_READ_COUNT;
use super::session::{session_get, session_invalidate, session_put};
use super::{ENV_BACKEND, SERVICE_NAME};

type DisabledMemoryMap = HashMap<(String, String), Zeroizing<Vec<u8>>>;

static DISABLED_STORE: OnceLock<Mutex<DisabledMemoryMap>> = OnceLock::new();

fn disabled_store() -> &'static Mutex<DisabledMemoryMap> {
    DISABLED_STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(super) struct DisabledMemoryStore;

impl DisabledMemoryStore {
    pub(super) fn arc() -> Arc<Self> {
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

pub(super) fn write_to_default_store(connection_id: &str, password: &str) -> Result<()> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    entry.set_password(password)?;
    Ok(())
}

pub(super) fn delete_from_default_store(connection_id: &str) -> Result<()> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    match entry.delete_credential() {
        Ok(()) | Err(Error::NoEntry) => Ok(()),
        Err(e) => Err(e.into()),
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

fn fetch_from_store(connection_id: &str) -> Result<Option<String>> {
    read_from_default_store(connection_id)
}

fn is_missing_entitlement_error_impl(err: &impl std::fmt::Display) -> bool {
    let msg = err.to_string().to_ascii_lowercase();
    msg.contains("entitlement") || msg.contains("platform failure")
}

fn format_credential_error(err: impl std::fmt::Display) -> String {
    format!(
        "Could not unlock stored credentials ({err}). Open Connections, re-enter the password, \
         and save the connection again."
    )
}

pub fn format_save_credential_error(err: impl std::fmt::Display) -> String {
    if is_missing_entitlement_error_impl(&err) {
        format!(
            "Could not store credentials: the app is not signed with keychain entitlements. \
             For local development, set {ENV_BACKEND}=disabled or run a signed release bundle \
             from pnpm tauri build."
        )
    } else {
        format!("Could not store credentials ({err}).")
    }
}

#[cfg_attr(not(any(test, feature = "test-helpers")), allow(dead_code))]
pub fn is_missing_entitlement_error(err: &impl std::fmt::Display) -> bool {
    is_missing_entitlement_error_impl(err)
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

#[derive(serde::Serialize, serde::Deserialize)]
struct BoundCredential {
    version: u32,
    fingerprint: String,
    password: String,
}

pub fn password_for_definition(def: &crate::types::ConnectionDef) -> Result<String, String> {
    let cache_key = format!("{}:{}", def.id, def.credential_fingerprint());
    if let Some(cached) = session_get(&cache_key) {
        return Ok(cached);
    }
    let raw = fetch_from_store(&def.id).map_err(format_credential_error)?;
    let raw = raw.ok_or_else(|| {
        "Password not found for this connection. Open Connections, enter the password, and save the connection again."
            .to_string()
    })?;
    let record: BoundCredential = serde_json::from_str(&raw).map_err(|_| {
        "This credential predates endpoint binding. Re-enter the password and save the connection before use."
            .to_string()
    })?;
    if record.version != 1 || record.fingerprint != def.credential_fingerprint() {
        return Err(
            "Stored credentials do not match this connection endpoint or transport configuration. Re-enter the password and save the connection."
                .to_string(),
        );
    }
    session_put(&cache_key, Zeroizing::new(record.password.clone()));
    Ok(record.password)
}

pub fn save_password_for_definition(
    def: &crate::types::ConnectionDef,
    password: &str,
) -> Result<()> {
    let record = BoundCredential {
        version: 1,
        fingerprint: def.credential_fingerprint(),
        password: password.to_string(),
    };
    let encoded = serde_json::to_string(&record)?;
    write_to_default_store(&def.id, &encoded)
        .map_err(|e| anyhow::anyhow!(format_save_credential_error(e)))?;
    session_invalidate(&def.id);
    let cache_key = format!("{}:{}", def.id, def.credential_fingerprint());
    session_put(&cache_key, Zeroizing::new(password.to_string()));
    Ok(())
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
