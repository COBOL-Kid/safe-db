use tauri::{AppHandle, Manager};

use crate::adapters::{Adapter, DEFAULT_TIMEOUT_MS, ExplainResult};
use crate::config::ConfigStore;
use crate::introspect::Schema;
use crate::queries::{HistoryEntry, QueryStore, SavedQuery};
use crate::query::{
    QueryResult, QuerySpec,
    ir::CompiledQuery,
    {compile, validate},
};
use crate::secrets;
use crate::settings::{Settings, SettingsStore};
use crate::types::ConnectionDef;

fn now_iso() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    format!("{}", now)
}

#[tauri::command]
pub async fn test_connection(def: ConnectionDef, password: String) -> Result<String, String> {
    let adapter = Adapter::connect(&def, &password)
        .await
        .map_err(|e| e.to_string())?;
    let version = adapter.test().await.map_err(|e| e.to_string())?;
    Ok(version)
}

#[tauri::command]
pub async fn save_connection(
    app: AppHandle,
    def: ConnectionDef,
    password: Option<String>,
) -> Result<(), String> {
    let config_store = app.state::<ConfigStore>();

    if let Some(pw) = password {
        secrets::save_password(&def.id, &pw).map_err(|e| e.to_string())?;
    }

    config_store.save(def.clone()).map_err(|e| e.to_string())?;

    Ok(())
}

