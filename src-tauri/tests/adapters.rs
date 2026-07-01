use safe_db_lib::adapters::columns_from_compiled_sql;
use safe_db_lib::test_support::{
    classify_mysql_type, classify_pg_type, parse_mysql_explain_cost, parse_showplan_cost,
};
#[cfg(feature = "oracle")]
use safe_db_lib::test_support::{encode_oracle_connect_query_value, validate_oracle_connect_field};
use safe_db_lib::types::Dialect;

#[test]
fn classify_mysql_type_aliases() {
    assert_eq!(classify_mysql_type("TINYINT"), "SmallInt");
    assert_eq!(classify_mysql_type("SMALLINT"), "SmallInt");
    assert_eq!(classify_mysql_type("MEDIUMINT"), "SmallInt");
    assert_eq!(classify_mysql_type("INT"), "Int");
    assert_eq!(classify_mysql_type("INTEGER"), "Int");
    assert_eq!(classify_mysql_type("BIGINT"), "BigInt");
    assert_eq!(classify_mysql_type("FLOAT"), "Float");
    assert_eq!(classify_mysql_type("DOUBLE"), "Double");
    assert_eq!(classify_mysql_type("VARCHAR"), "Text");
    assert_eq!(classify_mysql_type("DATETIME"), "DateTime");
    assert_eq!(classify_mysql_type("JSON"), "Json");
}

#[test]
fn classify_pg_type_aliases() {
    assert_eq!(classify_pg_type("BOOL"), "Bool");
    assert_eq!(classify_pg_type("BOOLEAN"), "Bool");
    assert_eq!(classify_pg_type("INT2"), "SmallInt");
    assert_eq!(classify_pg_type("SMALLSERIAL"), "SmallInt");
    assert_eq!(classify_pg_type("SMALLINT"), "SmallInt");
    assert_eq!(classify_pg_type("INT4"), "Int");
    assert_eq!(classify_pg_type("SERIAL"), "Int");
    assert_eq!(classify_pg_type("INTEGER"), "Int");
    assert_eq!(classify_pg_type("INT"), "Int");
    assert_eq!(classify_pg_type("INT8"), "BigInt");
    assert_eq!(classify_pg_type("BIGSERIAL"), "BigInt");
    assert_eq!(classify_pg_type("BIGINT"), "BigInt");
    assert_eq!(classify_pg_type("FLOAT4"), "Float");
    assert_eq!(classify_pg_type("REAL"), "Float");
    assert_eq!(classify_pg_type("FLOAT8"), "Double");
    assert_eq!(classify_pg_type("DOUBLE PRECISION"), "Double");
    assert_eq!(classify_pg_type("UUID"), "Uuid");
    assert_eq!(classify_pg_type("JSONB"), "Json");
    assert_eq!(classify_pg_type("VARCHAR"), "Text");
}

#[test]
fn parse_showplan_cost_extracts_subtree_cost() {
    let xml = r#"<ShowPlanXML><BatchSequence><Batch><Statements><StmtSimple StatementSubTreeCost="12.5"/></Statements></Batch></BatchSequence></ShowPlanXML>"#;
    assert_eq!(parse_showplan_cost(xml), Some(12.5));
}

#[test]
fn parse_showplan_cost_returns_none_when_missing() {
    assert_eq!(parse_showplan_cost("<Plan />"), None);
}

#[test]
fn parse_mysql_explain_cost_handles_legacy_query_block_shape() {
    let plan = serde_json::json!({
        "query_block": {
            "cost_info": {
                "query_cost": "12.50"
            }
        }
    });

    assert_eq!(parse_mysql_explain_cost(&plan), Some(12.5));
}

#[test]
fn parse_mysql_explain_cost_handles_mysql_9_query_plan_shape() {
    let plan = serde_json::json!({
        "query_plan": {
            "estimated_rows": 15.0,
            "estimated_total_cost": 1.75
        },
        "json_schema_version": "2.0"
    });

    assert_eq!(parse_mysql_explain_cost(&plan), Some(1.75));
}

#[test]
fn parse_mysql_explain_cost_returns_none_when_missing() {
    let plan = serde_json::json!({ "query_plan": { "estimated_rows": 15.0 } });

    assert_eq!(parse_mysql_explain_cost(&plan), None);
}

#[test]
fn columns_from_compiled_sql_handles_dialect_quotes_and_mssql_top() {
    let pg = r#"SELECT "t0"."id" AS "t0__id", "t0"."name" AS "t0__name"
FROM "public"."users" AS "t0"
LIMIT 101"#;
    assert_eq!(
        columns_from_compiled_sql(pg, Dialect::Postgres),
        vec!["t0__id".to_string(), "t0__name".to_string()]
    );

    let mysql = "SELECT `t0`.`id` AS `t0__id`\nFROM `app`.`users` AS `t0`\nLIMIT 101";
    assert_eq!(
        columns_from_compiled_sql(mysql, Dialect::MySql),
        vec!["t0__id".to_string()]
    );

    let mssql = "SELECT TOP 101 [t0].[id] AS [t0__id]\nFROM [dbo].[users] AS [t0]";
    assert_eq!(
        columns_from_compiled_sql(mssql, Dialect::Mssql),
        vec!["t0__id".to_string()]
    );
}

#[cfg(feature = "oracle")]
#[test]
fn oracle_connect_helpers_encode_and_validate() {
    assert!(validate_oracle_connect_field("db.example.com", "Host").is_ok());
    assert!(validate_oracle_connect_field("", "Host").is_err());
    assert!(validate_oracle_connect_field("host;drop", "Host").is_err());

    let encoded = encode_oracle_connect_query_value(r"C:\Users\me\my wallet");
    assert!(encoded.contains("%20"));
    assert!(encoded.contains("%5C"));
    assert!(!encoded.contains(' '));
    assert_eq!(
        encode_oracle_connect_query_value("/opt/oracle/wallet"),
        "/opt/oracle/wallet"
    );
}
