use anyhow::Result;
use oracle::sql_type::{InnerValue, ToSql};
use oracle::{Connection, Row as OracleRow};
use std::time::Duration;

use crate::adapters::{ExplainResult, columns_from_compiled_sql};
use crate::introspect::{ColumnInfo, IndexInfo, Schema, TableInfo, mark_indexed_columns};
use crate::query::ir::{BindValue, CompiledQuery, QueryResult, ResultCell, ResultColumn};
use crate::types::{ConnectionDef, TransportSecurityMode};

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

pub(crate) fn encode_connect_query_value(value: &str) -> String {
    let mut encoded = String::with_capacity(value.len());
    for byte in value.bytes() {
        let ch = byte as char;
        if ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_' | '.' | '/') {
            encoded.push(ch);
        } else {
            encoded.push('%');
            encoded.push_str(&format!("{byte:02X}"));
        }
    }
    encoded
}

pub(crate) fn validate_connect_field(field: &str, label: &str) -> Result<()> {
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

pub fn connect(def: &ConnectionDef, password: &str) -> Result<Connection> {
    validate_connect_field(&def.host, "Host")?;
    validate_connect_field(&def.database, "Database")?;
    if def.port == 0 {
        anyhow::bail!("Port must be between 1 and 65535");
    }

    let conn_str = match def.transport_security.mode {
        TransportSecurityMode::Disabled => {
            format!("//{}:{}/{}", def.host, def.port, def.database)
        }
        _ => {
            let wallet = def
                .transport_security
                .oracle_wallet_location
                .as_deref()
                .ok_or_else(|| anyhow::anyhow!("Oracle TCPS requires a wallet location"))?;
            let wallet = encode_connect_query_value(wallet);
            format!(
                "tcps://{}:{}/{}?wallet_location={wallet}",
                def.host, def.port, def.database
            )
        }
    };
    let conn = Connection::connect(&def.username, password, conn_str)?;
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
            ..ColumnInfo::default()
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
            kind: "NORMAL".to_string(),
            supports_equality: true,
            is_unique: is_unique == 1,
            is_primary: is_primary == 1,
            ..IndexInfo::default()
        });
        entry.columns.push(column_name);
    }

    Ok(index_map.into_values().collect())
}

