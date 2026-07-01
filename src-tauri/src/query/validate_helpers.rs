use std::collections::HashSet;

use crate::introspect::{Schema, TableInfo};
use crate::query::ir::{
    FilterGroup, FilterLiteral, FilterNode, FilterOp, FilterSpec, FilterValue, LiteralKind,
    QuerySpec, ValueKind,
};
use crate::query::validate::{
    MAX_FILTER_DEPTH, MAX_IN_LIST_SIZE, MAX_TEXT_LITERAL_LEN, literal_kind_for_column,
    ops_for_column,
};

pub(super) fn collect_node_ids<'a>(
    group: &'a FilterGroup,
    node_ids: &mut HashSet<&'a str>,
) -> Result<(), String> {
    insert_node_id(&group.id, node_ids)?;
    for child in &group.children {
        match child {
            FilterNode::Leaf(leaf) => insert_node_id(&leaf.id, node_ids)?,
            FilterNode::Group(nested) => collect_node_ids(nested, node_ids)?,
        }
    }
    Ok(())
}

pub(super) fn validate_node(
    node: &FilterNode,
    schema: &Schema,
    spec: &QuerySpec,
    table_aliases: &HashSet<&str>,
    depth: usize,
    warnings: &mut Vec<String>,
) -> Result<(), String> {
    if depth > MAX_FILTER_DEPTH {
        return Err(format!(
            "Filter nesting exceeds maximum depth of {}",
            MAX_FILTER_DEPTH
        ));
    }

    match node {
        FilterNode::Leaf(filter) => validate_leaf(filter, schema, spec, table_aliases, warnings),
        FilterNode::Group(group) => {
            validate_group(group, schema, spec, table_aliases, depth, warnings)
        }
    }
}

pub(super) fn validate_leaf(
    filter: &FilterSpec,
    schema: &Schema,
    spec: &QuerySpec,
    table_aliases: &HashSet<&str>,
    warnings: &mut Vec<String>,
) -> Result<(), String> {
    if !table_aliases.contains(filter.table_alias.as_str()) {
        return Err(format!(
            "Filter references unknown table alias '{}'",
            filter.table_alias
        ));
    }

    let table = find_table_by_alias(schema, spec, &filter.table_alias)
        .ok_or_else(|| format!("Cannot resolve table for alias '{}'", filter.table_alias))?;

    let col = table
        .columns
        .iter()
        .find(|c| c.name == filter.column)
        .ok_or_else(|| {
            format!(
                "Filter column '{}.{}' does not exist",
                filter.table_alias, filter.column
            )
        })?;

    let allowed = ops_for_column(&col.data_type);
    if !allowed.contains(&filter.op) {
        return Err(format!(
            "Operator '{}' is not applicable to column '{}.{}' (type: {})",
            op_label(&filter.op),
            filter.table_alias,
            filter.column,
            col.data_type
        ));
    }

    if !col.is_indexed {
        let warning = format!(
            "This query may scan more data than expected because it searches the non-indexed field '{}.{}'. Safe DB will still use the row limit and timeout.",
            table.name, col.name
        );
        if !warnings.contains(&warning) {
            warnings.push(warning);
        }
    }

    let value_kind = filter.op.value_kind();
    match value_kind {
        ValueKind::None => {
            if filter.value.is_some() {
                return Err(format!(
                    "Operator '{}' on '{}.{}' should not have a value",
                    op_label(&filter.op),
                    filter.table_alias,
                    filter.column
                ));
            }
        }
        ValueKind::Single => {
            let val = filter.value.as_ref().ok_or_else(|| {
                format!(
                    "Filter on '{}.{}' requires a value",
                    filter.table_alias, filter.column
                )
            })?;
            let lit = expect_single(val, &filter.table_alias, &filter.column)?;
            validate_literal(lit, &col.data_type, &filter.table_alias, &filter.column)?;
        }
        ValueKind::List => {
            let val = filter.value.as_ref().ok_or_else(|| {
                format!(
                    "Filter on '{}.{}' requires a value list",
                    filter.table_alias, filter.column
                )
            })?;
            let list = expect_list(val, &filter.table_alias, &filter.column)?;
            if list.is_empty() {
                return Err(format!(
                    "Filter on '{}.{}' has an empty value list",
                    filter.table_alias, filter.column
                ));
            }
            if list.len() > MAX_IN_LIST_SIZE {
                return Err(format!(
                    "Filter on '{}.{}' has too many values (max {})",
                    filter.table_alias, filter.column, MAX_IN_LIST_SIZE
                ));
            }
            for lit in list {
                validate_literal(lit, &col.data_type, &filter.table_alias, &filter.column)?;
            }
        }
        ValueKind::Pair => {
            let val = filter.value.as_ref().ok_or_else(|| {
                format!(
                    "Filter on '{}.{}' requires a range (from/to)",
                    filter.table_alias, filter.column
                )
            })?;
            let (from, to) = expect_pair(val, &filter.table_alias, &filter.column)?;
            validate_literal(from, &col.data_type, &filter.table_alias, &filter.column)?;
            validate_literal(to, &col.data_type, &filter.table_alias, &filter.column)?;
        }
    }

    Ok(())
}

