use std::collections::BTreeMap;

use safe_db_lib::config::ConfigStore;
use safe_db_lib::queries::{HistoryEntry, QueryStore, SavedQuery};
use safe_db_lib::query::ir::{
    CURRENT_SCHEMA_VERSION, FilterGroup, FilterNode, FilterOp, FilterValue, GroupConnector,
    LiteralKind, QuerySpec, TableRef,
};
use safe_db_lib::settings::{Settings, SettingsStore, normalize_settings};
use safe_db_lib::types::{CURRENT_CONNECTION_VERSION, ConnectionDef, Dialect, TransportSecurity};
use tempfile::TempDir;

fn sample_connection(id: &str) -> ConnectionDef {
    ConnectionDef {
        version: CURRENT_CONNECTION_VERSION,
        id: id.into(),
        name: format!("Conn {id}"),
        dialect: Dialect::Postgres,
        host: "localhost".into(),
        port: 5432,
        database: "demo".into(),
        username: "readonly".into(),
        transport_security: TransportSecurity::default(),
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
        filters: FilterGroup::default(),
        limit: 100,
        connector_overrides: BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
    }
}

#[test]
fn config_store_round_trips_connections() {
    let dir = TempDir::new().unwrap();
    let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();

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
    let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();
    assert!(store.list().unwrap().is_empty());

    std::fs::write(dir.path().join("connections.json"), "   ").unwrap();
    assert!(store.list().unwrap().is_empty());
}

#[test]
fn query_store_saved_queries_upsert_and_delete() {
    let dir = TempDir::new().unwrap();
    let store = QueryStore::new(dir.path().to_path_buf()).unwrap();

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
    let store = QueryStore::new(dir.path().to_path_buf()).unwrap();

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
    let store = QueryStore::new(dir.path().to_path_buf()).unwrap();
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
    let store = SettingsStore::new(dir.path().to_path_buf()).unwrap();

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

#[test]
fn settings_store_save_round_trips_non_default_values() {
    let dir = TempDir::new().unwrap();
    let store = SettingsStore::new(dir.path().to_path_buf()).unwrap();

    let saved = Settings {
        blocked_schemas: vec!["pg_catalog".to_string(), "information_schema".to_string()],
        explain_cost_threshold: 42.5,
        explain_cost_thresholds: Default::default(),
        theme: "dark".to_string(),
    };
    store.save(&saved).unwrap();

    let loaded = store.load().unwrap();
    assert_eq!(loaded.blocked_schemas, saved.blocked_schemas);
    assert_eq!(loaded.explain_cost_threshold, saved.explain_cost_threshold);
    assert_eq!(loaded.theme, saved.theme);
}

#[test]
fn settings_store_load_returns_defaults_for_missing_or_empty_file() {
    let dir = TempDir::new().unwrap();
    let store = SettingsStore::new(dir.path().to_path_buf()).unwrap();

    let loaded = store.load().unwrap();
    assert_eq!(loaded.explain_cost_threshold, 100_000.0);
    assert_eq!(loaded.theme, "light");
    assert!(loaded.blocked_schemas.is_empty());

    std::fs::write(dir.path().join("settings.json"), "   \n").unwrap();
    let loaded = store.load().unwrap();
    assert_eq!(loaded.explain_cost_threshold, 100_000.0);
    assert_eq!(loaded.theme, "light");
}

#[test]
fn settings_store_load_propagates_corrupt_json_errors() {
    let dir = TempDir::new().unwrap();
    let store = SettingsStore::new(dir.path().to_path_buf()).unwrap();

    std::fs::write(dir.path().join("settings.json"), "{ this is not json").unwrap();
    assert!(store.load().is_err());
}

#[test]
fn settings_store_save_defaults_round_trips() {
    let dir = TempDir::new().unwrap();
    let store = SettingsStore::new(dir.path().to_path_buf()).unwrap();

    store.save(&Settings::default()).unwrap();
    let loaded = store.load().unwrap();
    assert_eq!(loaded.explain_cost_threshold, 100_000.0);
    assert_eq!(loaded.theme, "light");
    assert!(loaded.blocked_schemas.is_empty());
}

#[test]
fn normalize_settings_lowercases_blocked_schemas_and_clamps_threshold() {
    let mut settings = Settings {
        blocked_schemas: vec!["Audit".to_string(), "audit".to_string()],
        explain_cost_threshold: 0.5,
        explain_cost_thresholds: Default::default(),
        theme: "dark".to_string(),
    };

    normalize_settings(&mut settings);

    assert_eq!(settings.blocked_schemas, vec!["audit".to_string()]);
    assert_eq!(settings.explain_cost_threshold, 1.0);
    assert_eq!(settings.theme, "dark");
}

const V1_SAVED_QUERIES: &str = r#"[
  {
    "id": "q1",
    "name": "Old Users",
    "connection_id": "c1",
    "spec": {
      "tables": [{"schema": "public", "name": "users", "alias": "t0"}],
      "columns": [],
      "joins": [],
      "filters": [
        {"table_alias": "t0", "column": "age", "op": "Gt", "value": "21"},
        {"table_alias": "t0", "column": "deleted_at", "op": "IsNull", "value": null}
      ],
      "limit": 50
    },
    "created_at": "1"
  }
]"#;

