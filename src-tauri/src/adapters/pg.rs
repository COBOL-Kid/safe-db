use anyhow::Result;
use sqlx::{Column, PgPool, Row, TypeInfo};

use crate::adapters::{ExplainResult, columns_from_compiled_sql};
use crate::introspect::{ColumnInfo, IndexInfo, Schema, TableInfo, mark_indexed_columns};
use crate::query::ir::{BindValue, CompiledQuery, QueryResult, ResultCell, ResultColumn};
use crate::types::{ConnectionDef, TransportSecurityMode};

pub async fn connect(def: &ConnectionDef, password: &str) -> Result<PgPool> {
    use sqlx::postgres::PgSslMode;

    let options = sqlx::postgres::PgConnectOptions::new()
        .host(&def.host)
        .port(def.port)
        .database(&def.database)
        .username(&def.username)
        .password(password);
    let mut options = options.ssl_mode(match def.transport_security.mode {
        TransportSecurityMode::VerifyIdentity => PgSslMode::VerifyFull,
        TransportSecurityMode::VerifyCa => PgSslMode::VerifyCa,
        TransportSecurityMode::EncryptOnly => PgSslMode::Require,
        TransportSecurityMode::Disabled => PgSslMode::Disable,
    });
    if let Some(ca) = &def.transport_security.ca_pem {
        options = options.ssl_root_cert_from_pem(ca.as_bytes().to_vec());
    }

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
            ..ColumnInfo::default()
        });
    }
    Ok(columns)
}

async fn introspect_indexes(pool: &PgPool, schema: &str, table: &str) -> Result<Vec<IndexInfo>> {
    let rows = sqlx::query(
        "SELECT i.relname AS index_name,
                a.attname AS column_name,
                idx.indisunique,
                idx.indisprimary,
                am.amname AS index_type
         FROM pg_index idx
         JOIN pg_class t  ON t.oid = idx.indrelid
         JOIN pg_class i  ON i.oid = idx.indexrelid
         JOIN pg_am am ON am.oid = i.relam
         JOIN pg_namespace n ON n.oid = t.relnamespace
         JOIN LATERAL unnest(idx.indkey) WITH ORDINALITY AS key(attnum, ordinality)
           ON key.ordinality <= idx.indnkeyatts
         JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = key.attnum
         WHERE n.nspname = $1 AND t.relname = $2
         ORDER BY idx.indisprimary DESC, i.relname, key.ordinality",
    )
    .bind(schema)
    .bind(table)
    .fetch_all(pool)
    .await?;

    let mut index_map: std::collections::HashMap<String, IndexInfo> =
        std::collections::HashMap::new();
    for row in rows {
        let index_name: String = row.try_get("index_name")?;
        let column_name: String = row.try_get("column_name")?;
        let is_unique: bool = row.try_get("indisunique")?;
        let is_primary: bool = row.try_get("indisprimary")?;
        let index_type: String = row.try_get("index_type")?;

        let entry = index_map.entry(index_name.clone()).or_insert(IndexInfo {
            name: index_name,
            columns: Vec::new(),
            kind: index_type.clone(),
            supports_equality: matches!(index_type.as_str(), "btree" | "hash"),
            is_unique,
            is_primary,
            ..IndexInfo::default()
        });
        entry.columns.push(column_name);
    }

    Ok(index_map.into_values().collect())
}

pub async fn execute_query(
    pool: &PgPool,
    compiled: &CompiledQuery,
    timeout_ms: u32,
) -> Result<QueryResult> {
    let mut tx = pool.begin().await?;

    sqlx::query("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED")
        .execute(&mut *tx)
        .await?;

    sqlx::query(sqlx::AssertSqlSafe(format!(
        "SET LOCAL statement_timeout = {}",
        timeout_ms
    )))
    .execute(&mut *tx)
    .await?;

    let mut query = sqlx::query(sqlx::AssertSqlSafe(compiled.sql.as_str()));
    for param in &compiled.params {
        query = match param {
            BindValue::Text(s) => query.bind(s.as_str()),
            BindValue::Int(n) => query.bind(*n),
            BindValue::Decimal(n) => query.bind(n.clone()),
            BindValue::Float(f) => query.bind(*f),
            BindValue::Bool(b) => query.bind(*b),
            BindValue::Date(d) => query.bind(*d),
            BindValue::DateTime(dt) => query.bind(*dt),
            BindValue::Null => query.bind(None::<i64>),
        };
    }

    let rows = query.fetch_all(&mut *tx).await?;
    let row_count = rows.len();

    let columns: Vec<ResultColumn> = if rows.is_empty() {
        columns_from_compiled_sql(&compiled.sql, crate::types::Dialect::Postgres)
            .into_iter()
            .map(|name| ResultColumn::new(name, "unknown"))
            .collect()
    } else {
        rows[0]
            .columns()
            .iter()
            .map(|c| ResultColumn::new(c.name(), c.type_info().name()))
            .collect()
    };

    let mut result_rows = Vec::new();
    for row in &rows {
        let mut row_values = Vec::new();
        for (i, col) in row.columns().iter().enumerate() {
            let value = decode_pg_value(row, i, col.type_info().name())?;
            row_values.push(value);
        }
        result_rows.push(row_values);
    }

    tx.commit().await?;

    let _ = row_count;
    Ok(QueryResult::from_rows(columns, result_rows))
}

