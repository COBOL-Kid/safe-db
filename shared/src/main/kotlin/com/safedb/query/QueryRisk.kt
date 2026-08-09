package com.safedb.query

import com.safedb.model.Dialect
import com.safedb.model.Outcome
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.SafeDbJson
import com.safedb.model.Schema
import com.safedb.model.Settings
import java.security.MessageDigest

fun evaluateQueryRisk(
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    dialect: Dialect,
): Outcome<QueryRiskEvaluation> {
    val validated =
        when (val result = validateQuery(spec, schema, settings.blockedSchemas, dialect)) {
            is Outcome.Ok -> result.value.first
            is Outcome.Err -> return Outcome.err(result.message)
        }
    val assessment =
        if (settings.queryRiskGate == QueryRiskGate.Disabled) {
            null
        } else {
            assessStaticQueryRisk(validated, schema, dialect)
        }
    return Outcome.ok(
        QueryRiskEvaluation(
            staticAssessment = assessment,
            finalAssessment = assessment,
            planStatus =
                if (assessment == null) QueryPlanStatus.Disabled else QueryPlanStatus.NotRequested,
            decision = applyRiskGate(assessment, settings.queryRiskGate),
        )
    )
}

fun queryFingerprint(validated: ValidatedQuery): String {
    val canonical = SafeDbJson.lenient.encodeToString(QuerySpec.serializer(), validated.spec())
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
