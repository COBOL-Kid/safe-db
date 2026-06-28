use anyhow::Result;
use serde_json::{Value, json};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::persist::atomic_write;
use crate::types::{CURRENT_CONNECTION_VERSION, ConnectionDef, TransportSecurityMode};

pub struct ConfigStore {
    path: PathBuf,
    lock: Mutex<()>,
}

impl ConfigStore {
    pub fn new(data_dir: PathBuf) -> Result<Self> {
        crate::persist::ensure_private_dir(&data_dir)?;
        Ok(Self {
            path: data_dir.join("connections.json"),
            lock: Mutex::new(()),
        })
    }

    pub fn list(&self) -> Result<Vec<ConnectionDef>> {
        let _guard = self.lock.lock().unwrap();
        self.load_connections_unlocked()
    }

    pub fn get(&self, id: &str) -> Result<Option<ConnectionDef>> {
        let connections = self.list()?;
        Ok(connections.into_iter().find(|c| c.id == id))
    }

    pub fn save(&self, def: ConnectionDef) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut connections = self.load_connections_unlocked()?;
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
        let mut connections = self.load_connections_unlocked()?;
        connections.retain(|c| c.id != id);
        self.write_all_unlocked(&connections)?;
        Ok(())
    }

    fn load_connections_unlocked(&self) -> Result<Vec<ConnectionDef>> {
        if !self.path.exists() {
            return Ok(vec![]);
        }
        let content = fs::read_to_string(&self.path)?;
        if content.trim().is_empty() {
            return Ok(vec![]);
        }
        let arr: Vec<Value> = serde_json::from_str(&content)?;
        let mut connections = Vec::new();
        let mut migrated_count = 0usize;
        let mut dropped = 0usize;
        for value in arr {
            let (value, upgraded) = migrate_legacy_connection(value);
            match serde_json::from_value::<ConnectionDef>(value) {
                Ok(def) => {
                    connections.push(def);
                    migrated_count += usize::from(upgraded);
                }
                Err(_) => dropped += 1,
            }
        }
        if migrated_count > 0 {
            log::info!(
                "Migrated {} legacy connection{} in {}",
                migrated_count,
                if migrated_count == 1 { "" } else { "s" },
                self.path.display(),
            );
        }
        if dropped > 0 {
            log::warn!(
                "Skipped {} unreadable connection{} in {} (preserved on disk)",
                dropped,
                if dropped == 1 { "" } else { "s" },
                self.path.display(),
            );
        }
        if migrated_count > 0 && dropped == 0 {
            let backup = self.path.with_extension("migration.bak");
            if !backup.exists() {
                atomic_write(&backup, &content)?;
            }
            self.write_all_unlocked(&connections)?;
        }
        Ok(connections)
    }

    fn write_all_unlocked(&self, connections: &[ConnectionDef]) -> Result<()> {
        let json = serde_json::to_string_pretty(connections)?;
        atomic_write(&self.path, &json)?;
        Ok(())
    }
}

/// Pre-transport-security profiles omitted `transport_security` from JSON.
/// Upgrade them to plaintext-compatible settings so local databases keep working.
fn migrate_legacy_connection(mut value: Value) -> (Value, bool) {
    let Some(obj) = value.as_object_mut() else {
        return (value, false);
    };
    if obj.contains_key("transport_security") {
        return (value, false);
    }
    obj.insert("version".to_string(), json!(CURRENT_CONNECTION_VERSION));
    obj.insert(
        "transport_security".to_string(),
        json!({
            "mode": TransportSecurityMode::Disabled,
            "legacy_implicit": true,
        }),
    );
    (value, true)
}
