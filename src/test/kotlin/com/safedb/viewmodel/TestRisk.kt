package com.safedb.viewmodel

import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.RiskGateState
import com.safedb.service.QueryRunResult

internal fun queryRunResult(result: QueryResult): QueryRunResult =
    QueryRunResult(
        result,
        QueryRiskEvaluation(
            staticAssessment = null,
            finalAssessment = null,
            planStatus = QueryPlanStatus.Disabled,
            decision =
                QueryRiskDecision(
                    "",
                    RiskGateState.Allowed,
                    QueryRiskGate.Disabled,
                    null,
                    emptyList(),
                ),
        ),
    )
