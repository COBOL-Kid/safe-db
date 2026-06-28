pub mod mssql;
pub mod mysql;
#[cfg(feature = "oracle")]
pub mod oracle;
pub mod pg;

use anyhow::Result;

use crate::introspect::Schema;
use crate::query::ir::{CompiledQuery, QueryResult};
use crate::types::{ConnectionDef, Dialect};

pub const DEFAULT_TIMEOUT_MS: u32 = 10_000;
pub const CONNECT_TIMEOUT_MS: u64 = 10_000;
pub const INTROSPECTION_TIMEOUT_MS: u64 = 30_000;

#[derive(Debug, Clone, PartialEq)]
pub enum ExplainResult {
    Estimated(f64),
    Unavailable(String),
}

#[cfg(feature = "oracle")]
type OracleConn = std::sync::Mutex<::oracle::Connection>;

pub enum Adapter {
    Postgres(sqlx::PgPool),
    MySql(sqlx::MySqlPool),
    Mssql(Box<tokio::sync::Mutex<mssql::MssqlClient>>),
    #[cfg(feature = "oracle")]
    Oracle(OracleConn),
}

impl Adapter {
    pub async fn connect(def: &ConnectionDef, password: &str) -> Result<Self> {
        def.validate().map_err(anyhow::Error::msg)?;
        let connect = async {
            match def.dialect {
                Dialect::Postgres => {
                    let pool = pg::connect(def, password).await?;
                    Ok(Adapter::Postgres(pool))
                }
                Dialect::MySql => {
                    let pool = mysql::connect(def, password).await?;
                    Ok(Adapter::MySql(pool))
                }
                Dialect::Mssql => {
                    let client = mssql::connect(def, password).await?;
                    Ok(Adapter::Mssql(Box::new(tokio::sync::Mutex::new(client))))
                }
                #[cfg(feature = "oracle")]
                Dialect::Oracle => {
                    let conn = oracle::connect(def, password)?;
                    Ok(Adapter::Oracle(std::sync::Mutex::new(conn)))
                }
                #[cfg(not(feature = "oracle"))]
                Dialect::Oracle => Err(anyhow::anyhow!(
                    "Oracle support is not enabled. Build with: cargo build --features oracle \
                     (requires Oracle Instant Client SDK installed)"
                )),
            }
        };
        tokio::time::timeout(
            std::time::Duration::from_millis(CONNECT_TIMEOUT_MS),
            connect,
        )
        .await
        .map_err(|_| anyhow::anyhow!("Connection timed out after {CONNECT_TIMEOUT_MS}ms"))?
    }

    pub async fn test(&self) -> Result<String> {
        match self {
            Adapter::Postgres(pool) => pg::test(pool).await,
            Adapter::MySql(pool) => mysql::test(pool).await,
            Adapter::Mssql(client) => {
                let mut client = client.lock().await;
                mssql::test(&mut client).await
            }
            #[cfg(feature = "oracle")]
            Adapter::Oracle(conn) => {
                let conn = conn.lock().map_err(|e| anyhow::anyhow!(e.to_string()))?;
                oracle::test(&conn)
            }
        }
    }

    pub async fn introspect(&self) -> Result<Schema> {
        let introspect = async {
            match self {
                Adapter::Postgres(pool) => pg::introspect(pool).await,
                Adapter::MySql(pool) => mysql::introspect(pool).await,
                Adapter::Mssql(client) => {
                    let mut client = client.lock().await;
                    mssql::introspect(&mut client).await
                }
                #[cfg(feature = "oracle")]
                Adapter::Oracle(conn) => {
                    let conn = conn.lock().map_err(|e| anyhow::anyhow!(e.to_string()))?;
                    oracle::introspect(&conn)
                }
            }
        };
        tokio::time::timeout(
            std::time::Duration::from_millis(INTROSPECTION_TIMEOUT_MS),
            introspect,
        )
        .await
        .map_err(|_| anyhow::anyhow!("Schema introspection timed out"))?
    }

    pub async fn execute_query(
        &self,
        compiled: &CompiledQuery,
        timeout_ms: u32,
    ) -> Result<QueryResult> {
        match self {
            Adapter::Postgres(pool) => pg::execute_query(pool, compiled, timeout_ms).await,
            Adapter::MySql(pool) => mysql::execute_query(pool, compiled, timeout_ms).await,
            Adapter::Mssql(client) => {
                let mut client = client.lock().await;
                mssql::execute_query(&mut client, compiled, timeout_ms).await
            }
            #[cfg(feature = "oracle")]
            Adapter::Oracle(conn) => {
                let conn = conn.lock().map_err(|e| anyhow::anyhow!(e.to_string()))?;
                oracle::execute_query(&conn, compiled, timeout_ms)
            }
        }
    }

    pub async fn explain(&self, compiled: &CompiledQuery) -> Result<ExplainResult> {
        let explain = async {
            match self {
                Adapter::Postgres(pool) => pg::explain(pool, compiled).await,
                Adapter::MySql(pool) => mysql::explain(pool, compiled).await,
                Adapter::Mssql(client) => {
                    let mut client = client.lock().await;
                    mssql::explain(&mut client, compiled).await
                }
                #[cfg(feature = "oracle")]
                Adapter::Oracle(conn) => {
                    let conn = conn.lock().map_err(|e| anyhow::anyhow!(e.to_string()))?;
                    oracle::explain(&conn, compiled)
                }
            }
        };
        tokio::time::timeout(
            std::time::Duration::from_millis(DEFAULT_TIMEOUT_MS as u64),
            explain,
        )
        .await
        .map_err(|_| anyhow::anyhow!("EXPLAIN timed out after {DEFAULT_TIMEOUT_MS}ms"))?
    }
}
