pub mod adapters;
mod commands;
pub mod config;
pub mod introspect;
mod persist;
pub mod queries;
pub mod query;
pub mod secrets;
pub mod settings;
pub mod types;

use commands::{
    clear_history, create_connection, delete_connection, delete_saved_query, get_schema,
    get_settings, list_connections, list_history, list_saved_queries, lock_credentials, run_query,
    save_connection, save_saved_query, save_settings, test_connection, update_connection,
};
use config::ConfigStore;
use queries::QueryStore;
use settings::SettingsStore;
use tauri::{Manager, RunEvent};

#[cfg(any(test, feature = "test-helpers"))]
pub mod test_support {
    pub use crate::commands::{COST_GUARD_PREFIX, QueryCoreError, QueryRunner, run_query_core};
    pub use crate::persist::atomic_write;

    pub fn classify_mysql_type(type_name: &str) -> &'static str {
        match crate::adapters::mysql::classify_mysql_type(type_name) {
            crate::adapters::mysql::MysqlTypeKind::SmallInt => "SmallInt",
            crate::adapters::mysql::MysqlTypeKind::Int => "Int",
            crate::adapters::mysql::MysqlTypeKind::BigInt => "BigInt",
            crate::adapters::mysql::MysqlTypeKind::Float => "Float",
            crate::adapters::mysql::MysqlTypeKind::Double => "Double",
            crate::adapters::mysql::MysqlTypeKind::Decimal => "Decimal",
            crate::adapters::mysql::MysqlTypeKind::Date => "Date",
            crate::adapters::mysql::MysqlTypeKind::DateTime => "DateTime",
            crate::adapters::mysql::MysqlTypeKind::Json => "Json",
            crate::adapters::mysql::MysqlTypeKind::Binary => "Binary",
            crate::adapters::mysql::MysqlTypeKind::Text => "Text",
        }
    }

    pub fn classify_pg_type(type_name: &str) -> &'static str {
        match crate::adapters::pg::classify_pg_type(type_name) {
            crate::adapters::pg::PgTypeKind::Bool => "Bool",
            crate::adapters::pg::PgTypeKind::SmallInt => "SmallInt",
            crate::adapters::pg::PgTypeKind::Int => "Int",
            crate::adapters::pg::PgTypeKind::BigInt => "BigInt",
            crate::adapters::pg::PgTypeKind::Float => "Float",
            crate::adapters::pg::PgTypeKind::Double => "Double",
            crate::adapters::pg::PgTypeKind::Decimal => "Decimal",
            crate::adapters::pg::PgTypeKind::Date => "Date",
            crate::adapters::pg::PgTypeKind::DateTime => "DateTime",
            crate::adapters::pg::PgTypeKind::DateTimeTz => "DateTimeTz",
            crate::adapters::pg::PgTypeKind::Uuid => "Uuid",
            crate::adapters::pg::PgTypeKind::Json => "Json",
            crate::adapters::pg::PgTypeKind::Binary => "Binary",
            crate::adapters::pg::PgTypeKind::Text => "Text",
        }
    }

    pub fn parse_showplan_cost(xml: &str) -> Option<f64> {
        crate::adapters::mssql::parse_showplan_cost(xml)
    }

    #[cfg(feature = "oracle")]
    pub fn encode_oracle_connect_query_value(value: &str) -> String {
        crate::adapters::oracle::encode_connect_query_value(value)
    }

    #[cfg(feature = "oracle")]
    pub fn validate_oracle_connect_field(field: &str, label: &str) -> anyhow::Result<()> {
        crate::adapters::oracle::validate_connect_field(field, label)
    }
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

            secrets::init_store()?;

            let data_dir = app.path().app_data_dir()?;
            app.manage(ConfigStore::new(data_dir.clone())?);
            app.manage(QueryStore::new(data_dir.clone())?);
            app.manage(SettingsStore::new(data_dir)?);

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            test_connection,
            save_connection,
            create_connection,
            update_connection,
            list_connections,
            delete_connection,
            lock_credentials,
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