fn decode_pg_value(row: &sqlx::postgres::PgRow, i: usize, type_name: &str) -> Result<ResultCell> {
    fn cell<T>(value: Option<T>, map: impl FnOnce(T) -> ResultCell) -> ResultCell {
        value.map(map).unwrap_or(ResultCell::Null)
    }

    match classify_pg_type(type_name) {
        PgTypeKind::Bool => row
            .try_get::<Option<bool>, _>(i)
            .map(|v| cell(v, ResultCell::Bool))
            .map_err(Into::into),
        PgTypeKind::SmallInt => row
            .try_get::<Option<i16>, _>(i)
            .map(|v| cell(v, |x| ResultCell::Integer(x as i64)))
            .map_err(Into::into),
        PgTypeKind::Int => row
            .try_get::<Option<i32>, _>(i)
            .map(|v| cell(v, |x| ResultCell::Integer(x as i64)))
            .map_err(Into::into),
        PgTypeKind::BigInt => row
            .try_get::<Option<i64>, _>(i)
            .map(|v| cell(v, ResultCell::Integer))
            .map_err(Into::into),
        PgTypeKind::Float => row
            .try_get::<Option<f32>, _>(i)
            .map(|v| cell(v, |x| ResultCell::Float(x as f64)))
            .map_err(Into::into),
        PgTypeKind::Double => row
            .try_get::<Option<f64>, _>(i)
            .map(|v| cell(v, ResultCell::Float))
            .map_err(Into::into),
        PgTypeKind::Decimal => row
            .try_get::<Option<bigdecimal::BigDecimal>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        PgTypeKind::Date => row
            .try_get::<Option<chrono::NaiveDate>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        PgTypeKind::DateTime => row
            .try_get::<Option<chrono::NaiveDateTime>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        PgTypeKind::DateTimeTz => row
            .try_get::<Option<chrono::DateTime<chrono::Utc>>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_rfc3339())))
            .map_err(Into::into),
        PgTypeKind::Uuid => row
            .try_get::<Option<uuid::Uuid>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        PgTypeKind::Json => row
            .try_get::<Option<serde_json::Value>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        PgTypeKind::Binary => row
            .try_get::<Option<Vec<u8>>, _>(i)
            .map(|v| cell(v, |x| ResultCell::binary(&x)))
            .map_err(Into::into),
        PgTypeKind::Text => row
            .try_get::<Option<String>, _>(i)
            .map(|v| cell(v, ResultCell::text))
            .map_err(Into::into),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PgTypeKind {
    Bool,
    SmallInt,
    Int,
    BigInt,
    Float,
    Double,
    Decimal,
    Date,
    DateTime,
    DateTimeTz,
    Uuid,
    Json,
    Binary,
    Text,
}

pub(crate) fn classify_pg_type(type_name: &str) -> PgTypeKind {
    match type_name {
        "BOOL" | "BOOLEAN" => PgTypeKind::Bool,
        "INT2" | "SMALLSERIAL" | "SMALLINT" => PgTypeKind::SmallInt,
        "INT4" | "SERIAL" | "INTEGER" | "INT" => PgTypeKind::Int,
        "INT8" | "BIGSERIAL" | "BIGINT" => PgTypeKind::BigInt,
        "FLOAT4" | "REAL" => PgTypeKind::Float,
        "FLOAT8" | "DOUBLE PRECISION" => PgTypeKind::Double,
        "NUMERIC" | "DECIMAL" => PgTypeKind::Decimal,
        "DATE" => PgTypeKind::Date,
        "TIMESTAMP" | "TIMESTAMP WITHOUT TIME ZONE" => PgTypeKind::DateTime,
        "TIMESTAMPTZ" | "TIMESTAMP WITH TIME ZONE" => PgTypeKind::DateTimeTz,
        "UUID" => PgTypeKind::Uuid,
        "JSON" | "JSONB" => PgTypeKind::Json,
        "BYTEA" => PgTypeKind::Binary,
        _ => PgTypeKind::Text,
    }
}

pub async fn explain(pool: &PgPool, compiled: &CompiledQuery) -> Result<ExplainResult> {
    let explain_sql = format!("EXPLAIN (FORMAT JSON) {}", compiled.sql);

    let mut query = sqlx::query(sqlx::AssertSqlSafe(explain_sql.as_str()));
    for param in &compiled.params {
        query = match param {
            BindValue::Text(s) => query.bind(s.as_str()),
            BindValue::Int(n) => query.bind(*n),
            BindValue::Decimal(n) => query.bind(n.clone()),
            BindValue::Float(f) => query.bind(*f),
            BindValue::Bool(b) => query.bind(*b),
            BindValue::Date(d) => query.bind(*d),
            BindValue::DateTime(dt) => query.bind(*dt),
            BindValue::Null => query.bind(None::<i64>),
        };
    }

    let row = query.fetch_one(pool).await?;
    let plan: serde_json::Value = row.try_get(0)?;
    let cost = plan
        .get(0)
        .and_then(|v| v.get("Plan"))
        .and_then(|v| v.get("Total Cost"))
        .and_then(|v| v.as_f64());

    Ok(match cost {
        Some(cost) => ExplainResult::Estimated(cost),
        None => ExplainResult::Unavailable(
            "Could not parse EXPLAIN cost from PostgreSQL JSON plan".to_string(),
        ),
    })
}
