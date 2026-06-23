use std::collections::HashSet;

use crate::introspect::{Schema, TableInfo};
use crate::query::ir::QuerySpec;

pub const MAX_LIMIT: u32 = 1000;
pub const DEFAULT_LIMIT: u32 = 100;

const BLOCKED_SCHEMAS: &[&str] = &[
    "pg_catalog",
    "information_schema",
    "pg_toast",
    "mysql",
    "performance_schema",
    "sys",
    "guest",
    "INFORMATION_SCHEMA",
    "SYS",
    "SYSTEM",
    "OUTLN",
    "DBSNMP",
    "XDB",
    "CTXSYS",
    "MDSYS",
    "OLAPSYS",
    "WMSYS",
    "ORDSYS",
    "EXFSYS",
    "ANONYMOUS",
    "APEX_PUBLIC_USER",
    "FLOWS_FILES",
    "APEX_030200",
    "APEX_040000",
    "APEX_040200",
    "AUDSYS",
    "GSMADMIN_INTERNAL",
    "SYSMAN",
    "DBSFWUSER",
    "APPQOSSYS",
    "ORACLE_OCM",
    "XS$NULL",
    "DVSYS",
    "LBACSYS",
];

pub struct ValidationOutcome {
    pub warnings: Vec<String>,
    pub limit: u32,
}

pub fn validate(spec: &mut QuerySpec, schema: &Schema) -> Result<ValidationOutcome, String> {
    let mut warnings = Vec::new();

    if spec.tables.is_empty() {
        return Err("At least one table is required".to_string());
    }

    let mut table_aliases: HashSet<&str> = HashSet::new();
    for table in &spec.tables {
        if BLOCKED_SCHEMAS.contains(&table.schema.as_str()) {
            return Err(format!("Schema '{}' is blocked (system/catalog schema)", table.schema));
        }

        let table_info = find_table(schema, &table.schema, &table.name)
            .ok_or_else(|| format!("Table '{}.{}' not found in schema", table.schema, table.name))?;

        if !table_aliases.insert(table.alias.as_str()) {
            return Err(format!("Duplicate table alias '{}'", table.alias));
        }

        for col in &spec.columns {
            if col.table_alias == table.alias {
                if !table_info.columns.iter().any(|c| c.name == col.column) {
                    return Err(format!(
                        "Column '{}.{}' does not exist",
                        table.alias, col.column
                    ));
                }
            }
        }
    }

    for col in &spec.columns {
        if !table_aliases.contains(col.table_alias.as_str()) {
            return Err(format!(
                "Column selection references unknown table alias '{}'",
                col.table_alias
            ));
        }
    }

    for join in &spec.joins {
        if !table_aliases.contains(join.left_alias.as_str()) {
            return Err(format!("Join references unknown table alias '{}'", join.left_alias));
        }
        if !table_aliases.contains(join.right_alias.as_str()) {
            return Err(format!("Join references unknown table alias '{}'", join.right_alias));
        }

        let left_table = find_table_by_alias(schema, spec, &join.left_alias)
            .ok_or_else(|| format!("Cannot resolve table for alias '{}'", join.left_alias))?;
        let right_table = find_table_by_alias(schema, spec, &join.right_alias)
            .ok_or_else(|| format!("Cannot resolve table for alias '{}'", join.right_alias))?;

        let left_col = left_table
            .columns
            .iter()
            .find(|c| c.name == join.left_column)
            .ok_or_else(|| format!("Join column '{}.{}' does not exist", join.left_alias, join.left_column))?;
        let right_col = right_table
            .columns
            .iter()
            .find(|c| c.name == join.right_column)
            .ok_or_else(|| format!("Join column '{}.{}' does not exist", join.right_alias, join.right_column))?;

        if !left_col.is_indexed {
            return Err(format!(
                "Join column '{}.{}' is not indexed — only indexed columns may be used for joins",
                join.left_alias, join.left_column
            ));
        }
        if !right_col.is_indexed {
            return Err(format!(
                "Join column '{}.{}' is not indexed — only indexed columns may be used for joins",
                join.right_alias, join.right_column
            ));
        }
    }

    for filter in &spec.filters {
        if !table_aliases.contains(filter.table_alias.as_str()) {
            return Err(format!(
                "Filter references unknown table alias '{}'",
                filter.table_alias
            ));
        }

        let table = find_table_by_alias(schema, spec, &filter.table_alias)
            .ok_or_else(|| format!("Cannot resolve table for alias '{}'", filter.table_alias))?;
        if !table.columns.iter().any(|c| c.name == filter.column) {
            return Err(format!(
                "Filter column '{}.{}' does not exist",
                filter.table_alias, filter.column
            ));
        }

        if filter.op.needs_value() && filter.value.is_none() {
            return Err(format!(
                "Filter on '{}.{}' requires a value",
                filter.table_alias, filter.column
            ));
        }
    }

    if spec.tables.len() > 1 {
        let connected = check_join_connectivity(spec);
        if !connected {
            warnings.push(
                "Multiple tables are present but not all are connected by joins — this may produce a Cartesian product".to_string()
            );
        }
    }

    if spec.limit == 0 {
        spec.limit = DEFAULT_LIMIT;
        warnings.push(format!("Limit was 0; defaulted to {}", DEFAULT_LIMIT));
    }
    if spec.limit > MAX_LIMIT {
        warnings.push(format!(
            "Limit {} exceeds maximum {}; capped to {}",
            spec.limit, MAX_LIMIT, MAX_LIMIT
        ));
        spec.limit = MAX_LIMIT;
    }

    if spec.columns.is_empty() {
        warnings.push("No columns selected — query will select all columns".to_string());
    }

    Ok(ValidationOutcome { warnings, limit: spec.limit })
}

fn find_table<'a>(schema: &'a Schema, table_schema: &str, table_name: &str) -> Option<&'a TableInfo> {
    schema
        .tables
        .iter()
        .find(|t| t.schema == table_schema && t.name == table_name)
}

fn find_table_by_alias<'a>(
    schema: &'a Schema,
    spec: &QuerySpec,
    alias: &str,
) -> Option<&'a TableInfo> {
    let table_ref = spec.tables.iter().find(|t| t.alias == alias)?;
    find_table(schema, &table_ref.schema, &table_ref.name)
}

fn check_join_connectivity(spec: &QuerySpec) -> bool {
    if spec.tables.len() <= 1 {
        return true;
    }

    let mut connected: HashSet<&str> = HashSet::new();
    connected.insert(spec.tables[0].alias.as_str());

    let mut changed = true;
    while changed {
        changed = false;
        for join in &spec.joins {
            let left = join.left_alias.as_str();
            let right = join.right_alias.as_str();
            if connected.contains(left) && !connected.contains(right) {
                connected.insert(right);
                changed = true;
            } else if connected.contains(right) && !connected.contains(left) {
                connected.insert(left);
                changed = true;
            }
        }
    }

    connected.len() == spec.tables.len()
}
