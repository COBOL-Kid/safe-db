use anyhow::Result;
use std::time::Duration;
use tiberius::{AuthMethod, Client, ColumnData, Config};
use tokio::net::TcpStream;
use tokio::time::timeout;
use tokio_util::compat::TokioAsyncWriteCompatExt;

use crate::adapters::ExplainResult;
use crate::introspect::{ColumnInfo, IndexInfo, Schema, TableInfo, mark_indexed_columns};
use crate::query::ir::{BindValue, CompiledQuery, QueryResult};

pub type MssqlClient = Client<tokio_util::compat::Compat<TcpStream>>;

pub async fn connect(
    host: &str,
    port: u16,
    database: &str,
    username: &str,
    password: &str,
) -> Result<MssqlClient> {
    let mut config = Config::new();
    config.host(host);
    config.port(port);
    config.database(database);
    config.authentication(AuthMethod::sql_server(username, password));
    config.readonly(true);

    let tcp = TcpStream::connect(config.get_addr()).await?;
    tcp.set_nodelay(true)?;

    let client = Client::connect(config, tcp.compat_write()).await?;
    Ok(client)
}

pub async fn test(client: &mut MssqlClient) -> Result<String> {
    let row = client
        .query("SELECT @@VERSION AS version", &[])
        .await?
        .into_row()
        .await?;
    let version = row
        .and_then(|r| r.get::<&str, _>("version").map(|s| s.to_string()))
        .unwrap_or_else(|| "Unknown".to_string());
    Ok(version)
}

pub async fn introspect(client: &mut MssqlClient) -> Result<Schema> {
    let table_rows = client
        .query(
            "SELECT TABLE_SCHEMA, TABLE_NAME
             FROM INFORMATION_SCHEMA.TABLES
             WHERE TABLE_TYPE = 'BASE TABLE'
               AND TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA', 'sys', 'guest')
             ORDER BY TABLE_SCHEMA, TABLE_NAME",
            &[],
        )
        .await?
        .into_first_result()
        .await?;

    let mut tables = Vec::new();
    for row in table_rows {
        let schema: String = row
            .get::<&str, _>("TABLE_SCHEMA")
            .unwrap_or_default()
            .to_string();
        let table_name: String = row
            .get::<&str, _>("TABLE_NAME")
            .unwrap_or_default()
            .to_string();

        let mut columns = introspect_columns(client, &schema, &table_name).await?;
        let indexes = introspect_indexes(client, &schema, &table_name).await?;
        mark_indexed_columns(&mut columns, &indexes);

        tables.push(TableInfo {
            schema,
            name: table_name,
            columns,
            indexes,
        });
    }

    Ok(Schema { tables })
}

async fn introspect_columns(
    client: &mut MssqlClient,
    schema: &str,
    table: &str,
) -> Result<Vec<ColumnInfo>> {
    let rows = client
        .query(
            "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
             FROM INFORMATION_SCHEMA.COLUMNS
             WHERE TABLE_SCHEMA = @P1 AND TABLE_NAME = @P2
             ORDER BY ORDINAL_POSITION",
            &[&schema, &table],
        )
        .await?
        .into_first_result()
        .await?;

    let mut columns = Vec::new();
    for row in rows {
        let name: String = row
            .get::<&str, _>("COLUMN_NAME")
            .unwrap_or_default()
            .to_string();
        let data_type: String = row
            .get::<&str, _>("DATA_TYPE")
            .unwrap_or_default()
            .to_string();
        let is_nullable: String = row
            .get::<&str, _>("IS_NULLABLE")
            .unwrap_or_default()
            .to_string();
        columns.push(ColumnInfo {
            name,
            data_type,
            nullable: is_nullable == "YES",
            is_indexed: false,
        });
    }
    Ok(columns)
}

async fn introspect_indexes(
    client: &mut MssqlClient,
    schema: &str,
    table: &str,
) -> Result<Vec<IndexInfo>> {
    let rows = client
        .query(
            "SELECT i.name AS index_name,
                    c.name AS column_name,
                    i.is_unique,
                    i.is_primary_key
             FROM sys.indexes i
             JOIN sys.index_columns ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
             JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
             JOIN sys.tables t ON i.object_id = t.object_id
             JOIN sys.schemas s ON t.schema_id = s.schema_id
             WHERE s.name = @P1 AND t.name = @P2
             ORDER BY i.is_primary_key DESC, i.name, ic.key_ordinal",
            &[&schema, &table],
        )
        .await?
        .into_first_result()
        .await?;

    let mut index_map: std::collections::HashMap<String, IndexInfo> =
        std::collections::HashMap::new();
    for row in rows {
        let index_name: String = row
            .get::<&str, _>("index_name")
            .unwrap_or_default()
            .to_string();
        let column_name: String = row
            .get::<&str, _>("column_name")
            .unwrap_or_default()
            .to_string();
        let is_unique: bool = row.get::<bool, _>("is_unique").unwrap_or(false);
        let is_primary: bool = row.get::<bool, _>("is_primary_key").unwrap_or(false);

        let entry = index_map.entry(index_name.clone()).or_insert(IndexInfo {
            name: index_name,
            columns: Vec::new(),
            is_unique,
            is_primary,
        });
        entry.columns.push(column_name);
    }

    Ok(index_map.into_values().collect())
}

