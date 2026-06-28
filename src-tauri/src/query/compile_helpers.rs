use std::collections::BTreeMap;

use crate::query::ir::{
    BindValue, FilterGroup, FilterNode, FilterOp, FilterSpec, FilterValue, GroupConnector,
    QuerySpec,
};
use crate::query::validate::ValidatedColumn;
use crate::types::Dialect;

pub(super) fn quote(ident: &str, dialect: Dialect) -> String {
    match dialect {
        Dialect::Postgres => format!("\"{}\"", ident.replace('"', "\"\"")),
        Dialect::MySql => format!("`{}`", ident.replace('`', "``")),
        Dialect::Mssql => format!("[{}]", ident.replace(']', "]]")),
        Dialect::Oracle => format!("\"{}\"", ident.replace('"', "\"\"")),
    }
}

pub(super) fn placeholder(idx: u32, dialect: Dialect) -> String {
    match dialect {
        Dialect::Postgres => format!("${}", idx),
        Dialect::MySql => "?".to_string(),
        Dialect::Mssql => format!("@P{}", idx),
        Dialect::Oracle => format!(":{}", idx),
    }
}

pub(super) fn build_select_clause(
    spec: &QuerySpec,
    dialect: Dialect,
    validated_columns: Option<&[ValidatedColumn]>,
) -> String {
    if let Some(columns) = validated_columns {
        return columns
            .iter()
            .map(|column| {
                format!(
                    "{}.{} AS {}",
                    quote(&column.table_alias, dialect),
                    quote(&column.column, dialect),
                    quote(&column.result_alias, dialect)
                )
            })
            .collect::<Vec<_>>()
            .join(", ");
    }
    if spec.columns.is_empty() {
        return "*".to_string();
    }

    spec.columns
        .iter()
        .map(|c| {
            format!(
                "{}.{}",
                quote(&c.table_alias, dialect),
                quote(&c.column, dialect)
            )
        })
        .collect::<Vec<_>>()
        .join(", ")
}

pub(super) fn build_from_clause(spec: &QuerySpec, dialect: Dialect) -> String {
    if spec.tables.is_empty() {
        return String::new();
    }
    let t = &spec.tables[0];
    format!(
        "{}.{} AS {}",
        quote(&t.schema, dialect),
        quote(&t.name, dialect),
        quote(&t.alias, dialect)
    )
}

