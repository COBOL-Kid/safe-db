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
    #[serde(default, skip_serializing_if = "is_false")]
    pub legacy_implicit: bool,
}

fn is_false(value: &bool) -> bool {
    !*value
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
            legacy_implicit: false,
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

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_connection(mode: TransportSecurityMode, legacy_implicit: bool) -> ConnectionDef {
        ConnectionDef {
            version: CURRENT_CONNECTION_VERSION,
            id: "c1".to_string(),
            name: "Test".to_string(),
            dialect: Dialect::Postgres,
            host: "localhost".to_string(),
            port: 5432,
            database: "demo".to_string(),
            username: "user".to_string(),
            transport_security: TransportSecurity {
                mode,
                ca_pem: None,
                oracle_wallet_location: None,
                legacy_implicit,
            },
        }
    }

    #[test]
    fn validate_allows_disabled_when_legacy_implicit() {
        let def = sample_connection(TransportSecurityMode::Disabled, true);
        assert!(def.validate().is_ok());
    }

    #[test]
    fn validate_allows_disabled_without_acknowledgement() {
        let def = sample_connection(TransportSecurityMode::Disabled, false);
        assert!(def.validate().is_ok());
    }

    #[test]
    fn validate_allows_encrypt_only_without_acknowledgement() {
        let def = sample_connection(TransportSecurityMode::EncryptOnly, false);
        assert!(def.validate().is_ok());
    }
}
