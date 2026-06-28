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
