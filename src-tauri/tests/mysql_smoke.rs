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
    password_source: PasswordSource,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum PasswordSource {
    Env,
    Docker(String),
    DefaultEmpty,
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

fn mysql_config_from_env() -> Result<Option<MySqlConfig>, String> {
    let Some(host) = std::env::var("SAFEDB_TEST_MYSQL_HOST").ok() else {
        return Ok(None);
    };
    let port = std::env::var("SAFEDB_TEST_MYSQL_PORT")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(3306);
    let Some(database) = std::env::var("SAFEDB_TEST_MYSQL_DATABASE").ok() else {
        return Ok(None);
    };
    let Some(username) = std::env::var("SAFEDB_TEST_MYSQL_USER").ok() else {
        return Ok(None);
    };
    let (password, password_source) = match std::env::var("SAFEDB_TEST_MYSQL_PASSWORD").ok() {
        Some(password) => (password, PasswordSource::Env),
        None => match mysql_root_password_from_docker(&host, port, &username)? {
            Some((container, password)) => (password, PasswordSource::Docker(container)),
            None => (String::new(), PasswordSource::DefaultEmpty),
        },
    };

    Ok(Some(MySqlConfig {
        host,
        port,
        database,
        username,
        password,
        password_source,
    }))
}

fn mysql_root_password_from_docker(
    host: &str,
    port: u16,
    username: &str,
) -> Result<Option<(String, String)>, String> {
    if username != "root" || !matches!(host, "localhost" | "127.0.0.1") {
        return Ok(None);
    }

    if let Some(container) = std::env::var("SAFEDB_TEST_MYSQL_DOCKER")
        .ok()
        .filter(|value| !value.trim().is_empty())
    {
        let password = docker_env_var(&container, "MYSQL_ROOT_PASSWORD").map_err(|error| {
            format!("failed to inspect pinned Docker container '{container}': {error}")
        })?;
        return Ok(password.map(|password| (container, password)));
    }

    let Some(container) = mysql_docker_container_publishing_port(port)? else {
        return Ok(None);
    };
    Ok(docker_env_var(&container, "MYSQL_ROOT_PASSWORD")?.map(|password| (container, password)))
}

fn docker_env_var(container: &str, key: &str) -> Result<Option<String>, String> {
    let output = Command::new("docker")
        .args([
            "inspect",
            container,
            "--format",
            "{{range .Config.Env}}{{println .}}{{end}}",
        ])
        .output()
        .map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }

    Ok(String::from_utf8_lossy(&output.stdout)
        .lines()
        .filter_map(|line| line.split_once('='))
        .find_map(|(name, value)| (name == key).then(|| value.to_string())))
}

fn mysql_docker_container_publishing_port(port: u16) -> Result<Option<String>, String> {
    let publish_filter = format!("publish={port}");
    let output = match Command::new("docker")
        .args([
            "ps",
            "--filter",
            "status=running",
            "--filter",
            &publish_filter,
            "--format",
            "{{.Names}}\t{{.Image}}",
        ])
        .output()
    {
        Ok(output) => output,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.to_string()),
    };

    if !output.status.success() {
        return Ok(None);
    }

    Ok(mysql_container_from_docker_ps(&String::from_utf8_lossy(
        &output.stdout,
    )))
}

fn mysql_container_from_docker_ps(output: &str) -> Option<String> {
    output.lines().find_map(|line| {
        let (name, image) = line.split_once('\t')?;
        let image = image.to_ascii_lowercase();
        (image.contains("mysql") || image.contains("mariadb")).then(|| name.to_string())
    })
}

#[tokio::test]
async fn mysql_connect_introspect_validate_compile_execute() {
    let config = match mysql_config_from_env() {
        Ok(Some(config)) => config,
        Ok(None) => {
            eprintln!(
                "skipping mysql_connect_introspect_validate_compile_execute: set \
             SAFEDB_TEST_MYSQL_HOST, SAFEDB_TEST_MYSQL_DATABASE, and \
             SAFEDB_TEST_MYSQL_USER to run this smoke test"
            );
            return;
        }
        Err(error) => panic!("mysql smoke test configuration failed: {error}"),
    };

    let pool = match mysql::connect(&connection_def(&config), &config.password).await {
        Ok(pool) => pool,
        Err(error) => panic!(
            "connect should succeed for {}@{}:{} using {:?} password: {error:?}",
            config.username, config.host, config.port, config.password_source
        ),
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

#[test]
fn parses_first_mysql_or_mariadb_container_from_docker_ps() {
    let output = "cache\tredis:8\ncustom-db\tghcr.io/example/mariadb:11\nother\tpostgres:18\n";

    assert_eq!(
        mysql_container_from_docker_ps(output),
        Some("custom-db".to_string())
    );
}

#[test]
fn ignores_non_mysql_containers_from_docker_ps() {
    let output = "cache\tredis:8\npg\tpostgres:18\n";

    assert_eq!(mysql_container_from_docker_ps(output), None);
}