#[test]
fn query_store_migrates_v1_saved_queries() {
    let dir = TempDir::new().unwrap();
    std::fs::write(dir.path().join("saved_queries.json"), V1_SAVED_QUERIES).unwrap();

    let store = QueryStore::new(dir.path().to_path_buf()).unwrap();
    let saved = store.list_saved().unwrap();
    assert_eq!(saved.len(), 1);

    let spec = &saved[0].spec;
    assert_eq!(spec.schema_version, CURRENT_SCHEMA_VERSION);
    assert_eq!(spec.limit, 50);
    assert_eq!(spec.filters.connector, GroupConnector::And);
    assert_eq!(spec.filters.children.len(), 2);

    // First leaf: age > 21 (string value migrated to a typed Single Text literal).
    let leaf0 = match &spec.filters.children[0] {
        FilterNode::Leaf(f) => f,
        _ => panic!("expected leaf"),
    };
    assert_eq!(leaf0.table_alias, "t0");
    assert_eq!(leaf0.column, "age");
    assert_eq!(leaf0.op, FilterOp::Gt);
    match &leaf0.value {
        Some(FilterValue::Single(lit)) => {
            assert_eq!(lit.kind, LiteralKind::Text);
            assert_eq!(lit.text, "21");
        }
        other => panic!("expected Single Text, got {other:?}"),
    }

    // Second leaf: deleted_at IS NULL (null value stays None).
    let leaf1 = match &spec.filters.children[1] {
        FilterNode::Leaf(f) => f,
        _ => panic!("expected leaf"),
    };
    assert_eq!(leaf1.op, FilterOp::IsNull);
    assert!(leaf1.value.is_none());

    // The file should have been rewritten to the current shape (filters is now
    // an object, not an array) and a migration backup retained.
    let rewritten = std::fs::read_to_string(dir.path().join("saved_queries.json")).unwrap();
    assert!(
        rewritten.contains("\"connector\""),
        "expected rewritten filters, got: {rewritten}"
    );
    assert!(
        !rewritten.contains("\"filters\":["),
        "v1 filters array should be gone after migration: {rewritten}"
    );
    assert!(
        dir.path().join("saved_queries.migration.bak").exists(),
        "migration backup should be retained"
    );

    // Re-reading yields the same data without re-migrating.
    let again = store.list_saved().unwrap();
    assert_eq!(again.len(), 1);
    assert_eq!(again[0].spec.filters.children.len(), 2);
}

#[test]
fn query_store_preserves_unreadable_entries_on_disk() {
    let dir = TempDir::new().unwrap();
    // One valid v2 entry followed by an unreadable (non-v1) entry.
    let v2_spec = sample_spec();
    let valid = serde_json::json!([{
        "id": "q1",
        "name": "Good",
        "connection_id": "c1",
        "spec": v2_spec,
        "created_at": "1"
    }, {
        "garbage": true
    }]);
    let raw = serde_json::to_string_pretty(&valid).unwrap();
    std::fs::write(dir.path().join("saved_queries.json"), raw).unwrap();

    let store = QueryStore::new(dir.path().to_path_buf()).unwrap();
    let saved = store.list_saved().unwrap();
    // Only the valid entry is returned...
    assert_eq!(saved.len(), 1);
    assert_eq!(saved[0].id, "q1");

    // ...but the unreadable entry is preserved on disk (file not rewritten).
    let on_disk = std::fs::read_to_string(dir.path().join("saved_queries.json")).unwrap();
    assert!(
        on_disk.contains("\"garbage\""),
        "unreadable entry should be preserved on disk, got: {on_disk}"
    );
    assert!(
        !dir.path().join("saved_queries.migration.bak").exists(),
        "no backup should be written when nothing was migrated"
    );
}
