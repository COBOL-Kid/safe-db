mod support;

use std::collections::BTreeMap;

use safe_db_lib::introspect::ColumnCategory;
use safe_db_lib::introspect::classify_column;
use safe_db_lib::query::compile::compile;
use safe_db_lib::query::ir::{
    CURRENT_SCHEMA_VERSION, ColumnSel, FilterGroup, FilterNode, FilterOp, FilterSpec, FilterValue,
    GroupConnector, JoinSpec, LiteralKind, QuerySpec, TableRef,
};
use safe_db_lib::query::validate::{
    DEFAULT_LIMIT, MAX_FILTER_DEPTH, MAX_IN_LIST_SIZE, MAX_LIMIT, literal_kind_for_column,
    ops_for_column, validate,
};
use safe_db_lib::types::Dialect;

#[test]
fn validate_rejects_empty_tables() {
    let mut spec = support::sample_spec();
    spec.tables.clear();
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("At least one table"));
}

#[test]
fn validate_rejects_blocked_system_schema() {
    let mut spec = support::sample_spec();
    spec.tables[0].schema = "pg_catalog".into();
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("blocked"));
}

#[test]
fn validate_rejects_custom_blocked_schema_case_insensitive() {
    let mut spec = support::sample_spec();
    spec.tables[0].schema = "Audit".into();
    let err = validate(&mut spec, &support::sample_schema(), &["audit".into()]).unwrap_err();
    assert!(err.contains("blocked"));
}

#[test]
fn validate_rejects_join_on_non_indexed_column() {
    let mut spec = support::sample_spec();
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
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("leading key"));
}

#[test]
fn validate_accepts_join_on_indexed_columns() {
    let mut spec = support::sample_spec();
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
    let outcome = validate(&mut spec, &support::sample_schema(), &[]).unwrap();
    assert!(outcome.warnings.is_empty());
}

#[test]
fn validate_errors_on_disconnected_tables() {
    let mut spec = support::sample_spec();
    spec.tables.push(TableRef {
        schema: "public".into(),
        name: "categories".into(),
        alias: "t1".into(),
    });
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("connected by joins"));
}

#[test]
fn validate_defaults_zero_limit_and_caps_excess() {
    let mut spec = support::sample_spec();
    spec.limit = 0;
    let outcome = validate(&mut spec, &support::sample_schema(), &[]).unwrap();
    assert_eq!(spec.limit, DEFAULT_LIMIT);
    assert!(outcome.warnings.iter().any(|w| w.contains("defaulted")));

    spec.limit = 5000;
    let outcome = validate(&mut spec, &support::sample_schema(), &[]).unwrap();
    assert_eq!(spec.limit, MAX_LIMIT);
    assert_eq!(outcome.limit, MAX_LIMIT);
}

#[test]
fn validate_warns_when_no_columns_selected() {
    let mut spec = support::sample_spec();
    spec.columns.clear();
    let outcome = validate(&mut spec, &support::sample_schema(), &[]).unwrap();
    assert!(
        outcome
            .warnings
            .iter()
            .any(|w| w.contains("No columns selected"))
    );
}

#[test]
fn validate_rejects_filter_missing_value() {
    let mut spec = support::sample_spec();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf(FilterOp::Eq, None)));
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("requires a value"));
}

#[test]
fn validate_rejects_ilike_on_numeric_column() {
    let mut spec = support::sample_spec();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "id",
            FilterOp::Ilike,
            Some(FilterValue::Single(support::lit(LiteralKind::Text, "foo"))),
        )));
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("not applicable"));
}

#[test]
fn validate_accepts_is_empty_on_text_column() {
    let mut spec = support::sample_spec();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "name",
            FilterOp::IsEmpty,
            None,
        )));
    let outcome = validate(&mut spec, &support::sample_schema(), &[]).unwrap();
    assert!(
        outcome
            .warnings
            .iter()
            .all(|w| !w.contains("not applicable"))
    );
}

#[test]
fn validate_rejects_in_with_empty_list() {
    let mut spec = support::sample_spec();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(vec![])),
        )));
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("empty value list"));
}

#[test]
fn validate_rejects_between_missing_pair() {
    let mut spec = support::sample_spec();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "id",
            FilterOp::Between,
            Some(FilterValue::Single(support::lit(LiteralKind::Int, "5"))),
        )));
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("range"));
}

