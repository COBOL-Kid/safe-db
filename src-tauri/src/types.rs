use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const CURRENT_CONNECTION_VERSION: u32 = 2;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum TransportSecurityMode {
    VerifyIdentity,
    VerifyCa,
    EncryptOnly,
    Disabled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TransportSecurity {
    #[serde(default = "default_transport_security_mode")]
    pub mode: TransportSecurityMode,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ca_pem: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub oracle_wallet_location: Option<String>,
    #[serde(default)]
    pub insecure_acknowledged: bool,
}

fn default_transport_security_mode() -> TransportSecurityMode {
    TransportSecurityMode::VerifyIdentity
}

impl Default for TransportSecurity {
    fn default() -> Self {
        Self {
            mode: default_transport_security_mode(),
            ca_pem: None,
            oracle_wallet_location: None,
            insecure_acknowledged: false,
        }
    }
}

fn default_connection_version() -> u32 {
    CURRENT_CONNECTION_VERSION
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionDef {
    #[serde(default = "default_connection_version")]
    pub version: u32,
    pub id: String,
    pub name: String,
    pub dialect: Dialect,
    pub host: String,
    pub port: u16,
    pub database: String,
    pub username: String,
    #[serde(default)]
    pub transport_security: TransportSecurity,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Dialect {
    Postgres,
    MySql,
    Mssql,
    Oracle,
}

impl ConnectionDef {
    pub fn credential_fingerprint(&self) -> String {
        let material = serde_json::to_vec(&(
            &self.dialect,
            &self.host,
            self.port,
            &self.database,
            &self.username,
            &self.transport_security,
        ))
        .expect("connection fingerprint fields are serializable");
        format!("{:x}", Sha256::digest(material))
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.id.trim().is_empty() {
            return Err("Connection id is required".to_string());
        }
        if self.host.trim().is_empty() {
            return Err("Host is required".to_string());
        }
        if self.database.trim().is_empty() {
            return Err("Database is required".to_string());
        }
        if self.username.trim().is_empty() {
            return Err("Username is required".to_string());
        }
        if self.port == 0 {
            return Err("Port must be between 1 and 65535".to_string());
        }
        if matches!(
            self.transport_security.mode,
            TransportSecurityMode::EncryptOnly | TransportSecurityMode::Disabled
        ) && !self.transport_security.insecure_acknowledged
        {
            return Err(
                "Insecure transport must be explicitly acknowledged before saving or testing"
                    .to_string(),
            );
        }
        if self.dialect == Dialect::Oracle
            && self.transport_security.mode != TransportSecurityMode::Disabled
            && self
                .transport_security
                .oracle_wallet_location
                .as_deref()
                .is_none_or(str::is_empty)
        {
            return Err("Verified Oracle TCPS requires an Oracle wallet location".to_string());
        }
        Ok(())
    }
}
