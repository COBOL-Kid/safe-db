use safe_db_lib::adapters::pg;

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
