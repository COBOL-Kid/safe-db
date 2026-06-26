use crate::query::ir::{CompiledQuery, QuerySpec};
use crate::types::Dialect;

pub fn compile(spec: &QuerySpec, dialect: Dialect) -> CompiledQuery {
    let mut params: Vec<String> = Vec::new();
    let mut param_idx = 1u32;

    let select_clause = build_select_clause(spec, dialect);
    let from_clause = build_from_clause(spec, dialect);
    let join_clause = build_join_clause(spec, dialect);
    let where_clause = build_where_clause(spec, dialect, &mut params, &mut param_idx);

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

    CompiledQuery { sql, params }
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

fn build_where_clause(
    spec: &QuerySpec,
    dialect: Dialect,
    params: &mut Vec<String>,
    param_idx: &mut u32,
) -> String {
    let conditions: Vec<String> = spec
        .filters
        .iter()
        .map(|f| {
            let column_ref = format!(
                "{}.{}",
                quote(&f.table_alias, dialect),
                quote(&f.column, dialect)
            );
            if !f.op.needs_value() {
                format!("{} {}", column_ref, f.op.sql())
            } else {
                let ph = placeholder(*param_idx, dialect);
                *param_idx += 1;
                if let Some(val) = &f.value {
                    params.push(val.clone());
                } else {
                    params.push(String::new());
                }
                format!("{} {} {}", column_ref, f.op.sql(), ph)
            }
        })
        .collect();

    conditions.join(" AND ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::ir::{ColumnSel, FilterOp, FilterSpec, JoinSpec, QuerySpec, TableRef};
    use crate::types::Dialect;

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
            filters: vec![
                FilterSpec {
                    table_alias: "t0".into(),
                    column: "name".into(),
                    op: FilterOp::Eq,
                    value: Some("widget".into()),
                },
                FilterSpec {
                    table_alias: "t0".into(),
                    column: "deleted_at".into(),
                    op: FilterOp::IsNull,
                    value: None,
                },
            ],
            limit: 50,
        }
    }

    #[test]
    fn postgres_compiles_quoting_placeholders_and_limit() {
        let compiled = compile(&two_table_spec(), Dialect::Postgres);
        assert!(compiled.sql.contains("SELECT \"t0\".\"id\""));
        assert!(compiled.sql.contains("FROM \"public\".\"products\" AS \"t0\""));
        assert!(compiled.sql.contains("INNER JOIN \"public\".\"categories\" AS \"t1\""));
        assert!(compiled.sql.contains("\"t0\".\"name\" = $1"));
        assert!(compiled.sql.contains("\"t0\".\"deleted_at\" IS NULL"));
        assert!(compiled.sql.ends_with("LIMIT 50"));
        assert_eq!(compiled.params, vec!["widget"]);
    }

    #[test]
    fn mysql_compiles_backticks_and_question_mark_params() {
        let compiled = compile(&two_table_spec(), Dialect::MySql);
        assert!(compiled.sql.contains("SELECT `t0`.`id`"));
        assert!(compiled.sql.contains("`t0`.`name` = ?"));
        assert!(compiled.sql.ends_with("LIMIT 50"));
        assert_eq!(compiled.params, vec!["widget"]);
    }

    #[test]
    fn mssql_compiles_top_instead_of_limit() {
        let compiled = compile(&two_table_spec(), Dialect::Mssql);
        assert!(compiled.sql.contains("SELECT TOP 50 "));
        assert!(compiled.sql.contains("[t0].[name] = @P1"));
        assert!(!compiled.sql.contains("LIMIT"));
    }

    #[test]
    fn oracle_compiles_fetch_first() {
        let compiled = compile(&two_table_spec(), Dialect::Oracle);
        assert!(compiled.sql.contains("FETCH FIRST 50 ROWS ONLY"));
        assert!(compiled.sql.contains(":1"));
    }

    #[test]
    fn empty_columns_select_star() {
        let mut spec = two_table_spec();
        spec.columns.clear();
        spec.joins.clear();
        spec.filters.clear();
        spec.tables.truncate(1);
        let compiled = compile(&spec, Dialect::Postgres);
        assert!(compiled.sql.starts_with("SELECT *"));
    }
}
