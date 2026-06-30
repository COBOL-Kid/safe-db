use std::collections::BTreeMap;

use safe_db_lib::query::ir::{
    BindValue, CURRENT_SCHEMA_VERSION, ColumnSel, FilterGroup, FilterLiteral, FilterOp,
    GroupConnector, LiteralKind, QuerySpec, TableRef, ValueKind,
};

#[test]
fn filter_op_value_kind_table() {
    assert_eq!(FilterOp::Eq.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::Ne.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::Gt.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::Gte.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::Lt.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::Lte.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::Like.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::NotLike.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::Ilike.value_kind(), ValueKind::Single);
    assert_eq!(FilterOp::In.value_kind(), ValueKind::List);
    assert_eq!(FilterOp::NotIn.value_kind(), ValueKind::List);
    assert_eq!(FilterOp::Between.value_kind(), ValueKind::Pair);
    assert_eq!(FilterOp::IsNull.value_kind(), ValueKind::None);
    assert_eq!(FilterOp::IsNotNull.value_kind(), ValueKind::None);
    assert_eq!(FilterOp::IsEmpty.value_kind(), ValueKind::None);
    assert_eq!(FilterOp::IsNotEmpty.value_kind(), ValueKind::None);
}

#[test]
fn needs_value_is_inverse_of_value_kind_none() {
    assert!(!FilterOp::IsNull.needs_value());
    assert!(!FilterOp::IsNotNull.needs_value());
    assert!(!FilterOp::IsEmpty.needs_value());
    assert!(!FilterOp::IsNotEmpty.needs_value());
    assert!(FilterOp::Eq.needs_value());
    assert!(FilterOp::Between.needs_value());
    assert!(FilterOp::In.needs_value());
}

#[test]
fn sql_operator_for_comparison_and_like_ops() {
    assert_eq!(FilterOp::Eq.sql_operator(), Some("="));
    assert_eq!(FilterOp::Ne.sql_operator(), Some("<>"));
    assert_eq!(FilterOp::Gt.sql_operator(), Some(">"));
    assert_eq!(FilterOp::Gte.sql_operator(), Some(">="));
    assert_eq!(FilterOp::Lt.sql_operator(), Some("<"));
    assert_eq!(FilterOp::Lte.sql_operator(), Some("<="));
    assert_eq!(FilterOp::Like.sql_operator(), Some("LIKE"));
    assert_eq!(FilterOp::NotLike.sql_operator(), Some("NOT LIKE"));
    assert_eq!(FilterOp::Ilike.sql_operator(), None);
    assert_eq!(FilterOp::In.sql_operator(), None);
    assert_eq!(FilterOp::Between.sql_operator(), None);
    assert_eq!(FilterOp::IsNull.sql_operator(), None);
}

#[test]
fn bind_value_from_literal_text() {
    let lit = FilterLiteral {
        kind: LiteralKind::Text,
        text: "Alice".to_string(),
    };
    match BindValue::from_literal(&lit).unwrap() {
        BindValue::Text(s) => assert_eq!(s, "Alice"),
        other => panic!("expected Text, got {other:?}"),
    }
}

#[test]
fn bind_value_from_literal_int_ok_and_err() {
    let ok = FilterLiteral {
        kind: LiteralKind::Int,
        text: "42".to_string(),
    };
    match BindValue::from_literal(&ok).unwrap() {
        BindValue::Int(n) => assert_eq!(n, 42),
        other => panic!("expected Int, got {other:?}"),
    }
    let bad = FilterLiteral {
        kind: LiteralKind::Int,
        text: "not a number".to_string(),
    };
    let err = BindValue::from_literal(&bad).unwrap_err();
    assert!(err.contains("not a valid integer"), "got: {err}");
}

#[test]
fn bind_value_from_literal_float_ok_and_err() {
    let ok = FilterLiteral {
        kind: LiteralKind::Float,
        text: "1.5".to_string(),
    };
    match BindValue::from_literal(&ok).unwrap() {
        BindValue::Float(f) => assert!((f - 1.5).abs() < 1e-9),
        other => panic!("expected Float, got {other:?}"),
    }
    let bad = FilterLiteral {
        kind: LiteralKind::Float,
        text: "abc".to_string(),
    };
    let err = BindValue::from_literal(&bad).unwrap_err();
    assert!(err.contains("not a valid number"), "got: {err}");
}

