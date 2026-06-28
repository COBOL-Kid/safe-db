use safe_db_lib::introspect::{ColumnCategory, ColumnInfo, IndexInfo, mark_indexed_columns};
use safe_db_lib::test_support::atomic_write;
use safe_db_lib::types::{
    CURRENT_CONNECTION_VERSION, ConnectionDef, Dialect, TransportSecurity, TransportSecurityMode,
};
use tempfile::TempDir;

#[test]
fn validate_allows_disabled_when_legacy_implicit() {
    let def = sample_connection(TransportSecurityMode::Disabled, true);
    assert!(def.validate().is_ok());
}

#[test]
fn validate_allows_disabled_without_acknowledgement() {
    let def = sample_connection(TransportSecurityMode::Disabled, false);
    assert!(def.validate().is_ok());
}

#[test]
fn validate_allows_encrypt_only_without_acknowledgement() {
    let def = sample_connection(TransportSecurityMode::EncryptOnly, false);
    assert!(def.validate().is_ok());
}

#[test]
fn marks_primary_composite_and_non_indexed_columns() {
    let mut columns = vec![
        ColumnInfo {
            name: "id".into(),
            data_type: "int".into(),
            nullable: false,
            is_indexed: false,
            ..ColumnInfo::default()
        },
        ColumnInfo {
            name: "category_id".into(),
            data_type: "int".into(),
            nullable: false,
            is_indexed: false,
            ..ColumnInfo::default()
        },
        ColumnInfo {
            name: "name".into(),
            data_type: "text".into(),
            nullable: true,
            is_indexed: false,
            ..ColumnInfo::default()
        },
    ];
    let indexes = vec![
        IndexInfo {
            name: "pk".into(),
            columns: vec!["id".into()],
            is_unique: true,
            is_primary: true,
            ..IndexInfo::default()
        },
        IndexInfo {
            name: "idx_cat".into(),
            columns: vec!["category_id".into(), "name".into()],
            is_unique: false,
            is_primary: false,
            ..IndexInfo::default()
        },
    ];

    mark_indexed_columns(&mut columns, &indexes);

    assert!(columns[0].is_indexed);
    assert!(columns[1].is_indexed);
    assert!(columns[2].is_indexed);
    assert_eq!(columns[0].category, ColumnCategory::Integer);
}

#[test]
fn leaves_non_indexed_columns_unmarked() {
    let mut columns = vec![ColumnInfo {
        name: "note".into(),
        data_type: "text".into(),
        nullable: true,
        is_indexed: false,
        ..ColumnInfo::default()
    }];
    mark_indexed_columns(&mut columns, &[]);
    assert!(!columns[0].is_indexed);
}

#[test]
fn atomic_write_creates_file_with_content() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("data.json");
    atomic_write(&path, r#"{"ok":true}"#).unwrap();
    let content = std::fs::read_to_string(&path).unwrap();
    assert_eq!(content, r#"{"ok":true}"#);
}

fn sample_connection(mode: TransportSecurityMode, legacy_implicit: bool) -> ConnectionDef {
    ConnectionDef {
        version: CURRENT_CONNECTION_VERSION,
        id: "c1".to_string(),
        name: "Test".to_string(),
        dialect: Dialect::Postgres,
        host: "localhost".to_string(),
        port: 5432,
        database: "demo".to_string(),
        username: "user".to_string(),
        transport_security: TransportSecurity {
            mode,
            ca_pem: None,
            oracle_wallet_location: None,
            legacy_implicit,
        },
    }
}
