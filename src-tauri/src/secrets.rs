use anyhow::Result;
use keyring_core::Entry;

const SERVICE_NAME: &str = "safe-db";

pub fn init_store() -> Result<()> {
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

pub fn save_password(connection_id: &str, password: &str) -> Result<()> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    entry.set_password(password)?;
    Ok(())
}

pub fn get_password(connection_id: &str) -> Result<Option<String>> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    match entry.get_password() {
        Ok(password) => Ok(Some(password)),
        Err(keyring_core::Error::NoEntry) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn delete_password(connection_id: &str) -> Result<()> {
    let entry = Entry::new(SERVICE_NAME, connection_id)?;
    match entry.delete_credential() {
        Ok(()) => Ok(()),
        Err(keyring_core::Error::NoEntry) => Ok(()),
        Err(e) => Err(e.into()),
    }
}
