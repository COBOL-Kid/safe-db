use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use crate::persist::atomic_write;
use crate::types::Dialect;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub blocked_schemas: Vec<String>,
    #[serde(default = "default_cost_threshold")]
    pub explain_cost_threshold: f64,
    #[serde(default)]
    pub explain_cost_thresholds: BTreeMap<Dialect, f64>,
    #[serde(default = "default_theme")]
    pub theme: String,
}

fn default_cost_threshold() -> f64 {
    100_000.0
}

fn default_theme() -> String {
    "light".to_string()
}

fn default_dialect_thresholds() -> BTreeMap<Dialect, f64> {
    [
        Dialect::Postgres,
        Dialect::MySql,
        Dialect::Mssql,
        Dialect::Oracle,
    ]
    .into_iter()
    .map(|dialect| (dialect, default_cost_threshold()))
    .collect()
}

impl Settings {
    pub fn cost_threshold(&self, dialect: Dialect) -> f64 {
        self.explain_cost_thresholds
            .get(&dialect)
            .copied()
            .unwrap_or(self.explain_cost_threshold)
    }
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            blocked_schemas: Vec::new(),
            explain_cost_threshold: default_cost_threshold(),
            explain_cost_thresholds: default_dialect_thresholds(),
            theme: default_theme(),
        }
    }
}

/// Normalize user-supplied settings before persistence.
pub fn normalize_settings(settings: &mut Settings) {
    let mut seen = HashSet::new();
    settings.blocked_schemas = settings
        .blocked_schemas
        .iter()
        .map(|s| s.trim().to_lowercase())
        .filter(|s| !s.is_empty())
        .filter(|s| seen.insert(s.clone()))
        .collect();

    settings.explain_cost_threshold = settings.explain_cost_threshold.clamp(1.0, 10_000_000.0);
    if settings.explain_cost_thresholds.is_empty() {
        settings.explain_cost_thresholds = [
            Dialect::Postgres,
            Dialect::MySql,
            Dialect::Mssql,
            Dialect::Oracle,
        ]
        .into_iter()
        .map(|dialect| (dialect, settings.explain_cost_threshold))
        .collect();
    }
    for threshold in settings.explain_cost_thresholds.values_mut() {
        *threshold = threshold.clamp(1.0, 10_000_000.0);
    }

    if settings.theme != "dark" {
        settings.theme = "light".to_string();
    }
}

pub struct SettingsStore {
    path: PathBuf,
    lock: Mutex<()>,
}

impl SettingsStore {
    pub fn new(data_dir: PathBuf) -> Result<Self> {
        crate::persist::ensure_private_dir(&data_dir)?;
        Ok(Self {
            path: data_dir.join("settings.json"),
            lock: Mutex::new(()),
        })
    }

    pub fn load(&self) -> Result<Settings> {
        let _guard = self.lock.lock().unwrap();
        if !self.path.exists() {
            return Ok(Settings::default());
        }
        let content = fs::read_to_string(&self.path)?;
        if content.trim().is_empty() {
            return Ok(Settings::default());
        }
        let settings: Settings = serde_json::from_str(&content)?;
        Ok(settings)
    }

    pub fn save(&self, settings: &Settings) -> Result<()> {
        let _guard = self.lock.lock().unwrap();
        let mut normalized = settings.clone();
        normalize_settings(&mut normalized);
        let json = serde_json::to_string_pretty(&normalized)?;
        atomic_write(&self.path, &json)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn settings_default_matches_expected_values() {
        let s = Settings::default();
        assert!(s.blocked_schemas.is_empty());
        assert_eq!(s.explain_cost_threshold, 100_000.0);
        assert_eq!(s.theme, "light");
    }

    #[test]
    fn save_then_load_round_trips() {
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
    fn load_returns_defaults_when_file_is_missing_or_empty() {
        let dir = TempDir::new().unwrap();
        let store = SettingsStore::new(dir.path().to_path_buf()).unwrap();

        // No file on disk yet.
        let loaded = store.load().unwrap();
        assert_eq!(loaded.explain_cost_threshold, 100_000.0);
        assert_eq!(loaded.theme, "light");
        assert!(loaded.blocked_schemas.is_empty());

        // Empty / whitespace-only file is treated as defaults.
        fs::write(store_path(dir.path()), "   \n").unwrap();
        let loaded = store.load().unwrap();
        assert_eq!(loaded.explain_cost_threshold, 100_000.0);
        assert_eq!(loaded.theme, "light");
    }

    #[test]
    fn load_propagates_error_for_corrupt_json() {
        let dir = TempDir::new().unwrap();
        let store = SettingsStore::new(dir.path().to_path_buf()).unwrap();

        fs::write(store_path(dir.path()), "{ this is not json").unwrap();
        assert!(store.load().is_err());
    }

    #[test]
    fn save_with_defaults_round_trips() {
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
        let mut s = Settings {
            blocked_schemas: vec!["Audit".to_string(), "audit".to_string()],
            explain_cost_threshold: 0.5,
            explain_cost_thresholds: Default::default(),
            theme: "dark".to_string(),
        };
        normalize_settings(&mut s);
        assert_eq!(s.blocked_schemas, vec!["audit".to_string()]);
        assert_eq!(s.explain_cost_threshold, 1.0);
        assert_eq!(s.theme, "dark");
    }

    fn store_path(dir: &std::path::Path) -> PathBuf {
        dir.join("settings.json")
    }
}
