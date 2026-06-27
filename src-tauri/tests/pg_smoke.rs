use safe_db_lib::adapters::pg;
use safe_db_lib::query::compile::compile;
use safe_db_lib::query::ir::{CURRENT_SCHEMA_VERSION, ColumnSel, FilterGroup, QuerySpec, TableRef};
use safe_db_lib::query::validate::validate;
use safe_db_lib::types::Dialect;

struct PgConfig {
    host: String,
    port: u16,
    database: String,
    username: String,
    password: String,
}

fn pg_config_from_env() -> Option<PgConfig> {
    let host = std::env::var("SAFEDB_TEST_PG_HOST").ok()?;
    let port = std::env::var("SAFEDB_TEST_PG_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(5432);
    let database = std::env::var("SAFEDB_TEST_PG_DATABASE").ok()?;
    let username = std::env::var("SAFEDB_TEST_PG_USER").ok()?;
    let password = std::env::var("SAFEDB_TEST_PG_PASSWORD").ok()?;

    Some(PgConfig {
        host,
        port,
        database,
        username,
        password,
    })
}

#[tokio::test]
async fn pg_connect_and_test() {
    let Some(config) = pg_config_from_env() else {
        eprintln!(
            "skipping pg_connect_and_test: set SAFEDB_TEST_PG_HOST, \
             SAFEDB_TEST_PG_DATABASE, SAFEDB_TEST_PG_USER, and \
             SAFEDB_TEST_PG_PASSWORD to run this smoke test"
        );
        return;
    };

    let pool = pg::connect(
        &config.host,
        config.port,
        &config.database,
        &config.username,
        &config.password,
    )
    .await
    .expect("connect should succeed");

    let version = pg::test(&pool).await.expect("test query should succeed");
    assert!(
        version.to_ascii_lowercase().contains("postgresql"),
        "expected PostgreSQL version string, got: {version}"
    );
}

#[tokio::test]
async fn pg_introspect_validate_compile_execute() {
    let Some(config) = pg_config_from_env() else {
        eprintln!("skipping pg_introspect_validate_compile_execute: set SAFEDB_TEST_PG_* env vars");
        return;
    };

    let pool = pg::connect(
        &config.host,
        config.port,
        &config.database,
        &config.username,
        &config.password,
    )
    .await
    .expect("connect should succeed");

    let schema = pg::introspect(&pool)
        .await
        .expect("introspect should succeed");
    assert!(
        !schema.tables.is_empty(),
        "expected at least one user table"
    );

    let table = &schema.tables[0];
    let column = table
        .columns
        .first()
        .expect("first table should have columns");

    let mut spec = QuerySpec {
        tables: vec![TableRef {
            schema: table.schema.clone(),
            name: table.name.clone(),
            alias: "t0".into(),
        }],
        columns: vec![ColumnSel {
            table_alias: "t0".into(),
            column: column.name.clone(),
        }],
        joins: vec![],
        filters: FilterGroup::default(),
        limit: 5,
        connector_overrides: std::collections::BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
    };

    let _outcome = validate(&mut spec, &schema, &[]).expect("validate should succeed");
    let compiled = compile(&spec, Dialect::Postgres).expect("compile should succeed");
    let result = pg::execute_query(&pool, &compiled, 10_000)
        .await
        .expect("execute should succeed");
    assert!(result.row_count <= 5);
}
