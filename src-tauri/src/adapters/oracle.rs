use anyhow::Result;
use oracle::sql_type::{InnerValue, ToSql};
use oracle::{Connection, Row as OracleRow};
use std::time::Duration;

use crate::adapters::ExplainResult;
use crate::introspect::{ColumnInfo, IndexInfo, Schema, TableInfo, mark_indexed_columns};
use crate::query::ir::{BindValue, CompiledQuery, QueryResult};

const BLOCKED_OWNERS: &[&str] = &[
    "SYS",
    "SYSTEM",
    "OUTLN",
    "DBSNMP",
    "APPQOSSYS",
    "DBSFWUSER",
    "ORACLE_OCM",
    "ANONYMOUS",
    "XS$NULL",
    "GSMADMIN_INTERNAL",
    "AUDSYS",
    "DVSYS",
    "LBACSYS",
    "REMOTE_SCHEDULER_AGENT",
    "WMSYS",
    "XDB",
    "CTXSYS",
    "ORDSYS",
    "ORDPLUGINS",
    "SI_INFORMTN_SCHEMA",
    "MDSYS",
    "OLAPSYS",
    "MDDATA",
    "SPATIAL_WFS_ADMIN_USR",
    "SPATIAL_CSW_ADMIN_USR",
    "SYSMAN",
    "APEX_030200",
    "FLOWS_FILES",
    "APEX_PUBLIC_USER",
    "ORDDATA",
    "APEX_040000",
    "APEX_040200",
];

