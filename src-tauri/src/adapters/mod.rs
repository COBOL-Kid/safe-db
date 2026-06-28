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

pub struct MssqlState {
    pub client: tokio::sync::Mutex<Option<mssql::MssqlClient>>,
    pub def: ConnectionDef,
    pub password: String,
}

pub enum Adapter {
    Postgres(sqlx::PgPool),
    MySql(sqlx::MySqlPool),
    Mssql(Box<MssqlState>),
    #[cfg(feature = "oracle")]
    Oracle(OracleConn),
}

/// Infer result column labels from a compiled SELECT without re-executing the query.
pub fn columns_from_compiled_sql(sql: &str, dialect: Dialect) -> Vec<String> {
    let upper = sql.to_uppercase();
    let Some(from_idx) = upper.find("\nFROM ").or_else(|| upper.find(" FROM ")) else {
        return Vec::new();
    };
    let Some(select_idx) = upper.find("SELECT") else {
        return Vec::new();
    };
    let mut select_list = sql[select_idx + "SELECT".len()..from_idx].trim();
    if dialect == Dialect::Mssql
        && select_list.to_uppercase().starts_with("TOP ")
        && let Some((_, rest)) = select_list.split_once(' ')
        && let Some((_, after_top_count)) = rest.trim_start().split_once(' ')
    {
        select_list = after_top_count.trim_start();
    }
    if select_list == "*" {
        return Vec::new();
    }

    select_list
        .split(',')
        .map(|part| {
            let part = part.trim();
            let upper_part = part.to_uppercase();
            if let Some(as_idx) = upper_part.rfind(" AS ") {
                unquote_identifier(part[as_idx + 4..].trim(), dialect)
            } else if let Some(dot) = part.rfind('.') {
                unquote_identifier(part[dot + 1..].trim(), dialect)
            } else {
                unquote_identifier(part, dialect)
            }
        })
        .collect()
}

fn unquote_identifier(identifier: &str, dialect: Dialect) -> String {
    match dialect {
        Dialect::Postgres | Dialect::Oracle => identifier.trim_matches('"').to_string(),
        Dialect::MySql => identifier.trim_matches('`').to_string(),
        Dialect::Mssql => identifier
            .strip_prefix('[')
            .and_then(|value| value.strip_suffix(']'))
            .unwrap_or(identifier)
            .to_string(),
    }
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
                    Ok(Adapter::Mssql(Box::new(MssqlState {
                        client: tokio::sync::Mutex::new(Some(client)),
                        def: def.clone(),
                        password: password.to_string(),
                    })))
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
            Adapter::Mssql(state) => {
                let mut client = state.client.lock().await;
                let client = mssql_client(&mut client, &state.def, &state.password).await?;
                mssql::test(client).await
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
                Adapter::Mssql(state) => {
                    let mut client = state.client.lock().await;
                    let client = mssql_client(&mut client, &state.def, &state.password).await?;
                    mssql::introspect(client).await
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
            Adapter::Mssql(state) => {
                let mut client = state.client.lock().await;
                let result = {
                    let client = mssql_client(&mut client, &state.def, &state.password).await?;
                    mssql::execute_query(client, compiled, timeout_ms).await
                };
                if result
                    .as_ref()
                    .is_err_and(|error| error.downcast_ref::<mssql::QueryTimedOut>().is_some())
                {
                    *client = None;
                    match mssql::connect(&state.def, &state.password).await {
                        Ok(replacement) => *client = Some(replacement),
                        Err(error) => {
                            log::warn!(
                                "failed to reconnect SQL Server session after timeout: {error}"
                            );
                        }
                    }
                }
                result
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
                Adapter::Mssql(state) => {
                    let mut explain_client = mssql::connect(&state.def, &state.password).await?;
                    mssql::explain(&mut explain_client, compiled).await
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

async fn mssql_client<'a>(
    client: &'a mut Option<mssql::MssqlClient>,
    def: &ConnectionDef,
    password: &str,
) -> Result<&'a mut mssql::MssqlClient> {
    if client.is_none() {
        *client = Some(mssql::connect(def, password).await?);
    }
    client
        .as_mut()
        .ok_or_else(|| anyhow::anyhow!("SQL Server connection is unavailable"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn columns_from_compiled_sql_handles_dialect_quotes_and_mssql_top() {
        let pg = r#"SELECT "t0"."id" AS "t0__id", "t0"."name" AS "t0__name"
FROM "public"."users" AS "t0"
LIMIT 101"#;
        assert_eq!(
            columns_from_compiled_sql(pg, Dialect::Postgres),
            vec!["t0__id".to_string(), "t0__name".to_string()]
        );

        let mysql = "SELECT `t0`.`id` AS `t0__id`\nFROM `app`.`users` AS `t0`\nLIMIT 101";
        assert_eq!(
            columns_from_compiled_sql(mysql, Dialect::MySql),
            vec!["t0__id".to_string()]
        );

        let mssql = "SELECT TOP 101 [t0].[id] AS [t0__id]\nFROM [dbo].[users] AS [t0]";
        assert_eq!(
            columns_from_compiled_sql(mssql, Dialect::Mssql),
            vec!["t0__id".to_string()]
        );
    }
}
