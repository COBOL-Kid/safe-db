use safe_db_lib::adapters::mysql;
use safe_db_lib::query::compile::compile;
use safe_db_lib::query::ir::{CURRENT_SCHEMA_VERSION, ColumnSel, FilterGroup, QuerySpec, TableRef};
use safe_db_lib::query::validate::validate;
use safe_db_lib::types::{
    CURRENT_CONNECTION_VERSION, ConnectionDef, Dialect, TransportSecurity, TransportSecurityMode,
};
use std::process::Command;

struct MySqlConfig {
    host: String,
    port: u16,
    database: String,
    username: String,
    password: String,
    password_env_unset: bool,
}

fn connection_def(config: &MySqlConfig) -> ConnectionDef {
    ConnectionDef {
        version: CURRENT_CONNECTION_VERSION,
        id: "mysql-smoke".into(),
        name: "MySQL smoke".into(),
        dialect: Dialect::MySql,
        host: config.host.clone(),
        port: config.port,
        database: config.database.clone(),
        username: config.username.clone(),
        transport_security: TransportSecurity {
            mode: TransportSecurityMode::Disabled,
            ..TransportSecurity::default()
        },
    }
}

fn mysql_config_from_env() -> Option<MySqlConfig> {
    let host = std::env::var("SAFEDB_TEST_MYSQL_HOST").ok()?;
    let port = std::env::var("SAFEDB_TEST_MYSQL_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(3306);
    let database = std::env::var("SAFEDB_TEST_MYSQL_DATABASE").ok()?;
    let username = std::env::var("SAFEDB_TEST_MYSQL_USER").ok()?;
    let password_from_env = std::env::var("SAFEDB_TEST_MYSQL_PASSWORD").ok();
    let password_env_unset = password_from_env.is_none();
    let docker_password = password_from_env
        .is_none()
        .then(|| mysql_root_password_from_docker(&host, &username))
        .flatten();
    let password = password_from_env.or(docker_password).unwrap_or_default();

    Some(MySqlConfig {
        host,
        port,
        database,
        username,
        password,
        password_env_unset,
    })
}

fn mysql_root_password_from_docker(host: &str, username: &str) -> Option<String> {
    if username != "root" || !matches!(host, "localhost" | "127.0.0.1") {
        return None;
    }

    let container = std::env::var("SAFEDB_TEST_MYSQL_DOCKER")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "safedb-mysql".to_string());
    let output = Command::new("docker")
        .args([
            "inspect",
            &container,
            "--format",
            "{{range .Config.Env}}{{println .}}{{end}}",
        ])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    String::from_utf8_lossy(&output.stdout)
        .lines()
        .find_map(|line| line.strip_prefix("MYSQL_ROOT_PASSWORD="))
        .map(str::to_string)
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

    let pool = match mysql::connect(&connection_def(&config), &config.password).await {
        Ok(pool) => pool,
        Err(error) if config.password_env_unset && error.to_string().contains("Access denied") => {
            eprintln!(
                "skipping mysql_connect_introspect_validate_compile_execute: local Docker \
                 password was unset and MySQL rejected host access for {}@{}",
                config.username, config.host
            );
            return;
        }
        Err(error) => panic!("connect should succeed: {error:?}"),
    };

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
        connector_overrides: std::collections::BTreeMap::new(),
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
