use anyhow::Result;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::persist::atomic_write;
use crate::types::ConnectionDef;

pub struct ConfigStore {
    path: PathBuf,
    lock: Mutex<()>,
}

impl ConfigStore {
    pub fn new(data_dir: PathBuf) -> Self {
        fs::create_dir_all(&data_dir).ok();
        Self {
            path: data_dir.join("connections.json"),
            lock: Mutex::new(()),
        }
    }

    pub fn list(&self) -> Result<Vec<ConnectionDef>> {
        let _guard = self.lock.lock().unwrap();
        if !self.path.exists() {
            return Ok(vec![]);
        }
        let content = fs::read_to_string(&self.path)?;
        if content.trim().is_empty() {
            return Ok(vec![]);
        }
        let connections: Vec<ConnectionDef> = serde_json::from_str(&content)?;
        Ok(connections)
    }

    pub fn get(&self, id: &str) -> Result<Option<ConnectionDef>> {
        let connections = self.list()?;
        Ok(connections.into_iter().find(|c| c.id == id))
    }

    pub fn save(&self, def: ConnectionDef) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut connections = self.read_all_unlocked()?;
        if let Some(existing) = connections.iter_mut().find(|c| c.id == def.id) {
            *existing = def;
        } else {
            connections.push(def);
        }
        self.write_all_unlocked(&connections)?;
        Ok(())
    }

    pub fn delete(&self, id: &str) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut connections = self.read_all_unlocked()?;
        connections.retain(|c| c.id != id);
        self.write_all_unlocked(&connections)?;
        Ok(())
    }

    fn read_all_unlocked(&self) -> Result<Vec<ConnectionDef>> {
        if !self.path.exists() {
            return Ok(vec![]);
        }
        let content = fs::read_to_string(&self.path)?;
        if content.trim().is_empty() {
            return Ok(vec![]);
        }
        let connections: Vec<ConnectionDef> = serde_json::from_str(&content)?;
        Ok(connections)
    }

    fn write_all_unlocked(&self, connections: &[ConnectionDef]) -> Result<()> {
        let json = serde_json::to_string_pretty(connections)?;
        atomic_write(&self.path, &json)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::Dialect;
    use tempfile::TempDir;

    fn sample(id: &str) -> ConnectionDef {
        ConnectionDef {
            id: id.to_string(),
            name: format!("Test {id}"),
            dialect: Dialect::Postgres,
            host: "localhost".to_string(),
            port: 5432,
            database: "demo".to_string(),
            username: "user".to_string(),
        }
    }

    #[test]
    fn save_upserts_by_id() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf());

        store.save(sample("c1")).unwrap();
        store.save(sample("c2")).unwrap();

        // Re-saving c1 with a new name should update in place, not append.
        let mut updated = sample("c1");
        updated.name = "Renamed".to_string();
        store.save(updated).unwrap();

        let list = store.list().unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(store.get("c1").unwrap().unwrap().name, "Renamed");
        assert_eq!(store.get("c2").unwrap().unwrap().name, "Test c2");
    }

    #[test]
    fn delete_removes_the_matching_connection() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf());
        store.save(sample("c1")).unwrap();
        store.save(sample("c2")).unwrap();

        store.delete("c1").unwrap();

        assert!(store.get("c1").unwrap().is_none());
        assert!(store.get("c2").unwrap().is_some());
    }

    #[test]
    fn get_returns_none_for_unknown_id() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf());
        store.save(sample("c1")).unwrap();
        assert!(store.get("missing").unwrap().is_none());
    }

    #[test]
    fn list_returns_empty_for_missing_or_blank_file() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf());

        assert!(store.list().unwrap().is_empty());

        fs::write(dir.path().join("connections.json"), "   \n").unwrap();
        assert!(store.list().unwrap().is_empty());
    }

    #[test]
    fn list_propagates_error_for_corrupt_json() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf());

        fs::write(dir.path().join("connections.json"), "{ this is not json").unwrap();
        assert!(store.list().is_err());
    }
}