fn validate_connect_field(field: &str, label: &str) -> Result<()> {
    if field.is_empty() {
        anyhow::bail!("{label} must not be empty");
    }
    if field
        .chars()
        .any(|c| !(c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_'))
    {
        anyhow::bail!("{label} contains invalid characters");
    }
    Ok(())
}

pub fn connect(
    host: &str,
    port: u16,
    database: &str,
    username: &str,
    password: &str,
) -> Result<Connection> {
    validate_connect_field(host, "Host")?;
    validate_connect_field(database, "Database")?;
    if port == 0 {
        anyhow::bail!("Port must be between 1 and 65535");
    }

    let conn_str = format!("//{host}:{port}/{database}");
    let conn = Connection::connect(username, password, conn_str)?;
    Ok(conn)
}

pub fn test(conn: &Connection) -> Result<String> {
    let row = conn.query_row(
        "SELECT banner FROM v$version WHERE banner LIKE 'Oracle%'",
        &[],
    )?;
    let version: String = row.get(0)?;
    Ok(version)
}

pub fn introspect(conn: &Connection) -> Result<Schema> {
    let blocked = BLOCKED_OWNERS
        .iter()
        .map(|owner| format!("'{}'", owner))
        .collect::<Vec<_>>()
        .join(",");

    let sql = format!(
        "SELECT owner, table_name
         FROM all_tables
         WHERE owner NOT IN ({blocked})
         ORDER BY owner, table_name"
    );

    let table_rows = conn.query(&sql, &[])?;

    let mut tables = Vec::new();
    let mut table_data: Vec<(String, String)> = Vec::new();
    for row_result in table_rows {
        let row = row_result?;
        let owner: String = row.get(0)?;
        let table_name: String = row.get(1)?;
        table_data.push((owner, table_name));
    }

    for (schema, table_name) in table_data {
        let mut columns = introspect_columns(conn, &schema, &table_name)?;
        let indexes = introspect_indexes(conn, &schema, &table_name)?;
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

fn introspect_columns(conn: &Connection, schema: &str, table: &str) -> Result<Vec<ColumnInfo>> {
    let rows = conn.query(
        "SELECT column_name, data_type, nullable
         FROM all_tab_columns
         WHERE owner = :1 AND table_name = :2
         ORDER BY column_id",
        &[&schema as &dyn ToSql, &table as &dyn ToSql],
    )?;

    let mut columns = Vec::new();
    for row_result in rows {
        let row = row_result?;
        let name: String = row.get(0)?;
        let data_type: String = row.get(1)?;
        let nullable_char: String = row.get(2)?;
        columns.push(ColumnInfo {
            name,
            data_type,
            nullable: nullable_char == "Y",
            is_indexed: false,
        });
    }
    Ok(columns)
}

fn introspect_indexes(conn: &Connection, schema: &str, table: &str) -> Result<Vec<IndexInfo>> {
    let rows = conn.query(
        "SELECT aic.index_name, aic.column_name,
                CASE WHEN ai.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_unique,
                CASE WHEN aic.index_name LIKE 'SYS_%' AND ai.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_primary
         FROM all_ind_columns aic
         JOIN all_indexes ai ON aic.index_owner = ai.owner AND aic.index_name = ai.index_name
         WHERE aic.table_owner = :1 AND aic.table_name = :2
         ORDER BY ai.uniqueness DESC, aic.index_name, aic.column_position",
        &[&schema as &dyn ToSql, &table as &dyn ToSql],
    )?;

    let mut index_map: std::collections::HashMap<String, IndexInfo> =
        std::collections::HashMap::new();
    for row_result in rows {
        let row = row_result?;
        let index_name: String = row.get(0)?;
        let column_name: String = row.get(1)?;
        let is_unique: i32 = row.get(2)?;
        let is_primary: i32 = row.get(3)?;

        let entry = index_map.entry(index_name.clone()).or_insert(IndexInfo {
            name: index_name,
            columns: Vec::new(),
            is_unique: is_unique == 1,
            is_primary: is_primary == 1,
        });
        entry.columns.push(column_name);
    }

    Ok(index_map.into_values().collect())
}

fn bind_statement(
    conn: &Connection,
    compiled: &CompiledQuery,
) -> Result<oracle::Statement<'_>> {
    let mut stmt = conn.statement(&compiled.sql).build()?;
    for (i, param) in compiled.params.iter().enumerate() {
        match param {
            BindValue::Text(s) => stmt.bind(i + 1, &s.as_str())?,
            BindValue::Int(n) => stmt.bind(i + 1, n)?,
            BindValue::Float(f) => stmt.bind(i + 1, f)?,
            BindValue::Bool(b) => stmt.bind(i + 1, b)?,
            BindValue::Null => {
                let null_val: Option<i64> = None;
                stmt.bind(i + 1, &null_val)?;
            }
        }
    }
    Ok(stmt)
}

pub fn execute_query(
    conn: &Connection,
    compiled: &CompiledQuery,
    timeout_ms: u32,
) -> Result<QueryResult> {
    let prev_timeout = conn.call_timeout()?;
    conn.set_call_timeout(Some(Duration::from_millis(timeout_ms as u64)))?;

    let result = (|| {
        conn.execute("SET TRANSACTION READ ONLY", &[])?;

        let stmt = bind_statement(conn, compiled)?;
        let rows = stmt.query(&[])?;

        let mut column_names: Vec<String> = Vec::new();
        let mut result_rows = Vec::new();

        let mut first = true;
        for row_result in rows {
            let row = row_result?;
            if first {
                first = false;
                column_names = row
                    .column_info()
                    .iter()
                    .map(|c| c.name().to_string())
                    .collect();
            }
            let row_data = decode_oracle_row(&row, &column_names);
            result_rows.push(row_data);
        }

        let row_count = result_rows.len();

        Ok(QueryResult {
            columns: column_names,
            rows: result_rows,
            row_count,
            truncated: false,
            warnings: Vec::new(),
        })
    })();

    conn.set_call_timeout(prev_timeout)?;
    result
}

pub fn explain(conn: &Connection, compiled: &CompiledQuery) -> Result<ExplainResult> {
    let explain_sql = format!("EXPLAIN PLAN FOR {}", compiled.sql);
    let mut stmt = conn.statement(&explain_sql).build()?;
    for (i, param) in compiled.params.iter().enumerate() {
        match param {
            BindValue::Text(s) => stmt.bind(i + 1, &s.as_str())?,
            BindValue::Int(n) => stmt.bind(i + 1, n)?,
            BindValue::Float(f) => stmt.bind(i + 1, f)?,
            BindValue::Bool(b) => stmt.bind(i + 1, b)?,
            BindValue::Null => {
                let null_val: Option<i64> = None;
                stmt.bind(i + 1, &null_val)?;
            }
        }
    }
    stmt.execute(&[])?;

    let cost_row = conn.query_row(
        "SELECT MAX(cost) FROM plan_table WHERE id = 0",
        &[],
    )?;
    let cost: Option<i64> = cost_row.get(0)?;
    let cost = cost.map(|c| c as f64);

    Ok(ExplainResult {
        cost,
        warning: if cost.is_none() {
            Some("Could not parse EXPLAIN cost from PLAN_TABLE".to_string())
        } else {
            None
        },
    })
}

fn decode_oracle_row(row: &OracleRow, column_names: &[String]) -> Vec<serde_json::Value> {
    row.sql_values()
        .iter()
        .take(column_names.len())
        .map(sql_value_to_json)
        .collect()
}

fn sql_value_to_json(val: &oracle::SqlValue) -> serde_json::Value {
    use serde_json::Value;

    if val.is_null().unwrap_or(true) {
        return Value::Null;
    }

    match val.as_inner_value() {
        Ok(InnerValue::Int64(n)) => Value::from(n),
        Ok(InnerValue::UInt64(n)) => Value::from(n),
        Ok(InnerValue::Float(f)) => Value::from(f as f64),
        Ok(InnerValue::Double(f)) => Value::from(f),
        Ok(InnerValue::Boolean(b)) => Value::from(b),
        Ok(InnerValue::Number(s)) => {
            if let Ok(n) = s.parse::<i64>() {
                Value::from(n)
            } else if let Ok(f) = s.parse::<f64>() {
                Value::from(f)
            } else {
                Value::String(s.to_string())
            }
        }
        Ok(InnerValue::Char(bytes)) => Value::String(String::from_utf8_lossy(bytes).into_owned()),
        _ => Value::Null,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validate_connect_field_rejects_empty_host() {
        assert!(validate_connect_field("", "Host").is_err());
    }

    #[test]
    fn validate_connect_field_rejects_special_chars() {
        assert!(validate_connect_field("host;drop", "Host").is_err());
    }

    #[test]
    fn validate_connect_field_accepts_valid_host() {
        assert!(validate_connect_field("db.example.com", "Host").is_ok());
    }
}
