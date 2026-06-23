use anyhow::Result;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

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
        fs::write(&self.path, json)?;
        Ok(())
    }
}