enum OwnedParam {
    Str(String),
    Int(i64),
    Float(f64),
    Bool(bool),
    Null,
}

async fn query_with_params(
    client: &mut MssqlClient,
    compiled: &CompiledQuery,
) -> Result<Vec<tiberius::Row>> {
    use tiberius::ToSql;

    let null_val: Option<i64> = None;
    let owned: Vec<OwnedParam> = compiled
        .params
        .iter()
        .map(|p| match p {
            BindValue::Text(s) => OwnedParam::Str(s.clone()),
            BindValue::Int(n) => OwnedParam::Int(*n),
            BindValue::Float(f) => OwnedParam::Float(*f),
            BindValue::Bool(b) => OwnedParam::Bool(*b),
            BindValue::Null => OwnedParam::Null,
        })
        .collect();

    let param_refs: Vec<&dyn ToSql> = owned
        .iter()
        .map(|p| match p {
            OwnedParam::Str(s) => s as &dyn ToSql,
            OwnedParam::Int(n) => n as &dyn ToSql,
            OwnedParam::Float(f) => f as &dyn ToSql,
            OwnedParam::Bool(b) => b as &dyn ToSql,
            OwnedParam::Null => &null_val as &dyn ToSql,
        })
        .collect();

    let rows = client
        .query(&compiled.sql, &param_refs)
        .await?
        .into_first_result()
        .await?;
    Ok(rows)
}

pub async fn execute_query(
    client: &mut MssqlClient,
    compiled: &CompiledQuery,
    timeout_ms: u32,
) -> Result<QueryResult> {
    let duration = Duration::from_millis(timeout_ms as u64);
    timeout(duration, execute_query_inner(client, compiled, timeout_ms))
        .await
        .map_err(|_| anyhow::anyhow!("Query timed out after {timeout_ms}ms"))?
}

async fn execute_query_inner(
    client: &mut MssqlClient,
    compiled: &CompiledQuery,
    timeout_ms: u32,
) -> Result<QueryResult> {
    client.execute("BEGIN TRANSACTION", &[]).await?;

    let inner = async {
        client.execute("SET TRANSACTION READ ONLY", &[]).await?;
        client
            .execute(&format!("SET LOCK_TIMEOUT {timeout_ms}"), &[])
            .await?;

        let rows = query_with_params(client, compiled).await?;
        let row_count = rows.len();

        let columns: Vec<String> = if rows.is_empty() {
            Vec::new()
        } else {
            rows[0]
                .columns()
                .iter()
                .map(|c| c.name().to_string())
                .collect()
        };

        let mut result_rows = Vec::new();
        for row in rows {
            let row_data: Vec<serde_json::Value> =
                row.into_iter().map(decode_mssql_value).collect();
            result_rows.push(row_data);
        }

        client.execute("COMMIT TRANSACTION", &[]).await?;

        Ok(QueryResult {
            columns,
            rows: result_rows,
            row_count,
            truncated: false,
            warnings: Vec::new(),
        })
    }
    .await;

    match inner {
        Err(e) => {
            let _ = client.execute("ROLLBACK TRANSACTION", &[]).await;
            Err(e)
        }
        Ok(result) => Ok(result),
    }
}

pub async fn explain(client: &mut MssqlClient, compiled: &CompiledQuery) -> Result<ExplainResult> {
    client.execute("SET SHOWPLAN_XML ON", &[]).await?;

    let plan_result = query_with_params(client, compiled).await;
    client.execute("SET SHOWPLAN_XML OFF", &[]).await?;

    let rows = plan_result?;
    let xml: String = rows
        .into_iter()
        .filter_map(|row| {
            row.into_iter().find_map(|cell| match cell {
                ColumnData::String(Some(s)) => Some(s.into_owned()),
                _ => None,
            })
        })
        .collect();

    let cost = parse_showplan_cost(&xml);
    Ok(ExplainResult {
        cost,
        warning: if cost.is_none() {
            Some("Could not parse EXPLAIN cost from SHOWPLAN_XML".to_string())
        } else {
            None
        },
    })
}

fn parse_showplan_cost(xml: &str) -> Option<f64> {
    for marker in ["StatementSubTreeCost=\"", "StatementSubTree Cost=\""] {
        if let Some(start) = xml.find(marker) {
            let rest = &xml[start + marker.len()..];
            let end = rest.find('"')?;
            return rest[..end].parse().ok();
        }
    }
    None
}

fn decode_mssql_value(cell: ColumnData<'static>) -> serde_json::Value {
    use serde_json::Value;
    match cell {
        ColumnData::U8(Some(v)) => Value::from(v as i64),
        ColumnData::I16(Some(v)) => Value::from(v as i64),
        ColumnData::I32(Some(v)) => Value::from(v as i64),
        ColumnData::I64(Some(v)) => Value::from(v),
        ColumnData::F32(Some(v)) => Value::from(v as f64),
        ColumnData::F64(Some(v)) => Value::from(v),
        ColumnData::String(Some(s)) => Value::String(s.into_owned()),
        _ => Value::Null,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_showplan_cost_extracts_subtree_cost() {
        let xml = r#"<StmtSimple StatementSubTreeCost="12.5" />"#;
        assert_eq!(parse_showplan_cost(xml), Some(12.5));
    }

    #[test]
    fn parse_showplan_cost_returns_none_for_missing_marker() {
        assert_eq!(parse_showplan_cost("<Plan />"), None);
    }
}
