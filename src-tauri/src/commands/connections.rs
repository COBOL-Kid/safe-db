use tauri::{AppHandle, Manager};

use crate::config::ConfigStore;
use crate::secrets;
use crate::types::ConnectionDef;

fn persist_connection(
    config_store: &ConfigStore,
    def: ConnectionDef,
    password: Option<String>,
) -> Result<(), String> {
    def.validate()?;
    let previous = config_store.get(&def.id).map_err(|e| e.to_string())?;
    if let Some(existing) = &previous
        && existing.credential_fingerprint() != def.credential_fingerprint()
        && password.is_none()
    {
        return Err(
            "Endpoint or transport changes require the password to be re-entered".to_string(),
        );
    }
    if previous.is_none() && password.is_none() {
        return Err("A password is required when creating a connection".to_string());
    }

    config_store.save(def.clone()).map_err(|e| e.to_string())?;
    if let Some(password) = password
        && let Err(error) = secrets::save_password_for_definition(&def, &password)
    {
        if let Some(previous) = previous {
            let _ = config_store.save(previous);
        } else {
            let _ = config_store.delete(&def.id);
        }
        return Err(error.to_string());
    }
    Ok(())
}

#[tauri::command]
pub async fn test_connection(def: ConnectionDef, password: String) -> Result<String, String> {
    def.validate()?;
    let adapter = crate::adapters::Adapter::connect(&def, &password)
        .await
        .map_err(|e| e.to_string())?;
    let version = adapter.test().await.map_err(|e| e.to_string())?;
    Ok(version)
}

#[tauri::command]
pub async fn save_connection(
    app: AppHandle,
    def: ConnectionDef,
    password: Option<String>,
) -> Result<(), String> {
    def.validate()?;
    let config_store = app.state::<ConfigStore>();
    persist_connection(&config_store, def, password)
}

#[tauri::command]
pub async fn create_connection(
    app: AppHandle,
    mut def: ConnectionDef,
    password: String,
) -> Result<ConnectionDef, String> {
    def.id = uuid::Uuid::new_v4().to_string();
    let config_store = app.state::<ConfigStore>();
    persist_connection(&config_store, def.clone(), Some(password))?;
    Ok(def)
}

#[tauri::command]
pub async fn update_connection(
    app: AppHandle,
    def: ConnectionDef,
    password: Option<String>,
) -> Result<(), String> {
    let config_store = app.state::<ConfigStore>();
    if config_store
        .get(&def.id)
        .map_err(|e| e.to_string())?
        .is_none()
    {
        return Err("Connection not found".to_string());
    }
    persist_connection(&config_store, def, password)
}

#[tauri::command]
pub async fn list_connections(app: AppHandle) -> Result<Vec<ConnectionDef>, String> {
    let config_store = app.state::<ConfigStore>();
    config_store.list().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_connection(app: AppHandle, id: String) -> Result<(), String> {
    let config_store = app.state::<ConfigStore>();
    let previous = config_store.get(&id).map_err(|e| e.to_string())?;
    config_store.delete(&id).map_err(|e| e.to_string())?;
    if let Err(error) = secrets::delete_password(&id) {
        if let Some(previous) = previous {
            let _ = config_store.save(previous);
        }
        return Err(error.to_string());
    }
    Ok(())
}

#[tauri::command]
pub fn lock_credentials() {
    secrets::lock_credentials();
}