pub(super) fn build_join_clause(spec: &QuerySpec, dialect: Dialect) -> String {
    if spec.tables.len() <= 1 {
        return String::new();
    }

    let mut included: std::collections::HashSet<&str> = std::collections::HashSet::new();
    included.insert(spec.tables[0].alias.as_str());

    let mut remaining: Vec<&str> = spec.tables[1..].iter().map(|t| t.alias.as_str()).collect();
    let mut clauses = Vec::new();

    while !remaining.is_empty() {
        let mut found = None;
        for (i, &alias) in remaining.iter().enumerate() {
            for join in &spec.joins {
                let left = join.left_alias.as_str();
                let right = join.right_alias.as_str();
                if left == alias && included.contains(right) {
                    found = Some((i, alias));
                    break;
                } else if right == alias && included.contains(left) {
                    found = Some((i, alias));
                    break;
                }
            }
            if found.is_some() {
                break;
            }
        }

        match found {
            Some((idx, alias)) => {
                remaining.remove(idx);
                included.insert(alias);

                let table_ref = spec.tables.iter().find(|t| t.alias == alias).unwrap();
                let join_target = format!(
                    "INNER JOIN {}.{} AS {}",
                    quote(&table_ref.schema, dialect),
                    quote(&table_ref.name, dialect),
                    quote(&table_ref.alias, dialect)
                );

                let connecting_joins = spec.joins.iter().filter(|candidate| {
                    (candidate.left_alias == alias
                        && included.contains(candidate.right_alias.as_str()))
                        || (candidate.right_alias == alias
                            && included.contains(candidate.left_alias.as_str()))
                });
                let on_clause = connecting_joins
                    .map(|candidate| {
                        format!(
                            "{}.{} = {}.{}",
                            quote(&candidate.left_alias, dialect),
                            quote(&candidate.left_column, dialect),
                            quote(&candidate.right_alias, dialect),
                            quote(&candidate.right_column, dialect)
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(" AND ");

                clauses.push(format!("{} ON {}", join_target, on_clause));
            }
            None => break,
        }
    }

    clauses.join("\n")
}

pub(super) fn build_where_root(
    group: &FilterGroup,
    overrides: &BTreeMap<String, GroupConnector>,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
) -> Result<String, String> {
    join_children(
        group, overrides, dialect, params, param_idx, /* wrap = */ false,
    )
}

fn join_children(
    group: &FilterGroup,
    overrides: &BTreeMap<String, GroupConnector>,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
    wrap: bool,
) -> Result<String, String> {
    if group.children.is_empty() {
        return Ok(String::new());
    }

    let mut parts: Vec<(usize, String)> = Vec::with_capacity(group.children.len());
    for (i, child) in group.children.iter().enumerate() {
        let rendered = build_where_node(child, overrides, dialect, params, param_idx)?;
        if !rendered.is_empty() {
            parts.push((i, rendered));
        }
    }

    if parts.is_empty() {
        return Ok(String::new());
    }

    let mut joined = parts[0].1.clone();
    for (orig_i, part) in parts.iter().skip(1) {
        let child = &group.children[*orig_i];
        let connector = overrides
            .get(child_id(child))
            .copied()
            .unwrap_or(group.connector);
        joined.push_str(connector_sql(connector));
        joined.push_str(part);
    }

    if wrap && parts.len() > 1 {
        Ok(format!("({})", joined))
    } else {
        Ok(joined)
    }
}

fn child_id(node: &FilterNode) -> &str {
    match node {
        FilterNode::Leaf(spec) => &spec.id,
        FilterNode::Group(group) => &group.id,
    }
}

fn connector_sql(connector: GroupConnector) -> &'static str {
    match connector {
        GroupConnector::And => " AND ",
        GroupConnector::Or => " OR ",
    }
}

fn build_where_node(
    node: &FilterNode,
    overrides: &BTreeMap<String, GroupConnector>,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
) -> Result<String, String> {
    match node {
        FilterNode::Leaf(filter) => build_leaf(filter, dialect, params, param_idx),
        FilterNode::Group(group) => build_group(group, overrides, dialect, params, param_idx),
    }
}

fn build_group(
    group: &FilterGroup,
    overrides: &BTreeMap<String, GroupConnector>,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
) -> Result<String, String> {
    join_children(
        group, overrides, dialect, params, param_idx, /* wrap = */ true,
    )
}

fn build_leaf(
    filter: &FilterSpec,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
) -> Result<String, String> {
    let column_ref = format!(
        "{}.{}",
        quote(&filter.table_alias, dialect),
        quote(&filter.column, dialect)
    );

    if let Some(op_str) = filter.op.sql_operator() {
        let val = filter.value.as_ref().ok_or_else(|| {
            format!(
                "Filter on {}.{} is missing its value",
                filter.table_alias, filter.column
            )
        })?;
        let lit = match val {
            FilterValue::Single(l) => l,
            _ => return Err(format!("Operator '{}' expects a single value", op_str)),
        };
        let ph = placeholder(*param_idx, dialect);
        *param_idx += 1;
        params.push(BindValue::from_literal(lit)?);
        Ok(format!("{} {} {}", column_ref, op_str, ph))
    } else {
        match filter.op {
            FilterOp::Ilike => {
                let val = filter.value.as_ref().ok_or_else(|| {
                    format!(
                        "Filter on {}.{} is missing its value",
                        filter.table_alias, filter.column
                    )
                })?;
                let lit = match val {
                    FilterValue::Single(l) => l,
                    _ => return Err("ILIKE expects a single value".to_string()),
                };
                let ph = placeholder(*param_idx, dialect);
                *param_idx += 1;
                params.push(BindValue::from_literal(lit)?);
                Ok(build_ilike(&column_ref, &ph, dialect))
            }
            FilterOp::In | FilterOp::NotIn => {
                let val = filter.value.as_ref().ok_or_else(|| {
                    format!(
                        "Filter on {}.{} is missing its value",
                        filter.table_alias, filter.column
                    )
                })?;
                let list = match val {
                    FilterValue::List(l) => l,
                    _ => return Err("IN expects a list of values".to_string()),
                };
                if list.is_empty() {
                    return Ok(if filter.op == FilterOp::In {
                        "1=0".to_string()
                    } else {
                        "1=1".to_string()
                    });
                }
                let mut phs = Vec::with_capacity(list.len());
                for lit in list {
                    let ph = placeholder(*param_idx, dialect);
                    *param_idx += 1;
                    params.push(BindValue::from_literal(lit)?);
                    phs.push(ph);
                }
                let kw = if filter.op == FilterOp::In {
                    "IN"
                } else {
                    "NOT IN"
                };
                Ok(format!("{} {} ({})", column_ref, kw, phs.join(", ")))
            }
            FilterOp::Between => {
                let val = filter.value.as_ref().ok_or_else(|| {
                    format!(
                        "Filter on {}.{} is missing its value",
                        filter.table_alias, filter.column
                    )
                })?;
                let (from, to) = match val {
                    FilterValue::Pair(f, t) => (f, t),
                    _ => return Err("BETWEEN expects a pair of values".to_string()),
                };
                let ph1 = placeholder(*param_idx, dialect);
                *param_idx += 1;
                params.push(BindValue::from_literal(from)?);
                let ph2 = placeholder(*param_idx, dialect);
                *param_idx += 1;
                params.push(BindValue::from_literal(to)?);
                Ok(format!("{} BETWEEN {} AND {}", column_ref, ph1, ph2))
            }
            FilterOp::IsNull => Ok(format!("{} IS NULL", column_ref)),
            FilterOp::IsNotNull => Ok(format!("{} IS NOT NULL", column_ref)),
            FilterOp::IsEmpty => Ok(format!("{} = ''", column_ref)),
            FilterOp::IsNotEmpty => Ok(format!("{} <> ''", column_ref)),
            _ => Err("Unsupported operator in compiler".to_string()),
        }
    }
}

fn build_ilike(column_ref: &str, ph: &str, dialect: Dialect) -> String {
    match dialect {
        Dialect::Postgres => format!("{} ILIKE {}", column_ref, ph),
        Dialect::Mssql => format!("LOWER({}) LIKE LOWER({})", column_ref, ph),
        Dialect::MySql => format!("LOWER({}) LIKE LOWER({})", column_ref, ph),
        Dialect::Oracle => format!("UPPER({}) LIKE UPPER({})", column_ref, ph),
    }
}