#[test]
fn validate_rejects_non_integer_value_for_int_column() {
    let mut spec = support::sample_spec();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "id",
            FilterOp::Eq,
            Some(FilterValue::Single(support::lit(LiteralKind::Int, "abc"))),
        )));
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("not a valid integer"));
}

#[test]
fn validate_rejects_text_literal_kind_for_int_column() {
    let mut spec = support::sample_spec();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "id",
            FilterOp::Eq,
            Some(FilterValue::Single(support::lit(LiteralKind::Text, "123"))),
        )));
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("expected Int"), "got: {err}");
}

#[test]
fn validate_accepts_nested_groups() {
    let mut spec = support::sample_spec();
    spec.filters.connector = GroupConnector::And;
    spec.filters.children.push(FilterNode::Group(support::group(
        GroupConnector::Or,
        vec![
            FilterNode::Leaf(support::leaf_on(
                "id",
                FilterOp::Eq,
                Some(FilterValue::Single(support::lit(LiteralKind::Int, "1"))),
            )),
            FilterNode::Leaf(support::leaf_on(
                "id",
                FilterOp::Eq,
                Some(FilterValue::Single(support::lit(LiteralKind::Int, "2"))),
            )),
        ],
    )));
    let outcome = validate(&mut spec, &support::sample_schema(), &[]).unwrap();
    assert!(outcome.warnings.is_empty());
}

#[test]
fn validate_warns_on_empty_group() {
    let mut spec = support::sample_spec();
    spec.filters.children.push(FilterNode::Group(support::group(
        GroupConnector::Or,
        vec![],
    )));
    let outcome = validate(&mut spec, &support::sample_schema(), &[]).unwrap();
    assert!(outcome.warnings.iter().any(|w| w.contains("no conditions")));
}

#[test]
fn validate_rejects_excessive_nesting() {
    let mut spec = support::sample_spec();
    let mut deepest: &mut FilterGroup = &mut spec.filters;
    for _ in 0..(MAX_FILTER_DEPTH + 2) {
        deepest.children.push(FilterNode::Group(support::group(
            GroupConnector::And,
            vec![],
        )));
        match deepest.children.last_mut().unwrap() {
            FilterNode::Group(g) => deepest = g,
            _ => break,
        }
    }
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("maximum depth"));
}

#[test]
fn classify_column_covers_common_types() {
    assert_eq!(classify_column("int"), ColumnCategory::Integer);
    assert_eq!(classify_column("INTEGER"), ColumnCategory::Integer);
    assert_eq!(classify_column("number"), ColumnCategory::Decimal);
    assert_eq!(classify_column("NUMBER"), ColumnCategory::Decimal);
    assert_eq!(classify_column("varchar"), ColumnCategory::Text);
    assert_eq!(classify_column("VARCHAR2"), ColumnCategory::Text);
    assert_eq!(classify_column("boolean"), ColumnCategory::Bool);
    assert_eq!(classify_column("date"), ColumnCategory::Date);
    assert_eq!(
        classify_column("timestamp without time zone"),
        ColumnCategory::DateTime
    );
    assert_eq!(classify_column("datetime"), ColumnCategory::DateTime);
    assert_eq!(literal_kind_for_column("int"), LiteralKind::Int);
    assert!(ops_for_column("int").contains(&FilterOp::Between));
}

#[test]
fn in_list_at_max_size_is_accepted() {
    let mut spec = support::sample_spec();
    let values: Vec<_> = (0..MAX_IN_LIST_SIZE)
        .map(|i| support::lit(LiteralKind::Int, &i.to_string()))
        .collect();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(values)),
        )));
    validate(&mut spec, &support::sample_schema(), &[]).unwrap();
}

#[test]
fn in_list_over_max_size_is_rejected() {
    let mut spec = support::sample_spec();
    let values: Vec<_> = (0..=MAX_IN_LIST_SIZE)
        .map(|i| support::lit(LiteralKind::Int, &i.to_string()))
        .collect();
    spec.filters
        .children
        .push(FilterNode::Leaf(support::leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(values)),
        )));
    let err = validate(&mut spec, &support::sample_schema(), &[]).unwrap_err();
    assert!(err.contains("too many values"));
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
    assert!(compiled.sql.ends_with("LIMIT 51"));
    assert_eq!(compiled.params.len(), 1);
}

