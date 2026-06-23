pub mod mysql;
pub mod pg;

use anyhow::Result;

use crate::introspect::Schema;
use crate::query::ir::{CompiledQuery, QueryResult};
use crate::types::{ConnectionDef, Dialect};

pub const DEFAULT_TIMEOUT_MS: u32 = 10_000;

pub enum Adapter {
    Postgres(sqlx::PgPool),
    MySql(sqlx::MySqlPool),
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
        }
    }

    pub async fn test(&self) -> Result<String> {
        match self {
            Adapter::Postgres(pool) => pg::test(pool).await,
            Adapter::MySql(pool) => mysql::test(pool).await,
        }
    }

    pub async fn introspect(&self) -> Result<Schema> {
        match self {
            Adapter::Postgres(pool) => pg::introspect(pool).await,
            Adapter::MySql(pool) => mysql::introspect(pool).await,
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
        }
    }
}
