use tauri::{AppHandle, Manager};

use crate::settings::{Settings, SettingsStore};

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
