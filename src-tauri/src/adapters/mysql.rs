use anyhow::Result;
use sqlx::{MySqlPool, Row};

use crate::introspect::{mark_indexed_columns, ColumnInfo, IndexInfo, Schema, TableInfo};

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