fn bind_statement(conn: &Connection, compiled: &CompiledQuery) -> Result<oracle::Statement> {
    let mut stmt = conn.statement(&compiled.sql).build()?;
    for (i, param) in compiled.params.iter().enumerate() {
        match param {
            BindValue::Text(s) => stmt.bind(i + 1, &s.as_str())?,
            BindValue::Int(n) => stmt.bind(i + 1, n)?,
            BindValue::Decimal(n) => stmt.bind(i + 1, &n.to_string().as_str())?,
            BindValue::Float(f) => stmt.bind(i + 1, f)?,
            BindValue::Bool(b) => stmt.bind(i + 1, b)?,
            BindValue::Date(d) => stmt.bind(i + 1, &d.to_string().as_str())?,
            BindValue::DateTime(dt) => stmt.bind(i + 1, &dt.to_string().as_str())?,
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

    let mut transaction_started = false;
    let result = (|| {
        conn.execute("SET TRANSACTION READ ONLY", &[])?;
        transaction_started = true;

        let mut stmt = bind_statement(conn, compiled)?;
        let rows = stmt.query(&[])?;

        let mut columns: Vec<ResultColumn> = Vec::new();
        let mut result_rows = Vec::new();

        let mut first = true;
        for row_result in rows {
            let row = row_result?;
            if first {
                first = false;
                columns = row
                    .column_info()
                    .iter()
                    .map(|c| ResultColumn::new(c.name(), c.oracle_type().to_string()))
                    .collect();
            }
            let row_data = decode_oracle_row(&row, columns.len());
            result_rows.push(row_data);
        }
        if first {
            columns = columns_from_compiled_sql(&compiled.sql, crate::types::Dialect::Oracle)
                .into_iter()
                .map(|name| ResultColumn::new(name, "unknown"))
                .collect();
        }

        let row_count = result_rows.len();

        let _ = row_count;
        Ok(QueryResult::from_rows(columns, result_rows))
    })();

    let result = match result {
        Ok(result) => match conn.execute("COMMIT", &[]) {
            Ok(_) => Ok(result),
            Err(error) => Err(anyhow::anyhow!(error)),
        },
        Err(error) => {
            if transaction_started && let Err(rollback_error) = conn.execute("ROLLBACK", &[]) {
                log::warn!("failed to rollback Oracle read-only transaction: {rollback_error}");
            }
            Err(error)
        }
    };

    match (result, conn.set_call_timeout(prev_timeout)) {
        (Ok(result), Ok(())) => Ok(result),
        (Ok(_), Err(error)) => Err(error.into()),
        (Err(error), Ok(())) => Err(error),
        (Err(error), Err(timeout_error)) => {
            log::warn!("failed to restore Oracle call timeout after query error: {timeout_error}");
            Err(error)
        }
    }
}

pub fn explain(conn: &Connection, compiled: &CompiledQuery) -> Result<ExplainResult> {
    let statement_id = format!("safedb_{}", uuid::Uuid::new_v4().simple());
    let explain_sql = format!(
        "EXPLAIN PLAN SET STATEMENT_ID = '{}' FOR {}",
        statement_id, compiled.sql
    );
    let mut stmt = conn.statement(&explain_sql).build()?;
    for (i, param) in compiled.params.iter().enumerate() {
        match param {
            BindValue::Text(s) => stmt.bind(i + 1, &s.as_str())?,
            BindValue::Int(n) => stmt.bind(i + 1, n)?,
            BindValue::Decimal(n) => stmt.bind(i + 1, &n.to_string().as_str())?,
            BindValue::Float(f) => stmt.bind(i + 1, f)?,
            BindValue::Bool(b) => stmt.bind(i + 1, b)?,
            BindValue::Date(d) => stmt.bind(i + 1, &d.to_string().as_str())?,
            BindValue::DateTime(dt) => stmt.bind(i + 1, &dt.to_string().as_str())?,
            BindValue::Null => {
                let null_val: Option<i64> = None;
                stmt.bind(i + 1, &null_val)?;
            }
        }
    }
    stmt.execute(&[])?;

    let cost_row = conn.query_row(
        "SELECT MAX(cost) FROM plan_table WHERE statement_id = :1 AND id = 0",
        &[&statement_id],
    )?;
    let cost: Option<i64> = cost_row.get(0)?;
    let cost = cost.map(|c| c as f64);
    if let Err(error) = conn.execute(
        "DELETE FROM plan_table WHERE statement_id = :1",
        &[&statement_id],
    ) {
        log::warn!("failed to clean Oracle PLAN_TABLE rows: {error}");
    }

    Ok(match cost {
        Some(cost) => ExplainResult::Estimated(cost),
        None => {
            ExplainResult::Unavailable("Could not parse EXPLAIN cost from PLAN_TABLE".to_string())
        }
    })
}

fn decode_oracle_row(row: &OracleRow, column_count: usize) -> Vec<ResultCell> {
    row.sql_values()
        .iter()
        .take(column_count)
        .map(sql_value_to_json)
        .collect()
}

fn sql_value_to_json(val: &oracle::SqlValue) -> ResultCell {
    if val.is_null().unwrap_or(true) {
        return ResultCell::Null;
    }

    match val.as_inner_value() {
        Ok(InnerValue::Int64(n)) => ResultCell::Integer(n),
        Ok(InnerValue::UInt64(n)) if n <= i64::MAX as u64 => ResultCell::Integer(n as i64),
        Ok(InnerValue::UInt64(n)) => ResultCell::text(n.to_string()),
        Ok(InnerValue::Float(f)) => ResultCell::Float(f as f64),
        Ok(InnerValue::Double(f)) => ResultCell::Float(f),
        Ok(InnerValue::Boolean(b)) => ResultCell::Bool(b),
        Ok(InnerValue::Number(s)) => {
            if let Ok(n) = s.parse::<i64>() {
                ResultCell::Integer(n)
            } else if let Ok(f) = s.parse::<f64>() {
                ResultCell::Float(f)
            } else {
                ResultCell::text(s.to_string())
            }
        }
        Ok(InnerValue::Char(bytes)) => {
            ResultCell::text(String::from_utf8_lossy(bytes).into_owned())
        }
        Ok(InnerValue::Raw(bytes)) => ResultCell::binary(bytes),
        _ => ResultCell::text(val.to_string()),
    }
}
