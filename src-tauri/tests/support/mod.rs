#![allow(dead_code)]

use std::collections::BTreeMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};

use safe_db_lib::adapters::ExplainResult;
use safe_db_lib::introspect::{ColumnCategory, ColumnInfo, IndexInfo, Schema, TableInfo};
use safe_db_lib::query::QueryResult;
use safe_db_lib::query::ir::{
    CURRENT_SCHEMA_VERSION, ColumnSel, FilterGroup, FilterNode, FilterOp, FilterSpec, FilterValue,
    GroupConnector, LiteralKind, QuerySpec, TableRef,
};
use safe_db_lib::settings::Settings;
use safe_db_lib::types::{CURRENT_CONNECTION_VERSION, ConnectionDef, Dialect, TransportSecurity};

pub fn sample_connection(id: &str) -> ConnectionDef {
    ConnectionDef {
        version: CURRENT_CONNECTION_VERSION,
        id: id.to_string(),
        name: format!("Conn {id}"),
        dialect: Dialect::Postgres,
        host: "localhost".to_string(),
        port: 5432,
        database: "demo".to_string(),
        username: "user".to_string(),
        transport_security: TransportSecurity::default(),
    }
}

pub fn sample_settings() -> Settings {
    Settings {
        blocked_schemas: vec![],
        explain_cost_threshold: 100_000.0,
        explain_cost_thresholds: Default::default(),
        theme: "light".to_string(),
    }
}

pub fn sample_schema() -> Schema {
    Schema {
        tables: vec![
            TableInfo {
                schema: "public".to_string(),
                name: "users".to_string(),
                columns: vec![
                    ColumnInfo {
                        name: "id".to_string(),
                        data_type: "int".to_string(),
                        nullable: false,
                        is_indexed: true,
                        join_eligible: true,
                        category: ColumnCategory::Integer,
                    },
                    ColumnInfo {
                        name: "name".to_string(),
                        data_type: "text".to_string(),
                        nullable: true,
                        is_indexed: false,
                        join_eligible: false,
                        category: ColumnCategory::Text,
                    },
                    ColumnInfo {
                        name: "category_id".to_string(),
                        data_type: "int".to_string(),
                        nullable: true,
                        is_indexed: true,
                        join_eligible: true,
                        category: ColumnCategory::Integer,
                    },
                ],
                indexes: vec![
                    IndexInfo {
                        name: "users_pkey".to_string(),
                        columns: vec!["id".to_string()],
                        included_columns: vec![],
                        kind: "btree".to_string(),
                        supports_equality: true,
                        is_unique: true,
                        is_primary: true,
                    },
                    IndexInfo {
                        name: "users_category_id_idx".to_string(),
                        columns: vec!["category_id".to_string()],
                        included_columns: vec![],
                        kind: "btree".to_string(),
                        supports_equality: true,
                        is_unique: false,
                        is_primary: false,
                    },
                ],
            },
            TableInfo {
                schema: "public".to_string(),
                name: "categories".to_string(),
                columns: vec![
                    ColumnInfo {
                        name: "id".to_string(),
                        data_type: "int".to_string(),
                        nullable: false,
                        is_indexed: true,
                        join_eligible: true,
                        category: ColumnCategory::Integer,
                    },
                    ColumnInfo {
                        name: "name".to_string(),
                        data_type: "text".to_string(),
                        nullable: true,
                        is_indexed: false,
                        join_eligible: false,
                        category: ColumnCategory::Text,
                    },
                ],
                indexes: vec![IndexInfo {
                    name: "categories_pkey".to_string(),
                    columns: vec!["id".to_string()],
                    included_columns: vec![],
                    kind: "btree".to_string(),
                    supports_equality: true,
                    is_unique: true,
                    is_primary: true,
                }],
            },
        ],
    }
}

pub fn sample_spec() -> QuerySpec {
    QuerySpec {
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
        filters: FilterGroup::default(),
        limit: 100,
        connector_overrides: BTreeMap::new(),
        schema_version: CURRENT_SCHEMA_VERSION,
    }
}

pub fn lit(kind: LiteralKind, text: &str) -> safe_db_lib::query::ir::FilterLiteral {
    safe_db_lib::query::ir::FilterLiteral {
        kind,
        text: text.into(),
    }
}

pub fn leaf(op: FilterOp, value: Option<FilterValue>) -> FilterSpec {
    FilterSpec {
        id: uuid::Uuid::new_v4().to_string(),
        table_alias: "t0".into(),
        column: "id".into(),
        op,
        value,
    }
}

pub fn leaf_on(col: &str, op: FilterOp, value: Option<FilterValue>) -> FilterSpec {
    FilterSpec {
        id: uuid::Uuid::new_v4().to_string(),
        table_alias: "t0".into(),
        column: col.into(),
        op,
        value,
    }
}

pub fn group(connector: GroupConnector, children: Vec<FilterNode>) -> FilterGroup {
    FilterGroup {
        id: uuid::Uuid::new_v4().to_string(),
        connector,
        children,
    }
}

pub struct MockRunner {
    explain: Mutex<Option<ExplainResult>>,
    execute: Mutex<Option<anyhow::Result<QueryResult>>>,
    pub explain_calls: AtomicUsize,
    pub execute_calls: AtomicUsize,
    pub last_explain_sql: Mutex<Option<String>>,
}

impl MockRunner {
    pub fn new() -> Self {
        Self {
            explain: Mutex::new(Some(ExplainResult::Estimated(0.0))),
            execute: Mutex::new(Some(Ok(QueryResult {
                columns: vec![],
                rows: vec![],
                row_count: 0,
                truncated: false,
                warnings: vec![],
            }))),
            explain_calls: AtomicUsize::new(0),
            execute_calls: AtomicUsize::new(0),
            last_explain_sql: Mutex::new(None),
        }
    }

    pub fn set_explain(&self, result: ExplainResult) {
        *self.explain.lock().unwrap() = Some(result);
    }

    pub fn set_execute(&self, result: anyhow::Result<QueryResult>) {
        *self.execute.lock().unwrap() = Some(result);
    }
}

impl safe_db_lib::test_support::QueryRunner for MockRunner {
    async fn explain(
        &self,
        compiled: &safe_db_lib::query::ir::CompiledQuery,
    ) -> anyhow::Result<ExplainResult> {
        self.explain_calls.fetch_add(1, Ordering::SeqCst);
        *self.last_explain_sql.lock().unwrap() = Some(compiled.sql.clone());
        Ok(self
            .explain
            .lock()
            .unwrap()
            .take()
            .unwrap_or(ExplainResult::Estimated(0.0)))
    }

    async fn execute_query(
        &self,
        _compiled: &safe_db_lib::query::ir::CompiledQuery,
        _timeout_ms: u32,
    ) -> anyhow::Result<QueryResult> {
        self.execute_calls.fetch_add(1, Ordering::SeqCst);
        self.execute
            .lock()
            .unwrap()
            .take()
            .unwrap_or(Ok(QueryResult {
                columns: vec![],
                rows: vec![],
                row_count: 0,
                truncated: false,
                warnings: vec![],
            }))
    }
}
