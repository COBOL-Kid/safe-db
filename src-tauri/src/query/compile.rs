use super::validate::ValidatedQuery;
use crate::query::ir::{BindValue, CompiledQuery, QuerySpec};
use crate::types::Dialect;

#[path = "compile_helpers.rs"]
mod compile_helpers;

#[cfg(any(test, feature = "test-helpers"))]
pub fn compile(spec: &QuerySpec, dialect: Dialect) -> Result<CompiledQuery, String> {
    compile_spec(spec, dialect, None)
}

pub fn compile_validated(
    validated: &ValidatedQuery,
    dialect: Dialect,
) -> Result<CompiledQuery, String> {
    compile_spec(validated.spec(), dialect, Some(validated.columns()))
}

fn compile_spec(
    spec: &QuerySpec,
    dialect: Dialect,
    validated_columns: Option<&[super::validate::ValidatedColumn]>,
) -> Result<CompiledQuery, String> {
    let mut params: Vec<BindValue> = Vec::new();
    let mut param_idx = 1u32;

    let select_clause = compile_helpers::build_select_clause(spec, dialect, validated_columns);
    let from_clause = compile_helpers::build_from_clause(spec, dialect);
    let join_clause = compile_helpers::build_join_clause(spec, dialect);
    let where_clause = compile_helpers::build_where_root(
        &spec.filters,
        &spec.connector_overrides,
        dialect,
        &mut params,
        &mut param_idx,
    )?;

    let mut sql = String::new();
    sql.push_str("SELECT ");

    let fetch_limit = spec.limit.saturating_add(1);

    if dialect == Dialect::Mssql {
        sql.push_str(&format!("TOP {} ", fetch_limit));
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
            sql.push_str(&fetch_limit.to_string());
            sql.push_str(" ROWS ONLY");
        }
        _ => {
            sql.push_str("\nLIMIT ");
            sql.push_str(&fetch_limit.to_string());
        }
    }

    Ok(CompiledQuery { sql, params })
}
