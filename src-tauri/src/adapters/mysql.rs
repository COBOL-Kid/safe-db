use anyhow::Result;
use sqlx::{Column, Connection, MySqlPool, Row, TypeInfo};

use crate::adapters::ExplainResult;
use crate::introspect::{ColumnInfo, IndexInfo, Schema, TableInfo, mark_indexed_columns};
use crate::query::ir::{BindValue, CompiledQuery, QueryResult, ResultCell, ResultColumn};
use crate::types::{ConnectionDef, TransportSecurityMode};

pub async fn connect(def: &ConnectionDef, password: &str) -> Result<MySqlPool> {
    use sqlx::mysql::MySqlSslMode;

    let options = sqlx::mysql::MySqlConnectOptions::new()
        .host(&def.host)
        .port(def.port)
        .database(&def.database)
        .username(&def.username)
        .password(password);
    let mut options = options.ssl_mode(match def.transport_security.mode {
        TransportSecurityMode::VerifyIdentity => MySqlSslMode::VerifyIdentity,
        TransportSecurityMode::VerifyCa => MySqlSslMode::VerifyCa,
        TransportSecurityMode::EncryptOnly => MySqlSslMode::Required,
        TransportSecurityMode::Disabled => MySqlSslMode::Disabled,
    });
    if let Some(ca) = &def.transport_security.ca_pem {
        options = options.ssl_ca_from_pem(ca.as_bytes().to_vec());
    }

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

async fn introspect_columns(
    pool: &MySqlPool,
    schema: &str,
    table: &str,
) -> Result<Vec<ColumnInfo>> {
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
            ..ColumnInfo::default()
        });
    }
    Ok(columns)
}

async fn introspect_indexes(pool: &MySqlPool, schema: &str, table: &str) -> Result<Vec<IndexInfo>> {
    let rows = sqlx::query(
        "SELECT INDEX_NAME, COLUMN_NAME, INDEX_TYPE, (NON_UNIQUE = 0) AS is_unique
         FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
         ORDER BY INDEX_NAME, SEQ_IN_INDEX",
    )
    .bind(schema)
    .bind(table)
    .fetch_all(pool)
    .await?;

    let mut index_map: std::collections::HashMap<String, IndexInfo> =
        std::collections::HashMap::new();
    for row in rows {
        let index_name: String = row.try_get("INDEX_NAME")?;
        let column_name: String = row.try_get("COLUMN_NAME")?;
        let is_unique: bool = row.try_get("is_unique")?;
        let index_type: String = row.try_get("INDEX_TYPE")?;
        let is_primary = index_name == "PRIMARY";

        let entry = index_map.entry(index_name.clone()).or_insert(IndexInfo {
            name: index_name,
            columns: Vec::new(),
            kind: index_type.clone(),
            supports_equality: matches!(index_type.as_str(), "BTREE" | "HASH"),
            is_unique,
            is_primary,
            ..IndexInfo::default()
        });
        entry.columns.push(column_name);
    }

    Ok(index_map.into_values().collect())
}

pub async fn execute_query(
    pool: &MySqlPool,
    compiled: &CompiledQuery,
    timeout_ms: u32,
) -> Result<QueryResult> {
    let mut conn = pool.acquire().await?;

    sqlx::query(sqlx::AssertSqlSafe(format!(
        "SET SESSION MAX_EXECUTION_TIME = {}",
        timeout_ms
    )))
    .execute(&mut *conn)
    .await?;

    sqlx::query("SET TRANSACTION READ ONLY, ISOLATION LEVEL READ UNCOMMITTED")
        .execute(&mut *conn)
        .await?;

    let query_result: Result<(Vec<ResultColumn>, Vec<Vec<ResultCell>>)> = async {
        let mut tx = conn.begin().await?;

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
            Vec::new()
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
                let value = decode_mysql_value(row, i, col.type_info().name())?;
                row_values.push(value);
            }
            result_rows.push(row_values);
        }

        tx.commit().await?;

        let _ = row_count;
        Ok((columns, result_rows))
    }
    .await;

    let reset_result = sqlx::query(sqlx::AssertSqlSafe("SET SESSION MAX_EXECUTION_TIME = 0"))
        .execute(&mut *conn)
        .await;

    let (columns, rows) = query_result?;
    reset_result?;

    Ok(QueryResult::from_rows(columns, rows))
}