#[tauri::command]
pub async fn list_connections(app: AppHandle) -> Result<Vec<ConnectionDef>, String> {
    let config_store = app.state::<ConfigStore>();
    config_store.list().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_connection(app: AppHandle, id: String) -> Result<(), String> {
    let config_store = app.state::<ConfigStore>();
    config_store.delete(&id).map_err(|e| e.to_string())?;
    secrets::delete_password(&id).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn get_schema(app: AppHandle, connection_id: String) -> Result<Schema, String> {
    let config_store = app.state::<ConfigStore>();
    let def = config_store
        .get(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Connection not found".to_string())?;

    let password = secrets::password_for_connection(&connection_id)?;

    let adapter = Adapter::connect(&def, &password)
        .await
        .map_err(|e| e.to_string())?;
    let schema = adapter.introspect().await.map_err(|e| e.to_string())?;

    Ok(schema)
}

/// Abstraction over the subset of `Adapter` that `run_query` needs. The trait
/// is intentionally minimal so tests can implement it with a mock.
pub trait QueryRunner: Send + Sync {
    async fn explain(&self, compiled: &CompiledQuery) -> anyhow::Result<ExplainResult>;
    async fn execute_query(
        &self,
        compiled: &CompiledQuery,
        timeout_ms: u32,
    ) -> anyhow::Result<QueryResult>;
}

impl QueryRunner for Adapter {
    async fn explain(&self, compiled: &CompiledQuery) -> anyhow::Result<ExplainResult> {
        Adapter::explain(self, compiled).await
    }

    async fn execute_query(
        &self,
        compiled: &CompiledQuery,
        timeout_ms: u32,
    ) -> anyhow::Result<QueryResult> {
        Adapter::execute_query(self, compiled, timeout_ms).await
    }
}

/// Error returned by `run_query_core` when validation, compile, or execute
/// fails. Carries the warnings accumulated up to the failure point so the
/// caller can still surface them on the failed `HistoryEntry` (cost-threshold
/// warnings in particular are often the *reason* a user wants to know what
/// happened on a failed run).
#[derive(Debug, Clone, PartialEq)]
pub struct QueryCoreError {
    pub message: String,
    pub warnings: Vec<String>,
}

/// Prefix for errors returned when the cost guard blocks execution.
/// The frontend recognizes this prefix to offer a confirmation retry.
pub const COST_GUARD_PREFIX: &str = "COST_GUARD_BLOCKED:";

/// Core `run_query` logic, extracted for unit testing. Validates + compiles +
/// EXPLAINs + executes via the supplied `runner`; the caller is responsible
/// for wiring the returned `(result, warnings)` into a `HistoryEntry` and
/// surfacing the error to the frontend.
///
/// Ordering matters for safety: `validate` runs first (it clamps `spec.limit`
/// and rejects blocked schemas / unknown columns), then `compile` materializes
/// the validated spec into SQL exactly once, and only *then* does EXPLAIN run
/// against that post-validate compiled query. This keeps the cost estimate and
/// any cost-threshold warning aligned with the SQL that actually executes —
/// e.g. an over-limit request is clamped before EXPLAIN, so the warning reflects
/// the capped query rather than the raw user input — and avoids a wasted EXPLAIN
/// round trip on specs that `validate` will reject anyway.
pub async fn run_query_core<R: QueryRunner>(
    runner: &R,
    def: &ConnectionDef,
    spec: &mut QuerySpec,
    schema: &Schema,
    settings: &Settings,
    force: bool,
) -> Result<QueryResult, QueryCoreError> {
    let outcome =
        validate(spec, schema, &settings.blocked_schemas).map_err(|e| QueryCoreError {
            message: e.to_string(),
            warnings: vec![],
        })?;

    let compiled = compile(spec, def.dialect).map_err(|e| QueryCoreError {
        message: e.to_string(),
        warnings: outcome.warnings.clone(),
    })?;

    let mut warnings = outcome.warnings;

    let explain_result = match runner.explain(&compiled).await {
        Ok(r) => r,
        Err(_) => ExplainResult {
            cost: None,
            warning: Some("EXPLAIN failed".to_string()),
        },
    };

    let explain_failed = explain_result.warning.is_some();
    let over_cost = explain_result
        .cost
        .is_some_and(|cost| cost > settings.explain_cost_threshold);

    if let Some(cost) = explain_result.cost
        && cost > settings.explain_cost_threshold
    {
        warnings.push(format!(
            "Estimated query cost ({cost:.0}) exceeds threshold ({:.0}) — this may be slow",
            settings.explain_cost_threshold
        ));
    }
    if let Some(w) = explain_result.warning {
        warnings.push(w);
    }

    if (explain_failed || over_cost) && !force {
        let reason = if explain_failed && over_cost {
            "EXPLAIN failed and estimated cost exceeds threshold"
        } else if explain_failed {
            "EXPLAIN failed"
        } else {
            "Estimated query cost exceeds threshold"
        };
        return Err(QueryCoreError {
            message: format!(
                "{COST_GUARD_PREFIX}{reason}. Confirm to run this query anyway."
            ),
            warnings,
        });
    }

    let result = runner
        .execute_query(&compiled, DEFAULT_TIMEOUT_MS)
        .await
        .map_err(|e| QueryCoreError {
            message: e.to_string(),
            warnings: warnings.clone(),
        })?;
    let mut result = result;
    result.truncated = result.row_count >= outcome.limit as usize;
    result.warnings = warnings;
    Ok(result)
}

#[tauri::command]
pub async fn run_query(
    app: AppHandle,
    connection_id: String,
    mut spec: QuerySpec,
    force: Option<bool>,
) -> Result<QueryResult, String> {
    let config_store = app.state::<ConfigStore>();
    let settings_store = app.state::<SettingsStore>();
    let query_store = app.state::<QueryStore>();

    let def = config_store
        .get(&connection_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Connection not found".to_string())?;

    let password = secrets::password_for_connection(&connection_id)?;

    let settings = settings_store.load().map_err(|e| e.to_string())?;

    let adapter = Adapter::connect(&def, &password)
        .await
        .map_err(|e| e.to_string())?;

    let schema = adapter.introspect().await.map_err(|e| e.to_string())?;

    let force = force.unwrap_or(false);

    let result = match run_query_core(
        &adapter,
        &def,
        &mut spec,
        &schema,
        &settings,
        force,
    )
    .await
    {
        Ok(r) => r,
        Err(core_err) => {
            let entry = HistoryEntry {
                id: uuid::Uuid::new_v4().to_string(),
                connection_id: connection_id.clone(),
                connection_name: def.name.clone(),
                spec: spec.clone(),
                row_count: 0,
                warnings: core_err.warnings,
                error: Some(core_err.message.clone()),
                timestamp: now_iso(),
            };
            if let Err(e) = query_store.add_history(entry) {
                log::warn!("failed to persist query history: {e}");
            }
            return Err(core_err.message);
        }
    };

    let entry = HistoryEntry {
        id: uuid::Uuid::new_v4().to_string(),
        connection_id: connection_id.clone(),
        connection_name: def.name.clone(),
        spec: spec.clone(),
        row_count: result.row_count,
        warnings: result.warnings.clone(),
        error: None,
        timestamp: now_iso(),
    };
    if let Err(e) = query_store.add_history(entry) {
        log::warn!("failed to persist query history: {e}");
    }

    Ok(result)
}

#[tauri::command]
pub async fn list_saved_queries(app: AppHandle) -> Result<Vec<SavedQuery>, String> {
    let store = app.state::<QueryStore>();
    store.list_saved().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn save_saved_query(app: AppHandle, query: SavedQuery) -> Result<(), String> {
    let store = app.state::<QueryStore>();
    store.save_query(query).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_saved_query(app: AppHandle, id: String) -> Result<(), String> {
    let store = app.state::<QueryStore>();
    store.delete_saved(&id).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn list_history(app: AppHandle) -> Result<Vec<HistoryEntry>, String> {
    let store = app.state::<QueryStore>();
    store.list_history().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn clear_history(app: AppHandle) -> Result<(), String> {
    let store = app.state::<QueryStore>();
    store.clear_history().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_settings(app: AppHandle) -> Result<Settings, String> {
    let store = app.state::<SettingsStore>();
    store.load().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn save_settings(app: AppHandle, settings: Settings) -> Result<(), String> {
    let store = app.state::<SettingsStore>();
    store.save(&settings).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::ir::{FilterOp, GroupConnector};
    use crate::types::Dialect;
    use std::collections::BTreeMap;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn sample_def() -> ConnectionDef {
        ConnectionDef {
            id: "c1".to_string(),
            name: "Test DB".to_string(),
            dialect: Dialect::Postgres,
            host: "localhost".to_string(),
            port: 5432,
            database: "demo".to_string(),
            username: "user".to_string(),
        }
    }

    fn sample_settings() -> Settings {
        Settings {
            blocked_schemas: vec![],
            explain_cost_threshold: 100_000.0,
            theme: "light".to_string(),
        }
    }

    fn sample_schema() -> Schema {
        Schema {
            tables: vec![crate::introspect::TableInfo {
                schema: "public".to_string(),
                name: "users".to_string(),
                columns: vec![crate::introspect::ColumnInfo {
                    name: "id".to_string(),
                    data_type: "int".to_string(),
                    nullable: false,
                    is_indexed: true,
                }],
                indexes: vec![],
            }],
        }
    }

    fn sample_spec() -> QuerySpec {
        QuerySpec {
            tables: vec![crate::query::ir::TableRef {
                schema: "public".to_string(),
                name: "users".to_string(),
                alias: "t0".to_string(),
            }],
            columns: vec![crate::query::ir::ColumnSel {
                table_alias: "t0".to_string(),
                column: "id".to_string(),
            }],
            joins: vec![],
            filters: crate::query::ir::FilterGroup {
                id: "g".to_string(),
                connector: GroupConnector::And,
                children: vec![],
            },
            limit: 50,
            schema_version: 2,
            connector_overrides: BTreeMap::new(),
        }
    }

    struct MockRunner {
        explain: Mutex<Option<ExplainResult>>,
        execute: Mutex<Option<anyhow::Result<QueryResult>>>,
        explain_calls: AtomicUsize,
        execute_calls: AtomicUsize,
        /// Captures the compiled SQL passed to the most recent `explain` call,
        /// so tests can assert that EXPLAIN ran against the post-validate query.
        last_explain_sql: Mutex<Option<String>>,
    }

    impl MockRunner {
        fn new() -> Self {
            Self {
                explain: Mutex::new(Some(ExplainResult {
                    cost: None,
                    warning: None,
                })),
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

        fn set_explain(&self, result: ExplainResult) {
            *self.explain.lock().unwrap() = Some(result);
        }
    }

    impl QueryRunner for MockRunner {
        async fn explain(&self, compiled: &CompiledQuery) -> anyhow::Result<ExplainResult> {
            self.explain_calls.fetch_add(1, Ordering::SeqCst);
            *self.last_explain_sql.lock().unwrap() = Some(compiled.sql.clone());
            Ok(self
                .explain
                .lock()
                .unwrap()
                .take()
                .unwrap_or(ExplainResult {
                    cost: None,
                    warning: None,
                }))
        }

        async fn execute_query(
            &self,
            _compiled: &CompiledQuery,
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

    #[tokio::test]
    async fn successful_run_returns_result_with_row_count() {
        let runner = MockRunner::new();
        *runner.execute.lock().unwrap() = Some(Ok(QueryResult {
            columns: vec!["id".to_string()],
            rows: vec![vec![serde_json::json!(1)]],
            row_count: 1,
            truncated: false,
            warnings: vec![],
        }));

        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let result = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap();

        assert_eq!(result.row_count, 1);
        assert!(!result.truncated);
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 1);
        // EXPLAIN runs once on the success path, against the post-validate query.
        assert_eq!(runner.explain_calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn truncated_is_set_when_row_count_meets_limit() {
        let runner = MockRunner::new();
        *runner.execute.lock().unwrap() = Some(Ok(QueryResult {
            columns: vec!["id".to_string()],
            rows: vec![],
            row_count: 50,
            truncated: false,
            warnings: vec![],
        }));

        let mut spec = sample_spec(); // limit 50
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let result = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap();

        assert!(result.truncated, "row_count >= limit should set truncated");
    }

    #[tokio::test]
    async fn cost_above_threshold_blocks_without_force() {
        let runner = MockRunner::new();
        runner.set_explain(ExplainResult {
            cost: Some(250_000.0),
            warning: None,
        });
        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap_err();

        assert!(err.message.starts_with(COST_GUARD_PREFIX));
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn cost_above_threshold_runs_with_force() {
        let runner = MockRunner::new();
        runner.set_explain(ExplainResult {
            cost: Some(250_000.0),
            warning: None,
        });
        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let result = run_query_core(&runner, &def, &mut spec, &schema, &settings, true)
            .await
            .unwrap();

        assert!(
            result
                .warnings
                .iter()
                .any(|w| w.contains("Estimated query cost"))
        );
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn cost_below_threshold_does_not_warn() {
        let runner = MockRunner::new();
        runner.set_explain(ExplainResult {
            cost: Some(50_000.0),
            warning: None,
        });
        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let result = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap();

        assert!(
            !result
                .warnings
                .iter()
                .any(|w| w.contains("Estimated query cost"))
        );
    }

    #[tokio::test]
    async fn explain_warning_blocks_without_force() {
        let runner = MockRunner::new();
        runner.set_explain(ExplainResult {
            cost: None,
            warning: Some("EXPLAIN failed".to_string()),
        });
        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap_err();

        assert!(err.message.starts_with(COST_GUARD_PREFIX));
        assert!(err.warnings.contains(&"EXPLAIN failed".to_string()));
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn explain_warning_runs_with_force() {
        let runner = MockRunner::new();
        runner.set_explain(ExplainResult {
            cost: None,
            warning: Some("EXPLAIN failed".to_string()),
        });
        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let result = run_query_core(&runner, &def, &mut spec, &schema, &settings, true)
            .await
            .unwrap();

        assert!(result.warnings.contains(&"EXPLAIN failed".to_string()));
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn execute_query_error_propagates_as_err() {
        let runner = MockRunner::new();
        *runner.execute.lock().unwrap() = Some(Err(anyhow::anyhow!("connection lost")));

        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, true)
            .await
            .unwrap_err();

        assert_eq!(err.message, "connection lost");
    }

    #[tokio::test]
    async fn execute_query_error_preserves_accumulated_warnings() {
        // Regression: cost-threshold and EXPLAIN warnings must survive an
        // execute-time failure so the History view can still show *why* the
        // query was risky when it failed.
        let runner = MockRunner::new();
        *runner.execute.lock().unwrap() = Some(Err(anyhow::anyhow!("connection lost")));
        runner.set_explain(ExplainResult {
            cost: Some(250_000.0),
            warning: Some("EXPLAIN failed".to_string()),
        });

        let mut spec = sample_spec();
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, true)
            .await
            .unwrap_err();

        assert_eq!(err.message, "connection lost");
        assert!(
            err.warnings
                .iter()
                .any(|w| w.contains("Estimated query cost")),
            "cost-threshold warning should be preserved on execute failure, got {:?}",
            err.warnings
        );
        assert!(
            err.warnings.contains(&"EXPLAIN failed".to_string()),
            "EXPLAIN warning should be preserved on execute failure, got {:?}",
            err.warnings
        );
    }

    #[tokio::test]
    async fn validate_failure_propagates_as_err() {
        let runner = MockRunner::new();
        let mut spec = sample_spec();
        // Reference a column that doesn't exist to trip validate.
        spec.columns.push(crate::query::ir::ColumnSel {
            table_alias: "t0".to_string(),
            column: "missing_column".to_string(),
        });
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap_err();

        assert!(!err.message.is_empty());
        // Neither compile nor EXPLAIN nor execute is reached when validation fails —
        // this is the fast-fail path the outer `run_query` previously relied on.
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 0);
        assert_eq!(runner.explain_calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn validate_failure_unset_single_value_blocks_explain() {
        let runner = MockRunner::new();
        let mut spec = sample_spec();
        // FilterOp::Eq has ValueKind::Single, so a missing `value` is
        // rejected by validate (not compile) before any SQL is emitted.
        let bad_filter = crate::query::ir::FilterSpec {
            id: "f1".to_string(),
            table_alias: "t0".to_string(),
            column: "id".to_string(),
            op: FilterOp::Eq,
            value: None,
        };
        spec.filters.children = vec![crate::query::ir::FilterNode::Leaf(bad_filter)];
        let def = sample_def();
        let schema = sample_schema();
        let settings = sample_settings();

        let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap_err();

        assert!(!err.message.is_empty());
        // Neither compile nor EXPLAIN nor execute is reached when validation
        // fails — the validate short-circuit is the fast-fail path the outer
        // `run_query` previously relied on.
        assert_eq!(runner.explain_calls.load(Ordering::SeqCst), 0);
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 0);
    }

    /// Regression for the pre-fix behavior where `run_query` compiled and
    /// EXPLAINed the spec *before* `validate` clamped `spec.limit`. That made
    /// the cost estimate reflect the raw user SQL (e.g. `LIMIT 0` or
    /// `LIMIT 9999`) instead of the SQL that actually executes. EXPLAIN now
    /// runs inside `run_query_core`, after validate, so the compiled SQL
    /// handed to `explain` must carry the clamped limit.
    #[tokio::test]
    async fn explain_runs_against_post_validate_compiled_query() {
        let runner = MockRunner::new();
        let mut spec = sample_spec();
        spec.limit = 0; // validate defaults 0 -> DEFAULT_LIMIT (100)
        let def = sample_def(); // Postgres -> SQL ends with "LIMIT <n>"
        let schema = sample_schema();
        let settings = sample_settings();

        let _ = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap();

        let explain_sql = runner
            .last_explain_sql
            .lock()
            .unwrap()
            .clone()
            .expect("EXPLAIN was not called");
        assert!(
            explain_sql.ends_with("LIMIT 100"),
            "EXPLAIN should run against the validate-clamped query (LIMIT 100), got: {explain_sql}"
        );
        // The mutated spec carries the clamped limit too.
        assert_eq!(spec.limit, 100);

        // Same invariant for the over-limit case: validate caps to MAX_LIMIT (1000).
        let runner2 = MockRunner::new();
        let mut spec2 = sample_spec();
        spec2.limit = 9999;
        let _ = run_query_core(&runner2, &def, &mut spec2, &schema, &settings, false)
            .await
            .unwrap();
        let explain_sql2 = runner2
            .last_explain_sql
            .lock()
            .unwrap()
            .clone()
            .expect("EXPLAIN was not called");
        assert!(
            explain_sql2.ends_with("LIMIT 1000"),
            "EXPLAIN should run against the validate-capped query (LIMIT 1000), got: {explain_sql2}"
        );
    }

    /// Regression for the pre-fix behavior where a spec that violated a
    /// blocked schema could surface a compile error instead of the
    /// safety-check error, because compile ran before validate. validate now
    /// runs first, so the blocked-schema error wins.
    #[tokio::test]
    async fn blocked_schema_error_wins_over_later_compile_failure() {
        let runner = MockRunner::new();
        let mut spec = sample_spec();
        // Block the schema via settings, AND add a filter that would fail
        // compile (Eq with no value) — the safety-check error should win.
        spec.tables[0].schema = "audit".to_string();
        spec.filters.children = vec![crate::query::ir::FilterNode::Leaf(
            crate::query::ir::FilterSpec {
                id: "f1".to_string(),
                table_alias: "t0".to_string(),
                column: "id".to_string(),
                op: FilterOp::Eq,
                value: None,
            },
        )];
        let def = sample_def();
        let schema = sample_schema();
        let settings = Settings {
            blocked_schemas: vec!["audit".to_string()],
            ..sample_settings()
        };

        let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
            .await
            .unwrap_err();

        assert!(
            err.message.contains("blocked"),
            "expected blocked-schema safety error, got: {}",
            err.message
        );
        // validate rejected before compile/explain/execute ran at all.
        assert_eq!(runner.explain_calls.load(Ordering::SeqCst), 0);
        assert_eq!(runner.execute_calls.load(Ordering::SeqCst), 0);
    }
}
