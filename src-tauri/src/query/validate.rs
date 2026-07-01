use std::collections::HashSet;

use crate::introspect::{ColumnCategory, Schema, classify_column};
use crate::query::ir::{FilterNode, FilterOp, LiteralKind, QuerySpec};

#[path = "validate_helpers.rs"]
mod validate_helpers;

pub const LARGE_LIMIT_WARNING_THRESHOLD: u32 = 1000;
pub const MAX_LIMIT: u32 = 10_000;
pub const DEFAULT_LIMIT: u32 = 100;
pub const MAX_FILTER_DEPTH: usize = 5;
pub const MAX_IN_LIST_SIZE: usize = 1000;
pub const MAX_TEXT_LITERAL_LEN: usize = 10_000;

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

#[derive(Debug, Clone)]
pub struct ValidatedColumn {
    pub table_alias: String,
    pub column: String,
    pub result_alias: String,
}

#[derive(Debug, Clone)]
pub struct ValidatedQuery {
    spec: QuerySpec,
    columns: Vec<ValidatedColumn>,
}

impl ValidatedQuery {
    pub(crate) fn spec(&self) -> &QuerySpec {
        &self.spec
    }

    pub(crate) fn columns(&self) -> &[ValidatedColumn] {
        &self.columns
    }
}

pub fn validate_query(
    spec: &mut QuerySpec,
    schema: &Schema,
    custom_blocked: &[String],
) -> Result<(ValidatedQuery, ValidationOutcome), String> {
    let outcome = validate(spec, schema, custom_blocked)?;
    let selections = if spec.columns.is_empty() {
        spec.tables
            .iter()
            .flat_map(|table_ref| {
                find_table(schema, &table_ref.schema, &table_ref.name)
                    .into_iter()
                    .flat_map(move |table| {
                        table
                            .columns
                            .iter()
                            .map(move |column| crate::query::ir::ColumnSel {
                                table_alias: table_ref.alias.clone(),
                                column: column.name.clone(),
                            })
                    })
            })
            .collect::<Vec<_>>()
    } else {
        spec.columns.clone()
    };
    let mut aliases = HashSet::new();
    let columns = selections
        .into_iter()
        .map(|selection| {
            let base = format!("{}__{}", selection.table_alias, selection.column);
            let mut result_alias = base.clone();
            let mut suffix = 2usize;
            while !aliases.insert(result_alias.clone()) {
                result_alias = format!("{base}__{suffix}");
                suffix += 1;
            }
            ValidatedColumn {
                table_alias: selection.table_alias,
                column: selection.column,
                result_alias,
            }
        })
        .collect();
    Ok((
        ValidatedQuery {
            spec: spec.clone(),
            columns,
        },
        outcome,
    ))
}

pub fn literal_kind_for_column(data_type: &str) -> LiteralKind {
    match classify_column(data_type) {
        ColumnCategory::Integer => LiteralKind::Int,
        ColumnCategory::Decimal => LiteralKind::Decimal,
        ColumnCategory::Bool => LiteralKind::Bool,
        ColumnCategory::Date => LiteralKind::Date,
        ColumnCategory::DateTime => LiteralKind::DateTime,
        ColumnCategory::Text
        | ColumnCategory::Binary
        | ColumnCategory::Json
        | ColumnCategory::Other => LiteralKind::Text,
    }
}

pub fn ops_for_column(data_type: &str) -> &'static [FilterOp] {
    match classify_column(data_type) {
        ColumnCategory::Text => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::Like,
            FilterOp::NotLike,
            FilterOp::Ilike,
            FilterOp::In,
            FilterOp::NotIn,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
            FilterOp::IsEmpty,
            FilterOp::IsNotEmpty,
        ],
        ColumnCategory::Integer | ColumnCategory::Decimal => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::Gt,
            FilterOp::Gte,
            FilterOp::Lt,
            FilterOp::Lte,
            FilterOp::In,
            FilterOp::NotIn,
            FilterOp::Between,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
        ColumnCategory::Bool => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
        ColumnCategory::Date | ColumnCategory::DateTime => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::Gt,
            FilterOp::Gte,
            FilterOp::Lt,
            FilterOp::Lte,
            FilterOp::Between,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
        ColumnCategory::Binary | ColumnCategory::Json | ColumnCategory::Other => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
    }
}

pub fn validate(
    spec: &mut QuerySpec,
    schema: &Schema,
    custom_blocked: &[String],
) -> Result<ValidationOutcome, String> {
    let mut warnings = Vec::new();

    if spec.schema_version != crate::query::ir::CURRENT_SCHEMA_VERSION {
        return Err(format!(
            "Query schema version {} is unsupported; expected {}",
            spec.schema_version,
            crate::query::ir::CURRENT_SCHEMA_VERSION
        ));
    }
    let mut node_ids = HashSet::new();
    validate_helpers::collect_node_ids(&spec.filters, &mut node_ids)?;
    for override_id in spec.connector_overrides.keys() {
        if !node_ids.contains(override_id.as_str()) {
            return Err(format!(
                "Connector override references unknown filter node id '{override_id}'"
            ));
        }
    }

    if spec.tables.is_empty() {
        return Err("At least one table is required".to_string());
    }

    let mut table_aliases: HashSet<&str> = HashSet::new();
    for table in &spec.tables {
        if validate_helpers::is_blocked(&table.schema, custom_blocked) {
            return Err(format!(
                "Schema '{}' is blocked (system/catalog schema)",
                table.schema
            ));
        }

        let _table_info = find_table(schema, &table.schema, &table.name).ok_or_else(|| {
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
                && !find_table(schema, &table.schema, &table.name)
                    .map(|t| t.columns.iter().any(|c| c.name == col.column))
                    .unwrap_or(false)
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

        if !left_col.join_eligible {
            return Err(format!(
                "Join column '{}.{}' is not the leading key of an equality-capable index",
                join.left_alias, join.left_column
            ));
        }
        if !right_col.join_eligible {
            return Err(format!(
                "Join column '{}.{}' is not the leading key of an equality-capable index",
                join.right_alias, join.right_column
            ));
        }
        if left_col.category != right_col.category {
            return Err(format!(
                "Join columns '{}.{}' and '{}.{}' have incompatible types",
                join.left_alias, join.left_column, join.right_alias, join.right_column
            ));
        }
    }

    validate_helpers::validate_node(
        &FilterNode::Group(spec.filters.clone()),
        schema,
        spec,
        &table_aliases,
        0,
        &mut warnings,
    )?;

    if spec.tables.len() > 1 && !validate_helpers::check_join_connectivity(spec) {
        return Err(
            "Not all tables are connected by joins — add joins linking every table".to_string(),
        );
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
    if spec.limit > LARGE_LIMIT_WARNING_THRESHOLD {
        warnings.push(
            "Large result limits are useful for reporting, but filters, selected columns, and indexed predicates keep queries faster and easier to reuse."
                .to_string(),
        );
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
) -> Option<&'a crate::introspect::TableInfo> {
    schema
        .tables
        .iter()
        .find(|t| t.schema == table_schema && t.name == table_name)
}

fn find_table_by_alias<'a>(
    schema: &'a Schema,
    spec: &QuerySpec,
    alias: &str,
) -> Option<&'a crate::introspect::TableInfo> {
    let table_ref = spec.tables.iter().find(|t| t.alias == alias)?;
    find_table(schema, &table_ref.schema, &table_ref.name)
}
