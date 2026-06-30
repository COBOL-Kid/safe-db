mod support;

use safe_db_lib::adapters::ExplainResult;
use safe_db_lib::query::ir::QueryResult;
use safe_db_lib::test_support::{COST_GUARD_PREFIX, run_query_core};

#[tokio::test]
async fn run_query_core_truncates_results_to_limit() {
    let def = support::sample_connection("c1");
    let mut spec = support::sample_spec();
    spec.limit = 1;
    let schema = support::sample_schema();
    let settings = support::sample_settings();
    let runner = support::MockRunner::new();
    runner.set_explain(ExplainResult::Estimated(0.0));
    runner.set_execute(Ok(QueryResult {
        columns: vec![],
        rows: vec![vec![], vec![], vec![]],
        row_count: 3,
        truncated: false,
        warnings: vec!["adapter warning".to_string()],
    }));

    let result = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
        .await
        .expect("query should succeed");

    assert_eq!(result.row_count, 1);
    assert!(result.truncated);
    assert_eq!(result.warnings, vec!["adapter warning".to_string()]);
}

#[tokio::test]
async fn run_query_core_blocks_when_cost_exceeds_threshold() {
    let def = support::sample_connection("c1");
    let mut spec = support::sample_spec();
    let schema = support::sample_schema();
    let mut settings = support::sample_settings();
    settings.explain_cost_threshold = 1.0;
    let runner = support::MockRunner::new();
    runner.set_explain(ExplainResult::Estimated(5.0));

    let err = run_query_core(&runner, &def, &mut spec, &schema, &settings, false)
        .await
        .expect_err("cost guard should block");

    assert!(err.message.starts_with(COST_GUARD_PREFIX));
    assert!(
        err.warnings
            .iter()
            .any(|warning| warning.contains("exceeds threshold"))
    );
}

#[tokio::test]
async fn run_query_core_allows_forced_retry_after_cost_guard() {
    let def = support::sample_connection("c1");
    let mut spec = support::sample_spec();
    let schema = support::sample_schema();
    let mut settings = support::sample_settings();
    settings.explain_cost_threshold = 1.0;
    let runner = support::MockRunner::new();
    runner.set_explain(ExplainResult::Estimated(5.0));

    let result = run_query_core(&runner, &def, &mut spec, &schema, &settings, true)
        .await
        .expect("forced retry should run");

    assert_eq!(result.row_count, 0);
    assert_eq!(
        runner
            .execute_calls
            .load(std::sync::atomic::Ordering::SeqCst),
        1
    );
}
