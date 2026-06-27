use anyhow::Result;
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::Value;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::persist::atomic_write;
use crate::query::ir::{CURRENT_SCHEMA_VERSION, FilterOp, QuerySpec};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SavedQuery {
    pub id: String,
    pub name: String,
    pub connection_id: String,
    pub spec: QuerySpec,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub id: String,
    pub connection_id: String,
    pub connection_name: String,
    pub spec: QuerySpec,
    pub row_count: usize,
    pub warnings: Vec<String>,
    pub error: Option<String>,
    pub timestamp: String,
}

pub struct QueryStore {
    saved_path: PathBuf,
    history_path: PathBuf,
    lock: Mutex<()>,
    max_history: usize,
}

impl QueryStore {
    pub fn new(data_dir: PathBuf) -> Self {
        fs::create_dir_all(&data_dir).ok();
        Self {
            saved_path: data_dir.join("saved_queries.json"),
            history_path: data_dir.join("query_history.json"),
            lock: Mutex::new(()),
            max_history: 100,
        }
    }

    pub fn list_saved(&self) -> Result<Vec<SavedQuery>> {
        let _guard = self.lock.lock().unwrap();
        let valid = self.read_valid::<SavedQuery>(&self.saved_path)?;
        Ok(valid)
    }

    pub fn save_query(&self, query: SavedQuery) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut queries = self.read_valid::<SavedQuery>(&self.saved_path)?;
        if let Some(existing) = queries.iter_mut().find(|q| q.id == query.id) {
            *existing = query;
        } else {
            queries.push(query);
        }
        self.write_json(&self.saved_path, &queries)?;
        Ok(())
    }

    pub fn delete_saved(&self, id: &str) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut queries = self.read_valid::<SavedQuery>(&self.saved_path)?;
        queries.retain(|q| q.id != id);
        self.write_json(&self.saved_path, &queries)?;
        Ok(())
    }

    pub fn list_history(&self) -> Result<Vec<HistoryEntry>> {
        let _guard = self.lock.lock().unwrap();
        let valid = self.read_valid::<HistoryEntry>(&self.history_path)?;
        Ok(valid)
    }

    pub fn add_history(&self, entry: HistoryEntry) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut history = self.read_valid::<HistoryEntry>(&self.history_path)?;
        history.insert(0, entry);
        if history.len() > self.max_history {
            history.truncate(self.max_history);
        }
        self.write_json(&self.history_path, &history)?;
        Ok(())
    }

    pub fn clear_history(&self) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        self.write_json(&self.history_path, &Vec::<HistoryEntry>::new())?;
        Ok(())
    }

    fn read_valid<T: DeserializeOwned + Serialize>(&self, path: &PathBuf) -> Result<Vec<T>> {
        if !path.exists() {
            return Ok(Vec::new());
        }
        let content = fs::read_to_string(path)?;
        if content.trim().is_empty() {
            return Ok(Vec::new());
        }
        let arr: Vec<Value> = serde_json::from_str(&content)?;
        let mut valid: Vec<T> = Vec::new();
        let mut migrated_count = 0usize;
        let mut dropped = 0usize;
        for v in arr {
            if let Ok(item) = serde_json::from_value::<T>(v.clone()) {
                valid.push(item);
                continue;
            }
            // Not valid v2 — attempt a v1→v2 migration.
            match migrate_v1_entry(v).and_then(|m| serde_json::from_value::<T>(m).ok()) {
                Some(item) => {
                    valid.push(item);
                    migrated_count += 1;
                }
                None => dropped += 1,
            }
        }
        if migrated_count > 0 {
            log::info!(
                "Migrated {} v1 entr{} from {} to schema v{}",
                migrated_count,
                if migrated_count == 1 { "y" } else { "ies" },
                path.display(),
                CURRENT_SCHEMA_VERSION,
            );
        }
        if dropped > 0 {
            log::warn!(
                "Skipped {} unreadable entr{} in {} (preserved on disk)",
                dropped,
                if dropped == 1 { "y" } else { "ies" },
                path.display(),
            );
        }
        // Persist migrations only when nothing was dropped, so corrupt or
        // unreadable entries are never silently overwritten and lost. A backup
        // of the original file is kept the first time a migration is written.
        if migrated_count > 0 && dropped == 0 {
            let backup = path.with_extension("v1.bak");
            if !backup.exists() {
                let _ = fs::write(&backup, &content);
            }
            let _ = self.write_json(path, &valid);
        }
        Ok(valid)
    }

    fn write_json<T: Serialize>(&self, path: &std::path::Path, data: &T) -> Result<()> {
        let json = serde_json::to_string_pretty(data)?;
        atomic_write(path, &json)?;
        Ok(())
    }
}

