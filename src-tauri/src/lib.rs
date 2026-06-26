pub mod adapters;
mod commands;
pub mod config;
pub mod introspect;
pub mod queries;
pub mod query;
pub mod secrets;
pub mod settings;
pub mod types;

use commands::{
    clear_history, delete_connection, delete_saved_query, get_schema, get_settings,
    list_connections, list_history, list_saved_queries, run_query, save_connection,
    save_saved_query, save_settings, test_connection,
};
use config::ConfigStore;
use queries::QueryStore;
use settings::SettingsStore;
use tauri::{Manager, RunEvent};

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

            secrets::init_store()?;

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
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|_app, event| {
            if matches!(event, RunEvent::Exit) {
                secrets::lock_credentials();
            }
        });
}
