use safe_db_lib::config::ConfigStore;
use safe_db_lib::queries::{HistoryEntry, QueryStore, SavedQuery};
use safe_db_lib::query::ir::{QuerySpec, TableRef};
use safe_db_lib::settings::SettingsStore;
use safe_db_lib::types::{ConnectionDef, Dialect};
use tempfile::TempDir;

fn sample_connection(id: &str) -> ConnectionDef {
    ConnectionDef {
        id: id.into(),
        name: format!("Conn {id}"),
        dialect: Dialect::Postgres,
        host: "localhost".into(),
        port: 5432,
        database: "demo".into(),
        username: "readonly".into(),
    }
}

fn sample_spec() -> QuerySpec {
    QuerySpec {
        tables: vec![TableRef {
            schema: "public".into(),
            name: "users".into(),
            alias: "t0".into(),
        }],
        columns: vec![],
        joins: vec![],
        filters: vec![],
        limit: 100,
    }
}

#[test]
fn config_store_round_trips_connections() {
    let dir = TempDir::new().unwrap();
    let store = ConfigStore::new(dir.path().to_path_buf());

    assert!(store.list().unwrap().is_empty());

    let conn = sample_connection("c1");
    store.save(conn.clone()).unwrap();
    let loaded = store.get("c1").unwrap().unwrap();
    assert_eq!(loaded.name, "Conn c1");

    let mut updated = conn.clone();
    updated.name = "Updated".into();
    store.save(updated).unwrap();
    assert_eq!(store.get("c1").unwrap().unwrap().name, "Updated");

    store.delete("c1").unwrap();
    assert!(store.get("c1").unwrap().is_none());
}

#[test]
fn config_store_handles_missing_and_empty_files() {
    let dir = TempDir::new().unwrap();
    let store = ConfigStore::new(dir.path().to_path_buf());
    assert!(store.list().unwrap().is_empty());

    std::fs::write(dir.path().join("connections.json"), "   ").unwrap();
    assert!(store.list().unwrap().is_empty());
}

#[test]
fn query_store_saved_queries_upsert_and_delete() {
    let dir = TempDir::new().unwrap();
    let store = QueryStore::new(dir.path().to_path_buf());

    let saved = SavedQuery {
        id: "q1".into(),
        name: "Users".into(),
        connection_id: "c1".into(),
        spec: sample_spec(),
        created_at: "1".into(),
    };
    store.save_query(saved.clone()).unwrap();

    let mut updated = saved.clone();
    updated.name = "All Users".into();
    store.save_query(updated).unwrap();
    assert_eq!(store.list_saved().unwrap()[0].name, "All Users");

    store.delete_saved("q1").unwrap();
    assert!(store.list_saved().unwrap().is_empty());
}

#[test]
fn query_store_history_prepends_and_caps_at_100() {
    let dir = TempDir::new().unwrap();
    let store = QueryStore::new(dir.path().to_path_buf());

    for i in 0..105 {
        store
            .add_history(HistoryEntry {
                id: format!("h{i}"),
                connection_id: "c1".into(),
                connection_name: "Conn".into(),
                spec: sample_spec(),
                row_count: i,
                warnings: vec![],
                error: None,
                timestamp: i.to_string(),
            })
            .unwrap();
    }

    let history = store.list_history().unwrap();
    assert_eq!(history.len(), 100);
    assert_eq!(history[0].id, "h104");
    assert_eq!(history[99].id, "h5");
}

#[test]
fn query_store_clear_history() {
    let dir = TempDir::new().unwrap();
    let store = QueryStore::new(dir.path().to_path_buf());
    store
        .add_history(HistoryEntry {
            id: "h1".into(),
            connection_id: "c1".into(),
            connection_name: "Conn".into(),
            spec: sample_spec(),
            row_count: 1,
            warnings: vec![],
            error: None,
            timestamp: "1".into(),
        })
        .unwrap();

    store.clear_history().unwrap();
    assert!(store.list_history().unwrap().is_empty());
}

#[test]
fn settings_store_defaults_and_round_trip() {
    let dir = TempDir::new().unwrap();
    let store = SettingsStore::new(dir.path().to_path_buf());

    let defaults = store.load().unwrap();
    assert_eq!(defaults.theme, "light");
    assert_eq!(defaults.explain_cost_threshold, 100_000.0);
    assert!(defaults.blocked_schemas.is_empty());

    let partial_json = r#"{"blocked_schemas":["audit"]}"#;
    std::fs::write(dir.path().join("settings.json"), partial_json).unwrap();
    let loaded = store.load().unwrap();
    assert_eq!(loaded.blocked_schemas, vec!["audit"]);
    assert_eq!(loaded.theme, "light");
    assert_eq!(loaded.explain_cost_threshold, 100_000.0);
}
