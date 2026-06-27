use crate::query::ir::{
    BindValue, CompiledQuery, FilterGroup, FilterNode, FilterOp, FilterSpec, FilterValue, QuerySpec,
};
use crate::types::Dialect;

pub fn compile(spec: &QuerySpec, dialect: Dialect) -> Result<CompiledQuery, String> {
    let mut params: Vec<BindValue> = Vec::new();
    let mut param_idx = 1u32;

    let select_clause = build_select_clause(spec, dialect);
    let from_clause = build_from_clause(spec, dialect);
    let join_clause = build_join_clause(spec, dialect);
    let where_clause = build_where_root(&spec.filters, dialect, &mut params, &mut param_idx)?;

    let mut sql = String::new();
    sql.push_str("SELECT ");

    if dialect == Dialect::Mssql {
        sql.push_str(&format!("TOP {} ", spec.limit));
    }

    sql.push_str(&select_clause);
    sql.push('\n');
    sql.push_str("FROM ");
    sql.push_str(&from_clause);
    if !join_clause.is_empty() {
        sql.push('\n');
        sql.push_str(&join_clause);
    }
    if !where_clause.is_empty() {
        sql.push('\n');
        sql.push_str("WHERE ");
        sql.push_str(&where_clause);
    }

    match dialect {
        Dialect::Mssql => {}
        Dialect::Oracle => {
            sql.push_str("\nFETCH FIRST ");
            sql.push_str(&spec.limit.to_string());
            sql.push_str(" ROWS ONLY");
        }
        _ => {
            sql.push_str("\nLIMIT ");
            sql.push_str(&spec.limit.to_string());
        }
    }

    Ok(CompiledQuery { sql, params })
}

fn quote(ident: &str, dialect: Dialect) -> String {
    match dialect {
        Dialect::Postgres => format!("\"{}\"", ident.replace('"', "\"\"")),
        Dialect::MySql => format!("`{}`", ident.replace('`', "``")),
        Dialect::Mssql => format!("[{}]", ident.replace(']', "]]")),
        Dialect::Oracle => format!("\"{}\"", ident.replace('"', "\"\"")),
    }
}

fn placeholder(idx: u32, dialect: Dialect) -> String {
    match dialect {
        Dialect::Postgres => format!("${}", idx),
        Dialect::MySql => "?".to_string(),
        Dialect::Mssql => format!("@P{}", idx),
        Dialect::Oracle => format!(":{}", idx),
    }
}

fn build_select_clause(spec: &QuerySpec, dialect: Dialect) -> String {
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

fn build_from_clause(spec: &QuerySpec, dialect: Dialect) -> String {
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

fn build_join_clause(spec: &QuerySpec, dialect: Dialect) -> String {
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
                    found = Some((i, alias, join, true));
                    break;
                } else if right == alias && included.contains(left) {
                    found = Some((i, alias, join, false));
                    break;
                }
            }
            if found.is_some() {
                break;
            }
        }

        match found {
            Some((idx, alias, join, alias_is_left)) => {
                remaining.remove(idx);
                included.insert(alias);

                let table_ref = spec.tables.iter().find(|t| t.alias == alias).unwrap();
                let join_target = format!(
                    "INNER JOIN {}.{} AS {}",
                    quote(&table_ref.schema, dialect),
                    quote(&table_ref.name, dialect),
                    quote(&table_ref.alias, dialect)
                );

                let (left_alias, left_col, right_alias, right_col) = if alias_is_left {
                    (
                        join.right_alias.as_str(),
                        join.right_column.as_str(),
                        join.left_alias.as_str(),
                        join.left_column.as_str(),
                    )
                } else {
                    (
                        join.left_alias.as_str(),
                        join.left_column.as_str(),
                        join.right_alias.as_str(),
                        join.right_column.as_str(),
                    )
                };

                let on_clause = format!(
                    "{}.{} = {}.{}",
                    quote(left_alias, dialect),
                    quote(left_col, dialect),
                    quote(right_alias, dialect),
                    quote(right_col, dialect)
                );

                clauses.push(format!("{} ON {}", join_target, on_clause));
            }
            None => break,
        }
    }

    clauses.join("\n")
}

fn build_where_root(
    group: &FilterGroup,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
) -> Result<String, String> {
    if group.children.is_empty() {
        return Ok(String::new());
    }

    let parts: Vec<String> = group
        .children
        .iter()
        .map(|child| build_where_node(child, dialect, params, param_idx))
        .collect::<Result<Vec<_>, _>>()?
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect();

    if parts.is_empty() {
        return Ok(String::new());
    }

    let connector = match group.connector {
        crate::query::ir::GroupConnector::And => " AND ",
        crate::query::ir::GroupConnector::Or => " OR ",
    };

    Ok(parts.join(connector))
}

