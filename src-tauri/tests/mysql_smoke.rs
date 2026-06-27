use safe_db_lib::adapters::mysql;
use safe_db_lib::query::compile::compile;
use safe_db_lib::query::ir::{ColumnSel, FilterGroup, QuerySpec, TableRef, CURRENT_SCHEMA_VERSION};
use safe_db_lib::query::validate::validate;
use safe_db_lib::types::Dialect;

struct MySqlConfig {
    host: String,
    port: u16,
    database: String,
    username: String,
    password: String,
}

fn mysql_config_from_env() -> Option<MySqlConfig> {
    let host = std::env::var("SAFEDB_TEST_MYSQL_HOST").ok()?;
    let port = std::env::var("SAFEDB_TEST_MYSQL_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(3306);
    let database = std::env::var("SAFEDB_TEST_MYSQL_DATABASE").ok()?;
    let username = std::env::var("SAFEDB_TEST_MYSQL_USER").ok()?;
    let password = std::env::var("SAFEDB_TEST_MYSQL_PASSWORD").unwrap_or_default();

    Some(MySqlConfig {
        host,
        port,
        database,
        username,
        password,
    })
}

#[tokio::test]
async fn mysql_connect_introspect_validate_compile_execute() {
    let Some(config) = mysql_config_from_env() else {
        eprintln!(
            "skipping mysql_connect_introspect_validate_compile_execute: set \
             SAFEDB_TEST_MYSQL_HOST, SAFEDB_TEST_MYSQL_DATABASE, and \
             SAFEDB_TEST_MYSQL_USER to run this smoke test"
        );
        return;
    };

    let pool = mysql::connect(
        &config.host,
        config.port,
        &config.database,
        &config.username,
        &config.password,
    )
    .await
    .expect("connect should succeed");

    let version = mysql::test(&pool).await.expect("test query should succeed");
    assert!(
        version.to_ascii_lowercase().contains("mysql")
            || version.to_ascii_lowercase().contains("mariadb"),
        "expected MySQL/MariaDB version string, got: {version}"
    );

    let schema = mysql::introspect(&pool)
        .await
        .expect("introspect should succeed");
    assert!(
        schema.tables.iter().any(|t| t.name == "products"),
        "expected products table in introspected schema"
    );

    let products = schema
        .tables
        .iter()
        .find(|t| t.schema == config.database && t.name == "products")
        .expect("products table should exist in test database");

    let id_col = products
        .columns
        .iter()
        .find(|c| c.name == "id")
        .expect("products.id should exist");
    assert!(id_col.is_indexed, "products.id should be indexed");

    let mut spec = QuerySpec {
        tables: vec![TableRef {
            schema: products.schema.clone(),
            name: products.name.clone(),
            alias: "t0".into(),
        }],
        columns: vec![ColumnSel {
            table_alias: "t0".into(),
            column: "id".into(),
        }],
        joins: vec![],
        filters: FilterGroup::default(),
        limit: 5,
        schema_version: CURRENT_SCHEMA_VERSION,
    };

    let outcome = validate(&mut spec, &schema, &[]).expect("validate should succeed");
    assert!(outcome.limit <= 5);

    let compiled = compile(&spec, Dialect::MySql).expect("compile should succeed");
    let result = mysql::execute_query(&pool, &compiled, 10_000)
        .await
        .expect("execute should succeed");
    assert!(result.row_count <= 5);
    assert!(!result.columns.is_empty());
}
