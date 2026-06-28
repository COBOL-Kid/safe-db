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
            "insecure_acknowledged": false,
            "legacy_implicit": true,
        }),
    );
    (value, true)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::Dialect;
    use tempfile::TempDir;

    fn sample(id: &str) -> ConnectionDef {
        ConnectionDef {
            version: crate::types::CURRENT_CONNECTION_VERSION,
            id: id.to_string(),
            name: format!("Test {id}"),
            dialect: Dialect::Postgres,
            host: "localhost".to_string(),
            port: 5432,
            database: "demo".to_string(),
            username: "user".to_string(),
            transport_security: crate::types::TransportSecurity::default(),
        }
    }

    #[test]
    fn save_upserts_by_id() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();

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
        let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();
        store.save(sample("c1")).unwrap();
        store.save(sample("c2")).unwrap();

        store.delete("c1").unwrap();

        assert!(store.get("c1").unwrap().is_none());
        assert!(store.get("c2").unwrap().is_some());
    }

    #[test]
    fn get_returns_none_for_unknown_id() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();
        store.save(sample("c1")).unwrap();
        assert!(store.get("missing").unwrap().is_none());
    }

    #[test]
    fn list_returns_empty_for_missing_or_blank_file() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();

        assert!(store.list().unwrap().is_empty());

        fs::write(dir.path().join("connections.json"), "   \n").unwrap();
        assert!(store.list().unwrap().is_empty());
    }

    #[test]
    fn list_propagates_error_for_corrupt_json() {
        let dir = TempDir::new().unwrap();
        let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();

        fs::write(dir.path().join("connections.json"), "{ this is not json").unwrap();
        assert!(store.list().is_err());
    }

    #[test]
    fn migrate_legacy_connection_without_transport_security() {
        let legacy = json!({
            "id": "legacy-1",
            "name": "Local MySQL",
            "dialect": "MySql",
            "host": "localhost",
            "port": 3306,
            "database": "safedb_test",
            "username": "root"
        });
        let (migrated, upgraded) = migrate_legacy_connection(legacy);
        assert!(upgraded);
        let def: ConnectionDef = serde_json::from_value(migrated).unwrap();
        assert_eq!(def.version, CURRENT_CONNECTION_VERSION);
        assert_eq!(def.transport_security.mode, TransportSecurityMode::Disabled);
        assert!(!def.transport_security.insecure_acknowledged);
        assert!(def.transport_security.legacy_implicit);
    }

    #[test]
    fn migrate_legacy_connection_leaves_explicit_transport_security() {
        let modern = json!({
            "id": "modern-1",
            "name": "Prod PG",
            "dialect": "Postgres",
            "host": "db.example.com",
            "port": 5432,
            "database": "app",
            "username": "reader",
            "transport_security": {
                "mode": "VerifyIdentity",
                "insecure_acknowledged": false
            }
        });
        let (value, upgraded) = migrate_legacy_connection(modern.clone());
        assert!(!upgraded);
        assert_eq!(value, modern);
    }

    #[test]
    fn list_migrates_legacy_json_on_disk() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("connections.json");
        let legacy = json!([{
            "id": "legacy-1",
            "name": "Local MySQL",
            "dialect": "MySql",
            "host": "localhost",
            "port": 3306,
            "database": "safedb_test",
            "username": "root"
        }]);
        fs::write(&path, serde_json::to_string_pretty(&legacy).unwrap()).unwrap();

        let store = ConfigStore::new(dir.path().to_path_buf()).unwrap();
        let list = store.list().unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(
            list[0].transport_security.mode,
            TransportSecurityMode::Disabled
        );
        assert!(list[0].transport_security.legacy_implicit);

        let backup = dir.path().join("connections.migration.bak");
        assert!(backup.exists());

        let on_disk: Vec<ConnectionDef> =
            serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(
            on_disk[0].transport_security.mode,
            TransportSecurityMode::Disabled
        );
        assert!(on_disk[0].transport_security.legacy_implicit);
    }
}