pub(super) fn validate_group(
    group: &FilterGroup,
    schema: &Schema,
    spec: &QuerySpec,
    table_aliases: &HashSet<&str>,
    depth: usize,
    warnings: &mut Vec<String>,
) -> Result<(), String> {
    if depth > 0 && group.children.is_empty() {
        warnings.push("Filter group has no conditions".to_string());
    }
    if depth > 0 && group.children.len() == 1 && matches!(group.children[0], FilterNode::Leaf(_)) {
        warnings.push("Filter group has only one condition — a group is unnecessary".to_string());
    }
    for child in &group.children {
        validate_node(child, schema, spec, table_aliases, depth + 1, warnings)?;
    }
    Ok(())
}

pub(super) fn validate_literal(
    lit: &FilterLiteral,
    data_type: &str,
    alias: &str,
    column: &str,
) -> Result<(), String> {
    let expected = literal_kind_for_column(data_type);
    if lit.kind != expected {
        return Err(format!(
            "Value for '{}.{}' has type {:?}; expected {:?} for column type {}",
            alias, column, lit.kind, expected, data_type
        ));
    }
    match lit.kind {
        LiteralKind::Text => {
            if lit.text.len() > MAX_TEXT_LITERAL_LEN {
                return Err(format!(
                    "Text value for '{}.{}' exceeds maximum length of {} characters",
                    alias, column, MAX_TEXT_LITERAL_LEN
                ));
            }
            Ok(())
        }
        LiteralKind::Int => {
            lit.text.parse::<i64>().map_err(|_| {
                format!(
                    "'{}' is not a valid integer for '{}.{}'",
                    lit.text, alias, column
                )
            })?;
            Ok(())
        }
        LiteralKind::Decimal => {
            lit.text.parse::<bigdecimal::BigDecimal>().map_err(|_| {
                format!(
                    "'{}' is not a valid decimal for '{}.{}'",
                    lit.text, alias, column
                )
            })?;
            Ok(())
        }
        LiteralKind::Float => {
            lit.text.parse::<f64>().map_err(|_| {
                format!(
                    "'{}' is not a valid number for '{}.{}'",
                    lit.text, alias, column
                )
            })?;
            Ok(())
        }
        LiteralKind::Bool => {
            if lit.text.eq_ignore_ascii_case("true")
                || lit.text.eq_ignore_ascii_case("false")
                || lit.text.eq_ignore_ascii_case("1")
                || lit.text.eq_ignore_ascii_case("0")
                || lit.text.eq_ignore_ascii_case("yes")
                || lit.text.eq_ignore_ascii_case("no")
                || lit.text.is_empty()
            {
                Ok(())
            } else {
                Err(format!(
                    "'{}' is not a valid boolean for '{}.{}'",
                    lit.text, alias, column
                ))
            }
        }
        LiteralKind::Date => crate::query::ir::parse_date_literal(&lit.text)
            .map(|_| ())
            .map_err(|e| format!("{e} for '{}.{}'", alias, column)),
        LiteralKind::DateTime => crate::query::ir::parse_datetime_literal(&lit.text)
            .map(|_| ())
            .map_err(|e| format!("{e} for '{}.{}'", alias, column)),
    }
}

fn insert_node_id<'a>(id: &'a str, node_ids: &mut HashSet<&'a str>) -> Result<(), String> {
    if id.trim().is_empty() {
        return Err("Every filter node must have a non-empty stable id".to_string());
    }
    if !node_ids.insert(id) {
        return Err(format!("Duplicate filter node id '{id}'"));
    }
    Ok(())
}

fn expect_single<'a>(
    val: &'a FilterValue,
    alias: &str,
    column: &str,
) -> Result<&'a FilterLiteral, String> {
    match val {
        FilterValue::Single(lit) => Ok(lit),
        _ => Err(format!(
            "Filter on '{}.{}' expects a single value",
            alias, column
        )),
    }
}

fn expect_list<'a>(
    val: &'a FilterValue,
    alias: &str,
    column: &str,
) -> Result<&'a Vec<FilterLiteral>, String> {
    match val {
        FilterValue::List(list) => Ok(list),
        _ => Err(format!(
            "Filter on '{}.{}' expects a list of values",
            alias, column
        )),
    }
}

fn expect_pair<'a>(
    val: &'a FilterValue,
    alias: &str,
    column: &str,
) -> Result<(&'a FilterLiteral, &'a FilterLiteral), String> {
    match val {
        FilterValue::Pair(from, to) => Ok((from, to)),
        _ => Err(format!(
            "Filter on '{}.{}' expects a range (from/to)",
            alias, column
        )),
    }
}

fn op_label(op: &FilterOp) -> &'static str {
    match op {
        FilterOp::Eq => "=",
        FilterOp::Ne => "<>",
        FilterOp::Gt => ">",
        FilterOp::Gte => ">=",
        FilterOp::Lt => "<",
        FilterOp::Lte => "<=",
        FilterOp::Like => "LIKE",
        FilterOp::NotLike => "NOT LIKE",
        FilterOp::Ilike => "ILIKE",
        FilterOp::In => "IN",
        FilterOp::NotIn => "NOT IN",
        FilterOp::Between => "BETWEEN",
        FilterOp::IsNull => "IS NULL",
        FilterOp::IsNotNull => "IS NOT NULL",
        FilterOp::IsEmpty => "IS EMPTY",
        FilterOp::IsNotEmpty => "IS NOT EMPTY",
    }
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

pub(super) fn check_join_connectivity(spec: &QuerySpec) -> bool {
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

pub(super) fn is_blocked(schema: &str, custom: &[String]) -> bool {
    super::BLOCKED_SCHEMAS
        .iter()
        .any(|blocked| blocked.eq_ignore_ascii_case(schema))
        || custom.iter().any(|s| s.eq_ignore_ascii_case(schema))
}
