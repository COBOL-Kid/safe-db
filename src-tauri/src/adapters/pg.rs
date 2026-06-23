use anyhow::Result;
use sqlx::{Column, PgPool, Row, TypeInfo};

use crate::introspect::{mark_indexed_columns, ColumnInfo, IndexInfo, Schema, TableInfo};
use crate::query::ir::{CompiledQuery, QueryResult};

pub async fn connect(host: &str, port: u16, database: &str, username: &str, password: &str) -> Result<PgPool> {
    let options = sqlx::postgres::PgConnectOptions::new()
        .host(host)
        .port(port)
        .database(database)
        .username(username)
        .password(password);

    let pool = PgPool::connect_with(options).await?;
    Ok(pool)
}

pub async fn test(pool: &PgPool) -> Result<String> {
    let row = sqlx::query("SELECT version()").fetch_one(pool).await?;
    let version: String = row.try_get(0)?;
    Ok(version)
}

pub async fn introspect(pool: &PgPool) -> Result<Schema> {
    let table_rows = sqlx::query(
        "SELECT table_schema, table_name
         FROM information_schema.tables
         WHERE table_type = 'BASE TABLE'
           AND table_schema NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
         ORDER BY table_schema, table_name",
    )
    .fetch_all(pool)
    .await?;

    let mut tables = Vec::new();
    for row in table_rows {
        let schema: String = row.try_get("table_schema")?;
        let table_name: String = row.try_get("table_name")?;

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

async fn introspect_columns(pool: &PgPool, schema: &str, table: &str) -> Result<Vec<ColumnInfo>> {
    let rows = sqlx::query(
        "SELECT column_name, data_type, is_nullable
         FROM information_schema.columns
         WHERE table_schema = $1 AND table_name = $2
         ORDER BY ordinal_position",
    )
    .bind(schema)
    .bind(table)
    .fetch_all(pool)
    .await?;

    let mut columns = Vec::new();
    for row in rows {
        let name: String = row.try_get("column_name")?;
        let data_type: String = row.try_get("data_type")?;
        let is_nullable: String = row.try_get("is_nullable")?;
        columns.push(ColumnInfo {
            name,
            data_type,
            nullable: is_nullable == "YES",
            is_indexed: false,
        });
    }
    Ok(columns)
}

async fn introspect_indexes(pool: &PgPool, schema: &str, table: &str) -> Result<Vec<IndexInfo>> {
    let rows = sqlx::query(
        "SELECT i.relname AS index_name,
                a.attname AS column_name,
                idx.indisunique,
                idx.indisprimary
         FROM pg_index idx
         JOIN pg_class t  ON t.oid = idx.indrelid
         JOIN pg_class i  ON i.oid = idx.indexrelid
         JOIN pg_namespace n ON n.oid = t.relnamespace
         JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(idx.indkey)
         WHERE n.nspname = $1 AND t.relname = $2
         ORDER BY idx.indisprimary DESC, i.relname, a.attnum",
    )
    .bind(schema)
    .bind(table)
    .fetch_all(pool)
    .await?;

    let mut index_map: std::collections::HashMap<String, IndexInfo> = std::collections::HashMap::new();
    for row in rows {
        let index_name: String = row.try_get("index_name")?;
        let column_name: String = row.try_get("column_name")?;
        let is_unique: bool = row.try_get("indisunique")?;
        let is_primary: bool = row.try_get("indisprimary")?;

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

pub async fn execute_query(pool: &PgPool, compiled: &CompiledQuery, timeout_ms: u32) -> Result<QueryResult> {
    let mut tx = pool.begin().await?;

    sqlx::query("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED")
        .execute(&mut *tx)
        .await?;

    sqlx::query(&format!("SET LOCAL statement_timeout = {}", timeout_ms))
        .execute(&mut *tx)
        .await?;

    let mut query = sqlx::query(&compiled.sql);
    for param in &compiled.params {
        query = query.bind(param);
    }

    let rows = query.fetch_all(&mut *tx).await?;
    let row_count = rows.len();

    let columns: Vec<String> = if rows.is_empty() {
        let describe = sqlx::query(&compiled.sql).fetch_optional(&mut *tx).await?;
        if let Some(row) = describe {
            row.columns().iter().map(|c| c.name().to_string()).collect()
        } else {
            Vec::new()
        }
    } else {
        rows[0].columns().iter().map(|c| c.name().to_string()).collect()
    };

    let mut result_rows = Vec::new();
    for row in &rows {
        let mut row_values = Vec::new();
        for (i, col) in row.columns().iter().enumerate() {
            let value = decode_pg_value(row, i, col.type_info().name());
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

fn decode_pg_value(row: &sqlx::postgres::PgRow, i: usize, type_name: &str) -> serde_json::Value {
    use serde_json::Value;
    match type_name {
        "BOOL" | "BOOLEAN" => {
            row.try_get::<Option<bool>, _>(i).map(|v| v.map(Value::Bool).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "INT2" | "SMALLSERIAL" | "SMALLINT" => {
            row.try_get::<Option<i16>, _>(i).map(|v| v.map(|x| Value::from(x as i64)).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "INT4" | "SERIAL" | "INTEGER" | "INT" => {
            row.try_get::<Option<i32>, _>(i).map(|v| v.map(|x| Value::from(x as i64)).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "INT8" | "BIGSERIAL" | "BIGINT" => {
            row.try_get::<Option<i64>, _>(i).map(|v| v.map(Value::from).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "FLOAT4" | "REAL" => {
            row.try_get::<Option<f32>, _>(i).map(|v| v.map(|x| Value::from(x as f64)).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        "FLOAT8" | "DOUBLE PRECISION" => {
            row.try_get::<Option<f64>, _>(i).map(|v| v.map(Value::from).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
        _ => {
            row.try_get::<Option<String>, _>(i).map(|v| v.map(Value::String).unwrap_or(Value::Null)).unwrap_or(Value::Null)
        }
    }
}
