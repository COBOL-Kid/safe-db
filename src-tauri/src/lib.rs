mod adapters;
mod commands;
mod config;
mod introspect;
mod queries;
mod query;
mod secrets;
mod settings;
mod types;

use commands::{
    clear_history, delete_connection, delete_saved_query, get_schema, get_settings,
    list_connections, list_history, list_saved_queries, run_query, save_connection,
    save_saved_query, save_settings, test_connection,
};
use config::ConfigStore;
use queries::QueryStore;
use settings::SettingsStore;
use tauri::Manager;

fn init_keyring() -> anyhow::Result<()> {
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }

            init_keyring()?;

            let data_dir = app.path().app_data_dir()?;
            app.manage(ConfigStore::new(data_dir.clone()));
            app.manage(QueryStore::new(data_dir.clone()));
            app.manage(SettingsStore::new(data_dir));

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            test_connection,
            save_connection,
            list_connections,
            delete_connection,
            get_schema,
            run_query,
            list_saved_queries,
            save_saved_query,
            delete_saved_query,
            list_history,
            clear_history,
            get_settings,
            save_settings,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