#[test]
fn mysql_compiles_backticks_and_question_mark_params() {
    let compiled = compile(&two_table_spec(), Dialect::MySql).unwrap();
    assert!(compiled.sql.contains("SELECT `t0`.`id`"));
    assert!(compiled.sql.contains("`t0`.`name` = ?"));
    assert!(compiled.sql.ends_with("LIMIT 51"));
    assert_eq!(compiled.params.len(), 1);
}

#[test]
fn mssql_compiles_top_instead_of_limit() {
    let compiled = compile(&two_table_spec(), Dialect::Mssql).unwrap();
    assert!(compiled.sql.contains("SELECT TOP 51 "));
    assert!(compiled.sql.contains("[t0].[name] = @P1"));
    assert!(!compiled.sql.contains("LIMIT"));
}

#[test]
fn oracle_compiles_fetch_first() {
    let compiled = compile(&two_table_spec(), Dialect::Oracle).unwrap();
    assert!(compiled.sql.contains("FETCH FIRST 51 ROWS ONLY"));
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
            id: "g0".into(),
            connector: GroupConnector::And,
            children: vec![FilterNode::Group(FilterGroup {
                id: "g1".into(),
                connector: GroupConnector::Or,
                children: vec![
                    FilterNode::Leaf(FilterSpec {
                        id: "l0".into(),
                        table_alias: "t0".into(),
                        column: "id".into(),
                        op: FilterOp::Eq,
                        value: Some(FilterValue::Single(support::lit(LiteralKind::Int, "1"))),
                    }),
                    FilterNode::Leaf(FilterSpec {
                        id: "l1".into(),
                        table_alias: "t0".into(),
                        column: "id".into(),
                        op: FilterOp::Eq,
                        value: Some(FilterValue::Single(support::lit(LiteralKind::Int, "2"))),
                    }),
                ],
            })],
        },
        limit: 100,
        connector_overrides: BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
    };
    let compiled = compile(&spec, Dialect::Postgres).unwrap();
    assert!(
        compiled
            .sql
            .contains("WHERE (\"t0\".\"id\" = $1 OR \"t0\".\"id\" = $2)")
    );
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
            id: "g0".into(),
            connector: GroupConnector::And,
            children: vec![FilterNode::Leaf(FilterSpec {
                id: "l0".into(),
                table_alias: "t0".into(),
                column: "id".into(),
                op: FilterOp::In,
                value: Some(FilterValue::List(vec![
                    support::lit(LiteralKind::Int, "1"),
                    support::lit(LiteralKind::Int, "2"),
                    support::lit(LiteralKind::Int, "3"),
                ])),
            })],
        },
        limit: 100,
        connector_overrides: BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
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
            id: "g0".into(),
            connector: GroupConnector::And,
            children: vec![FilterNode::Leaf(FilterSpec {
                id: "l0".into(),
                table_alias: "t0".into(),
                column: "id".into(),
                op: FilterOp::NotIn,
                value: Some(FilterValue::List(vec![
                    support::lit(LiteralKind::Int, "4"),
                    support::lit(LiteralKind::Int, "5"),
                ])),
            })],
        },
        limit: 100,
        connector_overrides: BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
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
            id: "g0".into(),
            connector: GroupConnector::And,
            children: vec![FilterNode::Leaf(FilterSpec {
                id: "l0".into(),
                table_alias: "t0".into(),
                column: "price".into(),
                op: FilterOp::Between,
                value: Some(FilterValue::Pair(
                    support::lit(LiteralKind::Int, "10"),
                    support::lit(LiteralKind::Int, "100"),
                )),
            })],
        },
        limit: 100,
        connector_overrides: BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
    };
    let compiled = compile(&spec, Dialect::Postgres).unwrap();
    assert!(compiled.sql.contains("\"t0\".\"price\" BETWEEN $1 AND $2"));
    assert_eq!(compiled.params.len(), 2);
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
            id: "g0".into(),
            connector: GroupConnector::And,
            children: vec![
                FilterNode::Leaf(FilterSpec {
                    id: "l0".into(),
                    table_alias: "t0".into(),
                    column: "name".into(),
                    op: FilterOp::Eq,
                    value: Some(FilterValue::Single(support::lit(
                        LiteralKind::Text,
                        "widget",
                    ))),
                }),
                FilterNode::Leaf(FilterSpec {
                    id: "l1".into(),
                    table_alias: "t0".into(),
                    column: "deleted_at".into(),
                    op: FilterOp::IsNull,
                    value: None,
                }),
            ],
        },
        limit: 50,
        connector_overrides: BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
    }
}
