pub mod mssql;
pub mod mysql;
pub mod pg;
#[cfg(feature = "oracle")]
pub mod oracle;

use anyhow::Result;

use crate::introspect::Schema;
use crate::query::ir::{CompiledQuery, QueryResult};
use crate::types::{ConnectionDef, Dialect};

pub const DEFAULT_TIMEOUT_MS: u32 = 10_000;

#[cfg(feature = "oracle")]
type OracleConn = std::sync::Mutex<oracle::Connection>;

pub enum Adapter {
    Postgres(sqlx::PgPool),
    MySql(sqlx::MySqlPool),
    Mssql(tokio::sync::Mutex<mssql::MssqlClient>),
    #[cfg(feature = "oracle")]
    Oracle(OracleConn),
}

impl Adapter {
    pub async fn connect(def: &ConnectionDef, password: &str) -> Result<Self> {
        match def.dialect {
            Dialect::Postgres => {
                let pool = pg::connect(&def.host, def.port, &def.database, &def.username, password).await?;
                Ok(Adapter::Postgres(pool))
            }
            Dialect::MySql => {
                let pool = mysql::connect(&def.host, def.port, &def.database, &def.username, password).await?;
                Ok(Adapter::MySql(pool))
            }
            Dialect::Mssql => {
                let client = mssql::connect(&def.host, def.port, &def.database, &def.username, password).await?;
                Ok(Adapter::Mssql(tokio::sync::Mutex::new(client)))
            }
            #[cfg(feature = "oracle")]
            Dialect::Oracle => {
                let conn = oracle::connect(&def.host, def.port, &def.database, &def.username, password)?;
                Ok(Adapter::Oracle(std::sync::Mutex::new(conn)))
            }
            #[cfg(not(feature = "oracle"))]
            Dialect::Oracle => {
                Err(anyhow::anyhow!(
                    "Oracle support is not enabled. Build with: cargo build --features oracle \
                     (requires Oracle Instant Client SDK installed)"
                ))
            }
        }
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
}
