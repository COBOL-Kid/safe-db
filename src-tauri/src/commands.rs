use tauri::{AppHandle, Manager};

use crate::adapters::{Adapter, DEFAULT_TIMEOUT_MS};
use crate::config::ConfigStore;
use crate::introspect::Schema;
use crate::queries::{HistoryEntry, QueryStore, SavedQuery};
use crate::query::{compile, validate, QueryResult, QuerySpec};
use crate::secrets;
use crate::settings::{Settings, SettingsStore};
use crate::types::ConnectionDef;

fn now_iso() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    format!("{}", now)
}

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

#[tauri::command]
pub async fn run_query(
    app: AppHandle,
    connection_id: String,
    mut spec: QuerySpec,
) -> Result<QueryResult, String> {
    let config_store = app.state::<ConfigStore>();
    let settings_store = app.state::<SettingsStore>();
    let query_store = app.state::<QueryStore>();

    let def = config_store
        .get(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Connection not found".to_string())?;

    let password = secrets::get_password(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Password not found in keyring".to_string())?;

    let settings = settings_store.load().map_err(|e| e.to_string())?;

    let adapter = Adapter::connect(&def, &password)
        .await
        .map_err(|e| e.to_string())?;

    let schema = adapter.introspect().await.map_err(|e| e.to_string())?;

    let outcome =
        validate(&mut spec, &schema, &settings.blocked_schemas).map_err(|e| e.to_string())?;

    let compiled = compile(&spec, def.dialect);

    let mut warnings = outcome.warnings;

    let explain_result =
        adapter
            .explain(&compiled)
            .await
            .unwrap_or(crate::adapters::ExplainResult {
                cost: None,
                warning: Some("EXPLAIN failed".to_string()),
            });

    if let Some(cost) = explain_result.cost {
        if cost > settings.explain_cost_threshold {
            warnings.push(format!(
                "Estimated query cost ({:.0}) exceeds threshold ({:.0}) — this may be slow",
                cost, settings.explain_cost_threshold
            ));
        }
    }
    if let Some(w) = explain_result.warning {
        warnings.push(w);
    }

    let result = match adapter.execute_query(&compiled, DEFAULT_TIMEOUT_MS).await {
        Ok(mut r) => {
            r.truncated = r.row_count >= outcome.limit as usize;
            r.warnings = warnings.clone();
            r
        }
        Err(e) => {
            let err_msg = e.to_string();
            let entry = HistoryEntry {
                id: uuid::Uuid::new_v4().to_string(),
                connection_id: connection_id.clone(),
                connection_name: def.name.clone(),
                spec: spec.clone(),
                row_count: 0,
                warnings,
                error: Some(err_msg.clone()),
                timestamp: now_iso(),
            };
            let _ = query_store.add_history(entry);
            return Err(err_msg);
        }
    };

    let entry = HistoryEntry {
        id: uuid::Uuid::new_v4().to_string(),
        connection_id: connection_id.clone(),
        connection_name: def.name.clone(),
        spec: spec.clone(),
        row_count: result.row_count,
        warnings: result.warnings.clone(),
        error: None,
        timestamp: now_iso(),
    };
    let _ = query_store.add_history(entry);

    Ok(result)
}

#[tauri::command]
pub async fn list_saved_queries(app: AppHandle) -> Result<Vec<SavedQuery>, String> {
    let store = app.state::<QueryStore>();
    store.list_saved().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn save_saved_query(app: AppHandle, query: SavedQuery) -> Result<(), String> {
    let store = app.state::<QueryStore>();
    store.save_query(query).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_saved_query(app: AppHandle, id: String) -> Result<(), String> {
    let store = app.state::<QueryStore>();
    store.delete_saved(&id).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn list_history(app: AppHandle) -> Result<Vec<HistoryEntry>, String> {
    let store = app.state::<QueryStore>();
    store.list_history().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn clear_history(app: AppHandle) -> Result<(), String> {
    let store = app.state::<QueryStore>();
    store.clear_history().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_settings(app: AppHandle) -> Result<Settings, String> {
    let store = app.state::<SettingsStore>();
    store.load().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn save_settings(app: AppHandle, settings: Settings) -> Result<(), String> {
    let store = app.state::<SettingsStore>();
    store.save(&settings).map_err(|e| e.to_string())
}