/// Legacy v1 filter spec: `value` was a plain `Option<String>` instead of the
/// typed `FilterValue` enum used by the current IR.
#[derive(Debug, Deserialize)]
struct FilterSpecV1 {
    table_alias: String,
    column: String,
    op: FilterOp,
    #[serde(default)]
    value: Option<String>,
}

/// Attempt to migrate a legacy v1 entry (where `spec.filters` is a JSON array
/// of `FilterSpec` with string values) into the current v2 shape (where
/// `spec.filters` is a `FilterGroup` object with typed `FilterValue`s).
///
/// Returns `Some(migrated Value)` when the input looks like a v1 entry, or
/// `None` when it doesn't (so the caller can treat it as a plain unreadable
/// entry rather than silently discarding it).
fn migrate_v1_entry(v: Value) -> Option<Value> {
    let mut obj = v.as_object()?.clone();
    let spec = obj.get("spec")?.as_object()?.clone();
    // v1 stores `filters` as an array; v2 stores it as an object (FilterGroup).
    let filters_arr = spec.get("filters")?.as_array()?.clone();

    let mut children: Vec<Value> = Vec::with_capacity(filters_arr.len());
    for f in filters_arr {
        let v1: FilterSpecV1 = serde_json::from_value(f).ok()?;
        let value = v1
            .value
            .map(|text| serde_json::json!({ "Single": { "kind": "Text", "text": text } }));
        let op = serde_json::to_value(v1.op).ok()?;
        children.push(serde_json::json!({
            "Leaf": {
                "table_alias": v1.table_alias,
                "column": v1.column,
                "op": op,
                "value": value,
            }
        }));
    }

    let mut new_spec = spec.clone();
    new_spec.insert(
        "filters".to_string(),
        serde_json::json!({ "connector": "And", "children": children }),
    );
    new_spec.insert(
        "schema_version".to_string(),
        serde_json::json!(CURRENT_SCHEMA_VERSION),
    );

    obj.insert("spec".to_string(), Value::Object(new_spec));
    Some(Value::Object(obj))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::ir::{FilterNode, FilterValue, GroupConnector, LiteralKind};
    use serde_json::json;
    use tempfile::TempDir;

    fn empty_spec() -> QuerySpec {
        QuerySpec {
            tables: vec![],
            columns: vec![],
            joins: vec![],
            filters: Default::default(),
            limit: 100,
            schema_version: CURRENT_SCHEMA_VERSION,
            connector_overrides: Default::default(),
        }
    }

    fn saved(id: &str) -> SavedQuery {
        SavedQuery {
            id: id.to_string(),
            name: format!("Saved {id}"),
            connection_id: "c1".to_string(),
            spec: empty_spec(),
            created_at: "1700000000".to_string(),
        }
    }

    #[test]
    fn saved_helper_is_consistent() {
        // The helper is the single source of truth for SavedQuery shape in
        // the inline tests; pin it so any future change to the struct trips
        // here first.
        let s = saved("q1");
        assert_eq!(s.id, "q1");
        assert_eq!(s.name, "Saved q1");
        assert_eq!(s.connection_id, "c1");
        assert_eq!(s.created_at, "1700000000");
    }

    fn history(id: &str) -> HistoryEntry {
        HistoryEntry {
            id: id.to_string(),
            connection_id: "c1".to_string(),
            connection_name: "Test DB".to_string(),
            spec: empty_spec(),
            row_count: 0,
            warnings: vec![],
            error: None,
            timestamp: "1700000000".to_string(),
        }
    }

    #[test]
    fn add_history_creates_file_and_prepends_entries() {
        let dir = TempDir::new().unwrap();
        let store = QueryStore::new(dir.path().to_path_buf());

        // First add on a fresh directory should create the file.
        assert!(!dir.path().join("query_history.json").exists());
        store.add_history(history("h1")).unwrap();
        assert!(dir.path().join("query_history.json").exists());

        store.add_history(history("h2")).unwrap();

        let listed = store.list_history().unwrap();
        // Newer entries come first.
        assert_eq!(listed.first().unwrap().id, "h2");
        assert_eq!(listed.last().unwrap().id, "h1");
    }

    #[test]
    fn add_history_caps_at_max_history_with_newest_first() {
        let dir = TempDir::new().unwrap();
        let store = QueryStore::new(dir.path().to_path_buf());

        for i in 0..105 {
            store.add_history(history(&format!("h{i}"))).unwrap();
        }

        let listed = store.list_history().unwrap();
        assert_eq!(listed.len(), 100);
        // h104 is the most recent.
        assert_eq!(listed.first().unwrap().id, "h104");
        // h0 was dropped.
        assert!(listed.iter().all(|e| e.id != "h0"));
    }

    #[test]
    fn clear_history_empties_the_list_and_writes_empty_array() {
        let dir = TempDir::new().unwrap();
        let store = QueryStore::new(dir.path().to_path_buf());
        store.add_history(history("h1")).unwrap();
        store.add_history(history("h2")).unwrap();

        store.clear_history().unwrap();

        assert!(store.list_history().unwrap().is_empty());
        let content = fs::read_to_string(dir.path().join("query_history.json")).unwrap();
        let parsed: Vec<HistoryEntry> = serde_json::from_str(&content).unwrap();
        assert!(parsed.is_empty());
    }

    #[test]
    fn migrate_v1_entry_returns_none_for_non_v1_shaped_input() {
        // Empty object, non-object, and objects without a `spec.filters` array
        // should all return None so the caller preserves them on disk.
        assert!(migrate_v1_entry(json!({})).is_none());
        assert!(migrate_v1_entry(json!([])).is_none());
        assert!(migrate_v1_entry(json!({ "spec": {} })).is_none());
        assert!(
            migrate_v1_entry(
                json!({ "spec": { "filters": { "connector": "And", "children": [] } } })
            )
            .is_none()
        );
    }

    #[test]
    fn migrate_v1_entry_converts_filters_array_into_group() {
        let v1 = json!({
            "id": "q1",
            "name": "old",
            "connection_id": "c1",
            "spec": {
                "tables": [],
                "columns": [],
                "joins": [],
                "filters": [
                    { "table_alias": "t0", "column": "age", "op": "Gt", "value": "21" }
                ],
                "limit": 100
            },
            "created_at": "1700000000"
        });

        let migrated = migrate_v1_entry(v1).expect("looks like v1");
        let parsed: SavedQuery = serde_json::from_value(migrated).expect("parses as v2");

        assert_eq!(parsed.spec.schema_version, CURRENT_SCHEMA_VERSION);
        assert_eq!(parsed.spec.filters.connector, GroupConnector::And);
        assert_eq!(parsed.spec.filters.children.len(), 1);

        // The single leaf should be a Gt on age with a Text literal "21".
        let leaf = &parsed.spec.filters.children[0];
        let FilterNode::Leaf(spec) = leaf else {
            panic!("expected Leaf")
        };
        assert_eq!(spec.column, "age");
        assert_eq!(spec.op, FilterOp::Gt);
        let FilterValue::Single(lit) = spec.value.as_ref().expect("value present") else {
            panic!("expected Single")
        };
        assert_eq!(lit.kind, LiteralKind::Text);
        assert_eq!(lit.text, "21");
    }
}
