use crate::adapters::{Adapter, DEFAULT_TIMEOUT_MS, ExplainResult};
use crate::introspect::Schema;
use crate::query::{
    QueryResult, QuerySpec,
    ir::CompiledQuery,
    {compile_validated, validate_query},
};
use crate::settings::Settings;
use crate::types::ConnectionDef;

/// Abstraction over the subset of `Adapter` that `run_query` needs. The trait
/// is intentionally minimal so tests can implement it with a mock.
#[allow(async_fn_in_trait)]
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
    let (validated, outcome) =
        validate_query(spec, schema, &settings.blocked_schemas).map_err(|e| QueryCoreError {
            message: e.to_string(),
            warnings: vec![],
        })?;

    let compiled = compile_validated(&validated, def.dialect).map_err(|e| QueryCoreError {
        message: e.to_string(),
        warnings: outcome.warnings.clone(),
    })?;

    let mut warnings = outcome.warnings;

    let explain_result = match runner.explain(&compiled).await {
        Ok(r) => r,
        Err(error) => ExplainResult::Unavailable(format!("EXPLAIN failed: {error}")),
    };

    let (explain_failed, over_cost) = match explain_result {
        ExplainResult::Estimated(cost) => {
            let threshold = settings.cost_threshold(def.dialect);
            let over_cost = cost > threshold;
            if over_cost {
                warnings.push(format!(
                    "Estimated query cost ({cost:.0}) exceeds threshold ({:.0}) — this may be slow",
                    threshold
                ));
            }
            (false, over_cost)
        }
        ExplainResult::Unavailable(reason) => {
            warnings.push(reason);
            (true, false)
        }
    };

    if (explain_failed || over_cost) && !force {
        let reason = if explain_failed && over_cost {
            "EXPLAIN failed and estimated cost exceeds threshold"
        } else if explain_failed {
            "EXPLAIN failed"
        } else {
            "Estimated query cost exceeds threshold"
        };
        return Err(QueryCoreError {
            message: format!("{COST_GUARD_PREFIX}{reason}. Confirm to run this query anyway."),
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
    let limit_truncated = result.rows.len() > outcome.limit as usize;
    if limit_truncated {
        result.rows.truncate(outcome.limit as usize);
    }
    result.row_count = result.rows.len();
    result.truncated |= limit_truncated;
    warnings.extend(result.warnings);
    result.warnings = warnings;
    Ok(result)
}