#[test]
fn bind_value_from_literal_decimal_ok_and_err() {
    let ok = FilterLiteral {
        kind: LiteralKind::Decimal,
        text: "12345678901234567890.1234".to_string(),
    };
    match BindValue::from_literal(&ok).unwrap() {
        BindValue::Decimal(n) => assert_eq!(n.to_string(), "12345678901234567890.1234"),
        other => panic!("expected Decimal, got {other:?}"),
    }
    let bad = FilterLiteral {
        kind: LiteralKind::Decimal,
        text: "abc".to_string(),
    };
    let err = BindValue::from_literal(&bad).unwrap_err();
    assert!(err.contains("not a valid decimal"), "got: {err}");
}

#[test]
fn bind_value_from_literal_bool_accepts_canonical_and_aliases() {
    for text in ["true", "TRUE", "True", "1", "yes", "YES"] {
        let lit = FilterLiteral {
            kind: LiteralKind::Bool,
            text: text.to_string(),
        };
        assert!(matches!(
            BindValue::from_literal(&lit).unwrap(),
            BindValue::Bool(true)
        ));
    }
    for text in ["false", "FALSE", "False", "0", "no", "NO", ""] {
        let lit = FilterLiteral {
            kind: LiteralKind::Bool,
            text: text.to_string(),
        };
        assert!(matches!(
            BindValue::from_literal(&lit).unwrap(),
            BindValue::Bool(false)
        ));
    }
    let bad = FilterLiteral {
        kind: LiteralKind::Bool,
        text: "maybe".to_string(),
    };
    let err = BindValue::from_literal(&bad).unwrap_err();
    assert!(err.contains("not a valid boolean"), "got: {err}");
}

#[test]
fn bind_value_from_literal_date_and_datetime_are_typed() {
    let d = FilterLiteral {
        kind: LiteralKind::Date,
        text: "2025-01-02".to_string(),
    };
    match BindValue::from_literal(&d).unwrap() {
        BindValue::Date(date) => assert_eq!(date.to_string(), "2025-01-02"),
        other => panic!("expected Date, got {other:?}"),
    }
    let dt = FilterLiteral {
        kind: LiteralKind::DateTime,
        text: "2025-01-02T03:04:05Z".to_string(),
    };
    match BindValue::from_literal(&dt).unwrap() {
        BindValue::DateTime(datetime) => assert_eq!(datetime.to_string(), "2025-01-02 03:04:05"),
        other => panic!("expected DateTime, got {other:?}"),
    }
}

#[test]
fn filter_group_default_is_empty_and_group_with_id() {
    let g = FilterGroup::default();
    assert!(!g.id.trim().is_empty());
    assert_eq!(g.connector, GroupConnector::And);
    assert!(g.children.is_empty());
}

#[test]
fn query_spec_round_trip_with_connector_overrides() {
    let spec = QuerySpec {
        tables: vec![TableRef {
            schema: "public".into(),
            name: "users".into(),
            alias: "t0".into(),
        }],
        columns: vec![ColumnSel {
            table_alias: "t0".into(),
            column: "id".into(),
        }],
        joins: vec![],
        filters: FilterGroup {
            id: "root".into(),
            connector: GroupConnector::And,
            children: vec![],
        },
        limit: 50,
        schema_version: CURRENT_SCHEMA_VERSION,
        connector_overrides: BTreeMap::from([("leaf-1".to_string(), GroupConnector::Or)]),
    };
    let json = serde_json::to_string(&spec).unwrap();
    let back: QuerySpec = serde_json::from_str(&json).unwrap();
    assert_eq!(back.limit, 50);
    assert_eq!(back.schema_version, CURRENT_SCHEMA_VERSION);
    assert_eq!(
        back.connector_overrides.get("leaf-1"),
        Some(&GroupConnector::Or)
    );
    assert_eq!(back.tables[0].alias, "t0");
}

#[test]
fn query_spec_deserializes_legacy_spec_without_connector_overrides_or_id() {
    let json = r#"{
        "tables": [],
        "columns": [],
        "joins": [],
        "filters": { "connector": "And", "children": [] },
        "limit": 100
    }"#;
    let spec: QuerySpec = serde_json::from_str(json).unwrap();
    assert_eq!(spec.schema_version, CURRENT_SCHEMA_VERSION);
    assert!(spec.connector_overrides.is_empty());
    assert_eq!(spec.filters.id, "");
}
