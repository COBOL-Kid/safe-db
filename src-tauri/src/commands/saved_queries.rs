use tauri::{AppHandle, Manager};

use crate::queries::{HistoryEntry, QueryStore, SavedQuery};

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
