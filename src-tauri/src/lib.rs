mod adapters;
mod commands;
mod config;
mod introspect;
mod query;
mod secrets;
mod types;

use commands::{
    delete_connection, get_schema, list_connections, run_query, save_connection, test_connection,
};
use config::ConfigStore;
use tauri::Manager;

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

            let data_dir = app.path().app_data_dir()?;
            let config_store = ConfigStore::new(data_dir);
            app.manage(config_store);

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            test_connection,
            save_connection,
            list_connections,
            delete_connection,
            get_schema,
            run_query,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
