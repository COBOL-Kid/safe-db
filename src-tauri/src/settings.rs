use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub blocked_schemas: Vec<String>,
    #[serde(default = "default_cost_threshold")]
    pub explain_cost_threshold: f64,
    #[serde(default = "default_theme")]
    pub theme: String,
}

fn default_cost_threshold() -> f64 {
    100_000.0
}

fn default_theme() -> String {
    "light".to_string()
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            blocked_schemas: Vec::new(),
            explain_cost_threshold: default_cost_threshold(),
            theme: default_theme(),
        }
    }
}

pub struct SettingsStore {
    path: PathBuf,
    lock: Mutex<()>,
}

impl SettingsStore {
    pub fn new(data_dir: PathBuf) -> Self {
        fs::create_dir_all(&data_dir).ok();
        Self {
            path: data_dir.join("settings.json"),
            lock: Mutex::new(()),
        }
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
        let json = serde_json::to_string_pretty(settings)?;
        fs::write(&self.path, json)?;
        Ok(())
    }
}
