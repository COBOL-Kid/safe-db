use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::query::ir::QuerySpec;

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
        self.read_json::<Vec<SavedQuery>>(&self.saved_path)
            .map(|v| v.unwrap_or_default())
    }

    pub fn save_query(&self, query: SavedQuery) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut queries = self
            .read_json::<Vec<SavedQuery>>(&self.saved_path)?
            .unwrap_or_default();
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
        let mut queries = self
            .read_json::<Vec<SavedQuery>>(&self.saved_path)?
            .unwrap_or_default();
        queries.retain(|q| q.id != id);
        self.write_json(&self.saved_path, &queries)?;
        Ok(())
    }

    pub fn list_history(&self) -> Result<Vec<HistoryEntry>> {
        let _guard = self.lock.lock().unwrap();
        self.read_json::<Vec<HistoryEntry>>(&self.history_path)
            .map(|v| v.unwrap_or_default())
    }

    pub fn add_history(&self, entry: HistoryEntry) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut history = self
            .read_json::<Vec<HistoryEntry>>(&self.history_path)?
            .unwrap_or_default();
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

    fn read_json<T: for<'de> Deserialize<'de>>(&self, path: &PathBuf) -> Result<Option<T>> {
        if !path.exists() {
            return Ok(None);
        }
        let content = fs::read_to_string(path)?;
        if content.trim().is_empty() {
            return Ok(None);
        }
        let data: T = serde_json::from_str(&content)?;
        Ok(Some(data))
    }

    fn write_json<T: Serialize>(&self, path: &PathBuf, data: &T) -> Result<()> {
        let json = serde_json::to_string_pretty(data)?;
        fs::write(path, json)?;
        Ok(())
    }
}
