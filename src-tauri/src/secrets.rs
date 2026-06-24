use anyhow::Result;
use keyring_core::Entry;

const SERVICE_NAME: &str = "safe-db";

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
