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

#[derive(Debug)]
pub struct ValidationOutcome {
    pub warnings: Vec<String>,
    pub limit: u32,
}

pub fn validate(
    spec: &mut QuerySpec,
    schema: &Schema,
    custom_blocked: &[String],
) -> Result<ValidationOutcome, String> {
    let mut warnings = Vec::new();

    if spec.tables.is_empty() {
        return Err("At least one table is required".to_string());
    }

    let mut table_aliases: HashSet<&str> = HashSet::new();
    for table in &spec.tables {
        if is_blocked(&table.schema, custom_blocked) {
            return Err(format!(
                "Schema '{}' is blocked (system/catalog schema)",
                table.schema
            ));
        }

        let table_info = find_table(schema, &table.schema, &table.name).ok_or_else(|| {
            format!(
                "Table '{}.{}' not found in schema",
                table.schema, table.name
            )
        })?;

        if !table_aliases.insert(table.alias.as_str()) {
            return Err(format!("Duplicate table alias '{}'", table.alias));
        }

        for col in &spec.columns {
            if col.table_alias == table.alias
                && !table_info.columns.iter().any(|c| c.name == col.column)
            {
                return Err(format!(
                    "Column '{}.{}' does not exist",
                    table.alias, col.column
                ));
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
            return Err(format!(
                "Join references unknown table alias '{}'",
                join.left_alias
            ));
        }
        if !table_aliases.contains(join.right_alias.as_str()) {
            return Err(format!(
                "Join references unknown table alias '{}'",
                join.right_alias
            ));
        }

        let left_table = find_table_by_alias(schema, spec, &join.left_alias)
            .ok_or_else(|| format!("Cannot resolve table for alias '{}'", join.left_alias))?;
        let right_table = find_table_by_alias(schema, spec, &join.right_alias)
            .ok_or_else(|| format!("Cannot resolve table for alias '{}'", join.right_alias))?;

        let left_col = left_table
            .columns
            .iter()
            .find(|c| c.name == join.left_column)
            .ok_or_else(|| {
                format!(
                    "Join column '{}.{}' does not exist",
                    join.left_alias, join.left_column
                )
            })?;
        let right_col = right_table
            .columns
            .iter()
            .find(|c| c.name == join.right_column)
            .ok_or_else(|| {
                format!(
                    "Join column '{}.{}' does not exist",
                    join.right_alias, join.right_column
                )
            })?;

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

    Ok(ValidationOutcome {
        warnings,
        limit: spec.limit,
    })
}

fn find_table<'a>(
    schema: &'a Schema,
    table_schema: &str,
    table_name: &str,
) -> Option<&'a TableInfo> {
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

fn is_blocked(schema: &str, custom: &[String]) -> bool {
    if BLOCKED_SCHEMAS.contains(&schema) {
        return true;
    }
    custom.iter().any(|s| s == schema)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::introspect::{ColumnInfo, Schema, TableInfo};
    use crate::query::ir::{ColumnSel, FilterOp, FilterSpec, JoinSpec, QuerySpec, TableRef};

    fn sample_schema() -> Schema {
        Schema {
            tables: vec![
                TableInfo {
                    schema: "public".into(),
                    name: "products".into(),
                    columns: vec![
                        ColumnInfo {
                            name: "id".into(),
                            data_type: "int".into(),
                            nullable: false,
                            is_indexed: true,
                        },
                        ColumnInfo {
                            name: "name".into(),
                            data_type: "text".into(),
                            nullable: true,
                            is_indexed: false,
                        },
                    ],
                    indexes: vec![],
                },
                TableInfo {
                    schema: "public".into(),
                    name: "categories".into(),
                    columns: vec![ColumnInfo {
                        name: "id".into(),
                        data_type: "int".into(),
                        nullable: false,
                        is_indexed: true,
                    }],
                    indexes: vec![],
                },
            ],
        }
    }

    fn base_spec() -> QuerySpec {
        QuerySpec {
            tables: vec![TableRef {
                schema: "public".into(),
                name: "products".into(),
                alias: "t0".into(),
            }],
            columns: vec![ColumnSel {
                table_alias: "t0".into(),
                column: "id".into(),
            }],
            joins: vec![],
            filters: vec![],
            limit: 100,
        }
    }

    #[test]
    fn rejects_empty_tables() {
        let mut spec = base_spec();
        spec.tables.clear();
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("At least one table"));
    }

    #[test]
    fn rejects_blocked_system_schema() {
        let mut spec = base_spec();
        spec.tables[0].schema = "pg_catalog".into();
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("blocked"));
    }

    #[test]
    fn rejects_custom_blocked_schema() {
        let mut spec = base_spec();
        spec.tables[0].schema = "audit".into();
        let err = validate(&mut spec, &sample_schema(), &["audit".into()]).unwrap_err();
        assert!(err.contains("blocked"));
    }

    #[test]
    fn rejects_join_on_non_indexed_column() {
        let mut spec = base_spec();
        spec.tables.push(TableRef {
            schema: "public".into(),
            name: "categories".into(),
            alias: "t1".into(),
        });
        spec.joins.push(JoinSpec {
            left_alias: "t0".into(),
            left_column: "name".into(),
            right_alias: "t1".into(),
            right_column: "id".into(),
        });
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("not indexed"));
    }

    #[test]
    fn accepts_join_on_indexed_columns() {
        let mut spec = base_spec();
        spec.tables.push(TableRef {
            schema: "public".into(),
            name: "categories".into(),
            alias: "t1".into(),
        });
        spec.joins.push(JoinSpec {
            left_alias: "t0".into(),
            left_column: "id".into(),
            right_alias: "t1".into(),
            right_column: "id".into(),
        });
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(outcome.warnings.is_empty());
    }

    #[test]
    fn warns_on_disconnected_tables() {
        let mut spec = base_spec();
        spec.tables.push(TableRef {
            schema: "public".into(),
            name: "categories".into(),
            alias: "t1".into(),
        });
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(outcome.warnings.iter().any(|w| w.contains("Cartesian")));
    }

    #[test]
    fn defaults_zero_limit_and_caps_excess() {
        let mut spec = base_spec();
        spec.limit = 0;
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert_eq!(spec.limit, DEFAULT_LIMIT);
        assert!(outcome.warnings.iter().any(|w| w.contains("defaulted")));

        spec.limit = 5000;
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert_eq!(spec.limit, MAX_LIMIT);
        assert_eq!(outcome.limit, MAX_LIMIT);
    }

    #[test]
    fn warns_when_no_columns_selected() {
        let mut spec = base_spec();
        spec.columns.clear();
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(
            outcome
                .warnings
                .iter()
                .any(|w| w.contains("No columns selected"))
        );
    }

    #[test]
    fn rejects_filter_missing_value() {
        let mut spec = base_spec();
        spec.filters.push(FilterSpec {
            table_alias: "t0".into(),
            column: "name".into(),
            op: FilterOp::Eq,
            value: None,
        });
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("requires a value"));
    }
}
