use anyhow::Result;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::Value;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::query::ir::{FilterOp, QuerySpec, CURRENT_SCHEMA_VERSION};

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
            match migrate_v1_entry(v)
                .and_then(|m| serde_json::from_value::<T>(m).ok())
            {
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

    fn write_json<T: Serialize>(&self, path: &PathBuf, data: &T) -> Result<()> {
        let json = serde_json::to_string_pretty(data)?;
        fs::write(path, json)?;
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
        let value = v1.value.map(|text| {
            serde_json::json!({ "Single": { "kind": "Text", "text": text } })
        });
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
