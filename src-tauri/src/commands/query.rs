use tauri::{AppHandle, Manager};

use crate::config::ConfigStore;
use crate::queries::{HistoryEntry, QueryStore};
use crate::query::QuerySpec;
use crate::secrets;
use crate::settings::SettingsStore;

use super::query_core::{QueryCoreError, run_query_core};

fn now_iso() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    format!("{}", now)
}

#[tauri::command]
pub async fn get_schema(
    app: AppHandle,
    connection_id: String,
) -> Result<crate::introspect::Schema, String> {
    let config_store = app.state::<ConfigStore>();
    let def = config_store
        .get(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Connection not found".to_string())?;

    let password = secrets::password_for_definition(&def)?;

    let adapter = crate::adapters::Adapter::connect(&def, &password)
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
    force: Option<bool>,
) -> Result<crate::query::QueryResult, String> {
    let config_store = app.state::<ConfigStore>();
    let settings_store = app.state::<SettingsStore>();
    let query_store = app.state::<QueryStore>();

    let def = config_store
        .get(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Connection not found".to_string())?;

    let password = secrets::password_for_definition(&def)?;

    let settings = settings_store.load().map_err(|e| e.to_string())?;

    let adapter = crate::adapters::Adapter::connect(&def, &password)
        .await
        .map_err(|e| e.to_string())?;

    let schema = adapter.introspect().await.map_err(|e| e.to_string())?;

    let force = force.unwrap_or(false);

    let result = match run_query_core(&adapter, &def, &mut spec, &schema, &settings, force).await {
        Ok(r) => r,
        Err(QueryCoreError { message, warnings }) => {
            let entry = HistoryEntry {
                id: uuid::Uuid::new_v4().to_string(),
                connection_id: connection_id.clone(),
                connection_name: def.name.clone(),
                spec: spec.clone(),
                row_count: 0,
                warnings,
                error: Some(message.clone()),
                timestamp: now_iso(),
            };
            if let Err(e) = query_store.add_history(entry) {
                log::warn!("failed to persist query history: {e}");
            }
            return Err(message);
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
    if let Err(e) = query_store.add_history(entry) {
        log::warn!("failed to persist query history: {e}");
    }

    Ok(result)
}
