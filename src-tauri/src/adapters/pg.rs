use anyhow::Result;
use sqlx::{PgPool, Row};

use crate::introspect::{mark_indexed_columns, ColumnInfo, IndexInfo, Schema, TableInfo};

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