fn build_where_node(
    node: &FilterNode,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
) -> Result<String, String> {
    match node {
        FilterNode::Leaf(filter) => build_leaf(filter, dialect, params, param_idx),
        FilterNode::Group(group) => build_group(group, dialect, params, param_idx),
    }
}

fn build_group(
    group: &FilterGroup,
    dialect: Dialect,
    params: &mut Vec<BindValue>,
    param_idx: &mut u32,
) -> Result<String, String> {
    if group.children.is_empty() {
        return Ok(String::new());
    }

    let parts: Vec<String> = group
        .children
        .iter()
        .map(|child| build_where_node(child, dialect, params, param_idx))
        .collect::<Result<Vec<_>, _>>()?
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect();

    if parts.is_empty() {
        return Ok(String::new());
    }

    let connector = match group.connector {
        crate::query::ir::GroupConnector::And => " AND ",
        crate::query::ir::GroupConnector::Or => " OR ",
    };

    let joined = parts.join(connector);
    if parts.len() > 1 {
        Ok(format!("({})", joined))
    } else {
        Ok(joined)
    }
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
        let val = filter
            .value
            .as_ref()
            .ok_or_else(|| format!("Filter on {}.{} is missing its value", filter.table_alias, filter.column))?;
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
                let val = filter
                    .value
                    .as_ref()
                    .ok_or_else(|| format!("Filter on {}.{} is missing its value", filter.table_alias, filter.column))?;
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
                let val = filter
                    .value
                    .as_ref()
                    .ok_or_else(|| format!("Filter on {}.{} is missing its value", filter.table_alias, filter.column))?;
                let list = match val {
                    FilterValue::List(l) => l,
                    _ => return Err("IN expects a list of values".to_string()),
                };
                if list.is_empty() {
                    return Ok("1=0".to_string());
                }
                let mut phs = Vec::with_capacity(list.len());
                for lit in list {
                    let ph = placeholder(*param_idx, dialect);
                    *param_idx += 1;
                    params.push(BindValue::from_literal(lit)?);
                    phs.push(ph);
                }
                let kw = if filter.op == FilterOp::In { "IN" } else { "NOT IN" };
                Ok(format!("{} {} ({})", column_ref, kw, phs.join(", ")))
            }
            FilterOp::Between => {
                let val = filter
                    .value
                    .as_ref()
                    .ok_or_else(|| format!("Filter on {}.{} is missing its value", filter.table_alias, filter.column))?;
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
        Dialect::Mssql => format!("{} LIKE {}", column_ref, ph),
        Dialect::MySql => format!("LOWER({}) LIKE LOWER({})", column_ref, ph),
        Dialect::Oracle => format!("UPPER({}) LIKE UPPER({})", column_ref, ph),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::ir::{
        ColumnSel, FilterGroup, FilterLiteral, FilterNode, FilterOp, FilterSpec, FilterValue,
        GroupConnector, JoinSpec, LiteralKind, QuerySpec, TableRef,
    };
    use crate::types::Dialect;

    fn lit(kind: LiteralKind, text: &str) -> FilterLiteral {
        FilterLiteral {
            kind,
            text: text.into(),
        }
    }

    fn two_table_spec() -> QuerySpec {
        QuerySpec {
            tables: vec![
                TableRef {
                    schema: "public".into(),
                    name: "products".into(),
                    alias: "t0".into(),
                },
                TableRef {
                    schema: "public".into(),
                    name: "categories".into(),
                    alias: "t1".into(),
                },
            ],
            columns: vec![ColumnSel {
                table_alias: "t0".into(),
                column: "id".into(),
            }],
            joins: vec![JoinSpec {
                left_alias: "t0".into(),
                left_column: "category_id".into(),
                right_alias: "t1".into(),
                right_column: "id".into(),
            }],
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "name".into(),
                        op: FilterOp::Eq,
                        value: Some(FilterValue::Single(lit(LiteralKind::Text, "widget"))),
                    }),
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "deleted_at".into(),
                        op: FilterOp::IsNull,
                        value: None,
                    }),
                ],
            },
            limit: 50,
            schema_version: 2,
        }
    }

    #[test]
    fn postgres_compiles_quoting_placeholders_and_limit() {
        let compiled = compile(&two_table_spec(), Dialect::Postgres).unwrap();
        assert!(compiled.sql.contains("SELECT \"t0\".\"id\""));
        assert!(
            compiled
                .sql
                .contains("FROM \"public\".\"products\" AS \"t0\"")
        );
        assert!(
            compiled
                .sql
                .contains("INNER JOIN \"public\".\"categories\" AS \"t1\"")
        );
        assert!(compiled.sql.contains("\"t0\".\"name\" = $1"));
        assert!(compiled.sql.contains("\"t0\".\"deleted_at\" IS NULL"));
        assert!(compiled.sql.ends_with("LIMIT 50"));
        assert_eq!(compiled.params.len(), 1);
        match &compiled.params[0] {
            BindValue::Text(s) => assert_eq!(s, "widget"),
            other => panic!("expected Text, got {:?}", other),
        }
    }

    #[test]
    fn mysql_compiles_backticks_and_question_mark_params() {
        let compiled = compile(&two_table_spec(), Dialect::MySql).unwrap();
        assert!(compiled.sql.contains("SELECT `t0`.`id`"));
        assert!(compiled.sql.contains("`t0`.`name` = ?"));
        assert!(compiled.sql.ends_with("LIMIT 50"));
        assert_eq!(compiled.params.len(), 1);
    }

    #[test]
    fn mssql_compiles_top_instead_of_limit() {
        let compiled = compile(&two_table_spec(), Dialect::Mssql).unwrap();
        assert!(compiled.sql.contains("SELECT TOP 50 "));
        assert!(compiled.sql.contains("[t0].[name] = @P1"));
        assert!(!compiled.sql.contains("LIMIT"));
    }

    #[test]
    fn oracle_compiles_fetch_first() {
        let compiled = compile(&two_table_spec(), Dialect::Oracle).unwrap();
        assert!(compiled.sql.contains("FETCH FIRST 50 ROWS ONLY"));
        assert!(compiled.sql.contains(":1"));
    }

    #[test]
    fn empty_columns_select_star() {
        let mut spec = two_table_spec();
        spec.columns.clear();
        spec.joins.clear();
        spec.filters.children.clear();
        spec.tables.truncate(1);
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(compiled.sql.starts_with("SELECT *"));
    }

    #[test]
    fn nested_groups_emit_parens() {
        let spec = QuerySpec {
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
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![FilterNode::Group(FilterGroup {
                    connector: GroupConnector::Or,
                    children: vec![
                        FilterNode::Leaf(FilterSpec {
                            table_alias: "t0".into(),
                            column: "id".into(),
                            op: FilterOp::Eq,
                            value: Some(FilterValue::Single(lit(LiteralKind::Int, "1"))),
                        }),
                        FilterNode::Leaf(FilterSpec {
                            table_alias: "t0".into(),
                            column: "id".into(),
                            op: FilterOp::Eq,
                            value: Some(FilterValue::Single(lit(LiteralKind::Int, "2"))),
                        }),
                    ],
                })],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(compiled.sql.contains("WHERE (\"t0\".\"id\" = $1 OR \"t0\".\"id\" = $2)"));
        assert_eq!(compiled.params.len(), 2);
    }

    #[test]
    fn in_compiles_multiple_placeholders() {
        let spec = QuerySpec {
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
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![FilterNode::Leaf(FilterSpec {
                    table_alias: "t0".into(),
                    column: "id".into(),
                    op: FilterOp::In,
                    value: Some(FilterValue::List(vec![
                        lit(LiteralKind::Int, "1"),
                        lit(LiteralKind::Int, "2"),
                        lit(LiteralKind::Int, "3"),
                    ])),
                })],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(compiled.sql.contains("\"t0\".\"id\" IN ($1, $2, $3)"));
        assert_eq!(compiled.params.len(), 3);
    }

    #[test]
    fn not_in_compiles() {
        let spec = QuerySpec {
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
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![FilterNode::Leaf(FilterSpec {
                    table_alias: "t0".into(),
                    column: "id".into(),
                    op: FilterOp::NotIn,
                    value: Some(FilterValue::List(vec![
                        lit(LiteralKind::Int, "4"),
                        lit(LiteralKind::Int, "5"),
                    ])),
                })],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(compiled.sql.contains("\"t0\".\"id\" NOT IN ($1, $2)"));
    }

    #[test]
    fn between_compiles_two_placeholders() {
        let spec = QuerySpec {
            tables: vec![TableRef {
                schema: "public".into(),
                name: "products".into(),
                alias: "t0".into(),
            }],
            columns: vec![ColumnSel {
                table_alias: "t0".into(),
                column: "price".into(),
            }],
            joins: vec![],
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![FilterNode::Leaf(FilterSpec {
                    table_alias: "t0".into(),
                    column: "price".into(),
                    op: FilterOp::Between,
                    value: Some(FilterValue::Pair(
                        lit(LiteralKind::Int, "10"),
                        lit(LiteralKind::Int, "100"),
                    )),
                })],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(compiled.sql.contains("\"t0\".\"price\" BETWEEN $1 AND $2"));
        assert_eq!(compiled.params.len(), 2);
    }

    #[test]
    fn ilike_compiles_per_dialect() {
        let spec = QuerySpec {
            tables: vec![TableRef {
                schema: "public".into(),
                name: "products".into(),
                alias: "t0".into(),
            }],
            columns: vec![ColumnSel {
                table_alias: "t0".into(),
                column: "name".into(),
            }],
            joins: vec![],
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![FilterNode::Leaf(FilterSpec {
                    table_alias: "t0".into(),
                    column: "name".into(),
                    op: FilterOp::Ilike,
                    value: Some(FilterValue::Single(lit(LiteralKind::Text, "widget"))),
                })],
            },
            limit: 100,
            schema_version: 2,
        };
        let pg = compile(&spec, Dialect::Postgres).unwrap();
        assert!(pg.sql.contains("ILIKE"));

        let mysql = compile(&spec, Dialect::MySql).unwrap();
        assert!(mysql.sql.contains("LOWER("));

        let mssql = compile(&spec, Dialect::Mssql).unwrap();
        assert!(mssql.sql.contains("LIKE"));

        let oracle = compile(&spec, Dialect::Oracle).unwrap();
        assert!(oracle.sql.contains("UPPER("));
    }

    #[test]
    fn is_empty_and_is_not_empty_compile() {
        let spec = QuerySpec {
            tables: vec![TableRef {
                schema: "public".into(),
                name: "products".into(),
                alias: "t0".into(),
            }],
            columns: vec![ColumnSel {
                table_alias: "t0".into(),
                column: "name".into(),
            }],
            joins: vec![],
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "name".into(),
                        op: FilterOp::IsEmpty,
                        value: None,
                    }),
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "description".into(),
                        op: FilterOp::IsNotEmpty,
                        value: None,
                    }),
                ],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(compiled.sql.contains("\"t0\".\"name\" = ''"));
        assert!(compiled.sql.contains("\"t0\".\"description\" <> ''"));
        assert!(compiled.params.is_empty());
    }

    #[test]
    fn not_like_compiles() {
        let spec = QuerySpec {
            tables: vec![TableRef {
                schema: "public".into(),
                name: "products".into(),
                alias: "t0".into(),
            }],
            columns: vec![ColumnSel {
                table_alias: "t0".into(),
                column: "name".into(),
            }],
            joins: vec![],
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![FilterNode::Leaf(FilterSpec {
                    table_alias: "t0".into(),
                    column: "name".into(),
                    op: FilterOp::NotLike,
                    value: Some(FilterValue::Single(lit(LiteralKind::Text, "%test%"))),
                })],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(compiled.sql.contains("NOT LIKE"));
    }

    #[test]
    fn mixed_and_or_groups() {
        let spec = QuerySpec {
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
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "active".into(),
                        op: FilterOp::Eq,
                        value: Some(FilterValue::Single(lit(LiteralKind::Bool, "true"))),
                    }),
                    FilterNode::Group(FilterGroup {
                        connector: GroupConnector::Or,
                        children: vec![
                            FilterNode::Leaf(FilterSpec {
                                table_alias: "t0".into(),
                                column: "id".into(),
                                op: FilterOp::Gt,
                                value: Some(FilterValue::Single(lit(LiteralKind::Int, "100"))),
                            }),
                            FilterNode::Leaf(FilterSpec {
                                table_alias: "t0".into(),
                                column: "id".into(),
                                op: FilterOp::Lt,
                                value: Some(FilterValue::Single(lit(LiteralKind::Int, "10"))),
                            }),
                        ],
                    }),
                ],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(
            compiled
                .sql
                .contains("WHERE \"t0\".\"active\" = $1 AND (\"t0\".\"id\" > $2 OR \"t0\".\"id\" < $3)")
        );
        assert_eq!(compiled.params.len(), 3);
    }

    #[test]
    fn typed_params_are_correctly_typed() {
        let spec = QuerySpec {
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
            filters: FilterGroup {
                connector: GroupConnector::And,
                children: vec![
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "id".into(),
                        op: FilterOp::Eq,
                        value: Some(FilterValue::Single(lit(LiteralKind::Int, "42"))),
                    }),
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "price".into(),
                        op: FilterOp::Gt,
                        value: Some(FilterValue::Single(lit(LiteralKind::Float, "9.99"))),
                    }),
                    FilterNode::Leaf(FilterSpec {
                        table_alias: "t0".into(),
                        column: "active".into(),
                        op: FilterOp::Eq,
                        value: Some(FilterValue::Single(lit(LiteralKind::Bool, "true"))),
                    }),
                ],
            },
            limit: 100,
            schema_version: 2,
        };
        let compiled = compile(&spec, Dialect::Postgres).unwrap();
        assert!(matches!(compiled.params[0], BindValue::Int(42)));
        assert!(matches!(compiled.params[1], BindValue::Float(_)));
        assert!(matches!(compiled.params[2], BindValue::Bool(true)));
    }
}
