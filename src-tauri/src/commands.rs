use tauri::{AppHandle, Manager};

use crate::adapters::Adapter;
use crate::config::ConfigStore;
use crate::introspect::Schema;
use crate::secrets;
use crate::types::ConnectionDef;

#[tauri::command]
pub async fn test_connection(def: ConnectionDef, password: String) -> Result<String, String> {
    let adapter = Adapter::connect(&def, &password)
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
    let config_store = app.state::<ConfigStore>();
    config_store.save(def.clone()).map_err(|e| e.to_string())?;

    if let Some(pw) = password {
        if !pw.is_empty() {
            secrets::save_password(&def.id, &pw).map_err(|e| e.to_string())?;
        }
    }

    Ok(())
}

#[tauri::command]
pub async fn list_connections(app: AppHandle) -> Result<Vec<ConnectionDef>, String> {
    let config_store = app.state::<ConfigStore>();
    config_store.list().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_connection(app: AppHandle, id: String) -> Result<(), String> {
    let config_store = app.state::<ConfigStore>();
    config_store.delete(&id).map_err(|e| e.to_string())?;
    secrets::delete_password(&id).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn get_schema(app: AppHandle, connection_id: String) -> Result<Schema, String> {
    let config_store = app.state::<ConfigStore>();
    let def = config_store
        .get(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Connection not found".to_string())?;

    let password = secrets::get_password(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Password not found in keyring".to_string())?;

    let adapter = Adapter::connect(&def, &password)
        .await
        .map_err(|e| e.to_string())?;
    let schema = adapter.introspect().await.map_err(|e| e.to_string())?;

    Ok(schema)
}
