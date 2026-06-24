use anyhow::Result;
use sqlx::{Column, MySqlPool, Row, TypeInfo};

use crate::adapters::ExplainResult;
use crate::introspect::{mark_indexed_columns, ColumnInfo, IndexInfo, Schema, TableInfo};
use crate::query::ir::{CompiledQuery, QueryResult};

pub async fn connect(host: &str, port: u16, database: &str, username: &str, password: &str) -> Result<MySqlPool> {
    let options = sqlx::mysql::MySqlConnectOptions::new()
        .host(host)
        .port(port)
        .database(database)
        .username(username)
        .password(password);

    let pool = MySqlPool::connect_with(options).await?;
    Ok(pool)
}

pub async fn test(pool: &MySqlPool) -> Result<String> {
    let row = sqlx::query("SELECT VERSION()").fetch_one(pool).await?;
    let version: String = row.try_get(0)?;
    Ok(version)
}

pub async fn introspect(pool: &MySqlPool) -> Result<Schema> {
    let table_rows = sqlx::query(
        "SELECT TABLE_SCHEMA, TABLE_NAME
         FROM information_schema.TABLES
         WHERE TABLE_TYPE = 'BASE TABLE'
           AND TABLE_SCHEMA NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
         ORDER BY TABLE_SCHEMA, TABLE_NAME",
    )
    .fetch_all(pool)
    .await?;

    let mut tables = Vec::new();
    for row in table_rows {
        let schema: String = row.try_get("TABLE_SCHEMA")?;
        let table_name: String = row.try_get("TABLE_NAME")?;

        let mut columns = introspect_columns(pool, &schema, &table_name).await?;
        let indexes = introspect_indexes(pool, &schema, &table_name).await?;
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

async fn introspect_columns(pool: &MySqlPool, schema: &str, table: &str) -> Result<Vec<ColumnInfo>> {
    let rows = sqlx::query(
        "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
         FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
         ORDER BY ORDINAL_POSITION",
    )
    .bind(schema)
    .bind(table)
    .fetch_all(pool)
    .await?;

    let mut columns = Vec::new();
    for row in rows {
        let name: String = row.try_get("COLUMN_NAME")?;
        let data_type: String = row.try_get("DATA_TYPE")?;
        let is_nullable: String = row.try_get("IS_NULLABLE")?;
        columns.push(ColumnInfo {
            name,
            data_type,
            nullable: is_nullable == "YES",
            is_indexed: false,
        });
    }
    Ok(columns)
}

async fn introspect_indexes(pool: &MySqlPool, schema: &str, table: &str) -> Result<Vec<IndexInfo>> {
    let rows = sqlx::query(
        "SELECT INDEX_NAME, COLUMN_NAME, (NON_UNIQUE = 0) AS is_unique
         FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
         ORDER BY INDEX_NAME, SEQ_IN_INDEX",
    )
    .bind(schema)
    .bind(table)
    .fetch_all(pool)
    .await?;

    let mut index_map: std::collections::HashMap<String, IndexInfo> = std::collections::HashMap::new();
    for row in rows {
        let index_name: String = row.try_get("INDEX_NAME")?;
        let column_name: String = row.try_get("COLUMN_NAME")?;
        let is_unique: bool = row.try_get("is_unique")?;
        let is_primary = index_name == "PRIMARY";

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

pub async fn execute_query(pool: &MySqlPool, compiled: &CompiledQuery, timeout_ms: u32) -> Result<QueryResult> {
    sqlx::query(sqlx::AssertSqlSafe(format!("SET SESSION MAX_EXECUTION_TIME = {}", timeout_ms)))
        .execute(pool)
        .await?;

    sqlx::query("SET SESSION TRANSACTION ISOLATION LEVEL READ UNCOMMITTED")
        .execute(pool)
        .await?;

    let mut tx = pool.begin().await?;
    sqlx::query("SET TRANSACTION READ ONLY").execute(&mut *tx).await?;

    let mut query = sqlx::query(sqlx::AssertSqlSafe(compiled.sql.as_str()));
    for param in &compiled.params {
        query = query.bind(param);
    }

    let rows = query.fetch_all(&mut *tx).await?;
    let row_count = rows.len();

    let columns: Vec<String> = if rows.is_empty() {
        Vec::new()
    } else {
        rows[0].columns().iter().map(|c| c.name().to_string()).collect()
    };

    let mut result_rows = Vec::new();
    for row in &rows {
        let mut row_values = Vec::new();
        for (i, col) in row.columns().iter().enumerate() {
            let value = decode_mysql_value(row, i, col.type_info().name());
            row_values.push(value);
        }
        result_rows.push(row_values);
    }

    tx.commit().await?;

    Ok(QueryResult {
        columns,
        rows: result_rows,
        row_count,
        truncated: false,
        warnings: Vec::new(),
    })
}

fn decode_mysql_value(row: &sqlx::mysql::MySqlRow, i: usize, type_name: &str) -> serde_json::Value {
    use serde_json::Value;
    match type_name {
        "BOOLEAN" | "BOOL" | "TINYINT" => {
            row.try_get::<Option<bool>, _>(i).map(|v| v.map(Value::Bool).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "SMALLINT" | "MEDIUMINT" => {
            row.try_get::<Option<i16>, _>(i).map(|v| v.map(|x| Value::from(x as i64)).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "INT" | "INTEGER" => {
            row.try_get::<Option<i32>, _>(i).map(|v| v.map(|x| Value::from(x as i64)).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "BIGINT" => {
            row.try_get::<Option<i64>, _>(i).map(|v| v.map(Value::from).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "FLOAT" => {
            row.try_get::<Option<f32>, _>(i).map(|v| v.map(|x| Value::from(x as f64)).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "DOUBLE" => {
            row.try_get::<Option<f64>, _>(i).map(|v| v.map(Value::from).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        _ => {
            row.try_get::<Option<String>, _>(i).map(|v| v.map(Value::String).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
    }
}

pub async fn explain(pool: &MySqlPool, compiled: &CompiledQuery) -> Result<ExplainResult> {
    let explain_sql = format!("EXPLAIN FORMAT=JSON {}", compiled.sql);

    let mut query = sqlx::query(sqlx::AssertSqlSafe(explain_sql.as_str()));
    for param in &compiled.params {
        query = query.bind(param);
    }

    let row = query.fetch_one(pool).await?;
    let json_str: String = row.try_get(0)?;

    let plan: serde_json::Value = serde_json::from_str(&json_str)?;
    let cost = plan
        .get(0)
        .and_then(|v| v.get("query_block"))
        .and_then(|v| v.get("cost_info"))
        .and_then(|v| v.get("query_cost"))
        .and_then(|v| v.as_f64());

    Ok(ExplainResult {
        cost,
        warning: None,
    })
}