fn decode_mysql_value(
    row: &sqlx::mysql::MySqlRow,
    i: usize,
    type_name: &str,
) -> Result<ResultCell> {
    fn cell<T>(value: Option<T>, map: impl FnOnce(T) -> ResultCell) -> ResultCell {
        value.map(map).unwrap_or(ResultCell::Null)
    }

    match classify_mysql_type(type_name) {
        MysqlTypeKind::SmallInt => row
            .try_get::<Option<i16>, _>(i)
            .map(|v| cell(v, |x| ResultCell::Integer(x as i64)))
            .map_err(Into::into),
        MysqlTypeKind::Int => row
            .try_get::<Option<i32>, _>(i)
            .map(|v| cell(v, |x| ResultCell::Integer(x as i64)))
            .map_err(Into::into),
        MysqlTypeKind::BigInt => row
            .try_get::<Option<i64>, _>(i)
            .map(|v| cell(v, ResultCell::Integer))
            .map_err(Into::into),
        MysqlTypeKind::Float => row
            .try_get::<Option<f32>, _>(i)
            .map(|v| cell(v, |x| ResultCell::Float(x as f64)))
            .map_err(Into::into),
        MysqlTypeKind::Double => row
            .try_get::<Option<f64>, _>(i)
            .map(|v| cell(v, ResultCell::Float))
            .map_err(Into::into),
        MysqlTypeKind::Decimal => row
            .try_get::<Option<bigdecimal::BigDecimal>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        MysqlTypeKind::Date => row
            .try_get::<Option<chrono::NaiveDate>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        MysqlTypeKind::DateTime => row
            .try_get::<Option<chrono::NaiveDateTime>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        MysqlTypeKind::Json => row
            .try_get::<Option<serde_json::Value>, _>(i)
            .map(|v| cell(v, |x| ResultCell::text(x.to_string())))
            .map_err(Into::into),
        MysqlTypeKind::Binary => row
            .try_get::<Option<Vec<u8>>, _>(i)
            .map(|v| cell(v, |x| ResultCell::binary(&x)))
            .map_err(Into::into),
        MysqlTypeKind::Text => row
            .try_get::<Option<String>, _>(i)
            .map(|v| cell(v, ResultCell::text))
            .map_err(Into::into),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum MysqlTypeKind {
    SmallInt,
    Int,
    BigInt,
    Float,
    Double,
    Decimal,
    Date,
    DateTime,
    Json,
    Binary,
    Text,
}

fn classify_mysql_type(type_name: &str) -> MysqlTypeKind {
    match type_name {
        "TINYINT" | "SMALLINT" | "MEDIUMINT" => MysqlTypeKind::SmallInt,
        "INT" | "INTEGER" => MysqlTypeKind::Int,
        "BIGINT" => MysqlTypeKind::BigInt,
        "FLOAT" => MysqlTypeKind::Float,
        "DOUBLE" => MysqlTypeKind::Double,
        "DECIMAL" | "NEWDECIMAL" | "NUMERIC" => MysqlTypeKind::Decimal,
        "DATE" => MysqlTypeKind::Date,
        "DATETIME" | "TIMESTAMP" => MysqlTypeKind::DateTime,
        "JSON" => MysqlTypeKind::Json,
        "BINARY" | "VARBINARY" | "BLOB" | "TINYBLOB" | "MEDIUMBLOB" | "LONGBLOB" => {
            MysqlTypeKind::Binary
        }
        _ => MysqlTypeKind::Text,
    }
}

pub async fn explain(pool: &MySqlPool, compiled: &CompiledQuery) -> Result<ExplainResult> {
    let explain_sql = format!("EXPLAIN FORMAT=JSON {}", compiled.sql);

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
    let json_str: String = row.try_get(0)?;

    let plan: serde_json::Value = serde_json::from_str(&json_str)?;
    let cost_value = plan
        .get("query_block")
        .and_then(|v| v.get("cost_info"))
        .and_then(|v| v.get("query_cost"));
    let cost = cost_value.and_then(|value| {
        value
            .as_f64()
            .or_else(|| value.as_str().and_then(|s| s.parse::<f64>().ok()))
    });

    Ok(match cost {
        Some(cost) => ExplainResult::Estimated(cost),
        None => ExplainResult::Unavailable(
            "Could not parse EXPLAIN cost from MySQL JSON plan".to_string(),
        ),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_mysql_tinyint_as_lossless_integer() {
        assert_eq!(classify_mysql_type("TINYINT"), MysqlTypeKind::SmallInt);
    }

    #[test]
    fn classify_mysql_type_integer_aliases() {
        assert_eq!(classify_mysql_type("SMALLINT"), MysqlTypeKind::SmallInt);
        assert_eq!(classify_mysql_type("MEDIUMINT"), MysqlTypeKind::SmallInt);
        assert_eq!(classify_mysql_type("INT"), MysqlTypeKind::Int);
        assert_eq!(classify_mysql_type("INTEGER"), MysqlTypeKind::Int);
        assert_eq!(classify_mysql_type("BIGINT"), MysqlTypeKind::BigInt);
    }

    #[test]
    fn classify_mysql_type_float_aliases() {
        assert_eq!(classify_mysql_type("FLOAT"), MysqlTypeKind::Float);
        assert_eq!(classify_mysql_type("DOUBLE"), MysqlTypeKind::Double);
    }

    #[test]
    fn classify_mysql_type_unknown_falls_back_to_text() {
        assert_eq!(classify_mysql_type("VARCHAR"), MysqlTypeKind::Text);
        assert_eq!(classify_mysql_type("DATETIME"), MysqlTypeKind::DateTime);
        assert_eq!(classify_mysql_type(""), MysqlTypeKind::Text);
        assert_eq!(classify_mysql_type("JSON"), MysqlTypeKind::Json);
    }
}
