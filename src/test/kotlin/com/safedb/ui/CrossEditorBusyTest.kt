package com.safedb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.QueryRunResult
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.viewmodel.SavedQueriesViewModel
import com.safedb.viewmodel.SchemaViewModel
import com.safedb.viewmodel.SqlEditorViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalComposeUiApi::class)
class CrossEditorBusyTest {
    @Test
    fun builderRunAndConfirmDoNotStartWhileSqlBusy() {
        val service = CrossEditorBusyService()
        val harness = CrossEditorHarness(service)
        val sqlBusy = mutableStateOf(true)

        ImageComposeScene(width = 1400, height = 900, density = Density(1f)) {
                SafeDbTheme(isDark = false) { harness.BuilderContent(sqlBusy = sqlBusy.value) }
            }
            .use { scene ->
                scene.assertOtherEditorBusyGuardsRunAndConfirm(
                    otherEditorBusy = sqlBusy,
                    service = service,
                    pendingConfirmation = { harness.queryViewModel.pendingConfirmation },
                )
            }
    }

    @Test
    fun sqlRunAndConfirmDoNotStartWhileBuilderBusy() {
        val service = CrossEditorBusyService()
        val harness = CrossEditorHarness(service)
        val builderBusy = mutableStateOf(true)

        ImageComposeScene(width = 1400, height = 900, density = Density(1f)) {
                SafeDbTheme(isDark = false) { harness.SqlContent(builderBusy = builderBusy.value) }
            }
            .use { scene ->
                scene.assertOtherEditorBusyGuardsRunAndConfirm(
                    otherEditorBusy = builderBusy,
                    service = service,
                    pendingConfirmation = { harness.sqlViewModel.pendingConfirmation },
                )
            }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.assertOtherEditorBusyGuardsRunAndConfirm(
    otherEditorBusy: MutableState<Boolean>,
    service: CrossEditorBusyService,
    pendingConfirmation: () -> QueryConfirmationRequirement?,
) {
    render(0L)

    val busyRun = requireExact("Run")
    assertTrue(busyRun.isDisabled(), "Run must be disabled while the other editor is busy")
    click(busyRun.boundsInRoot.center)
    settle()
    assertEquals(0, service.queryAttempts)

    otherEditorBusy.value = false
    settle()
    val idleRun = requireExact("Run")
    assertFalse(idleRun.isDisabled(), "Run must be enabled while the other editor is idle")
    click(idleRun.boundsInRoot.center)
    settle()
    assertEquals(1, service.queryAttempts)
    assertNotNull(pendingConfirmation())

    otherEditorBusy.value = true
    settle()
    click(requireContaining("Run with safeguards").boundsInRoot.center)
    settle()
    assertEquals(1, service.queryAttempts)
    assertNotNull(pendingConfirmation())

    otherEditorBusy.value = false
    settle()
    click(requireContaining("Run with safeguards").boundsInRoot.center)
    settle()
    assertEquals(2, service.queryAttempts)
    assertNull(pendingConfirmation())
}

private fun SemanticsNode.isDisabled(): Boolean {
    var node: SemanticsNode? = this
    while (node != null) {
        if (node.config.contains(SemanticsProperties.Disabled)) return true
        node = node.parent
    }
    return false
}

private class CrossEditorHarness(service: CrossEditorBusyService) {
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
    val queryViewModel = QueryViewModel(service, scope)
    val sqlViewModel = SqlEditorViewModel(service, scope)
    val schemaViewModel = SchemaViewModel(service, scope)
    val savedQueriesViewModel = SavedQueriesViewModel(service, scope)
    val recipesViewModel = RecipesViewModel(service, scope)

    init {
        schemaViewModel.load("c1", selection = selection)
        queryViewModel.addTable(ordersTable)
        sqlViewModel.onTextChanged(TextFieldValue("SELECT customer_id FROM orders LIMIT 5"))
    }

    @Composable
    fun BuilderContent(sqlBusy: Boolean) {
        BuilderScreen(
            connection = connectionDef,
            connections = listOf(connectionDef),
            queryViewModel = queryViewModel,
            savedQueriesViewModel = savedQueriesViewModel,
            recipesViewModel = recipesViewModel,
            schemaViewModel = schemaViewModel,
            schemaSelection = selection,
            schemaHistoryError = null,
            settings = Settings(),
            sqlBusy = sqlBusy,
            onConnectionSelected = {},
            onSchemaSelected = {},
            onUnavailableSchemaSelection = {},
            onDismissSchemaHistoryError = {},
            onOpenExplore = {},
            onOpenSettings = {},
            onApplyRecipe = { _, _ -> },
        )
    }

    @Composable
    fun SqlContent(builderBusy: Boolean) {
        SqlScreen(
            connection = connectionDef,
            connections = listOf(connectionDef),
            sqlViewModel = sqlViewModel,
            schemaViewModel = schemaViewModel,
            schemaSelection = selection,
            schemaHistoryError = null,
            settings = Settings(),
            parseResult =
                rememberSqlParseResult(
                    sqlText = sqlViewModel.text.text,
                    connection = connectionDef,
                    schemaViewModel = schemaViewModel,
                ),
            builderBusy = builderBusy,
            onConnectionSelected = {},
            onSchemaSelected = {},
            onUnavailableSchemaSelection = {},
            onDismissSchemaHistoryError = {},
            onOpenExplore = {},
            onOpenConnections = {},
        )
    }
}

private val ordersTable =
    TableInfo(
        schema = "public",
        name = "orders",
        columns =
            listOf(
                ColumnInfo(
                    name = "customer_id",
                    dataType = "bigint",
                    nullable = false,
                    isIndexed = true,
                    joinEligible = true,
                ),
            ),
        indexes = emptyList(),
    )

private val connectionDef =
    ConnectionDef(
        id = "c1",
        name = "Test Connection",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "testdb",
        username = "readonly",
    )

private class CrossEditorBusyService : FakeSafeDbServiceSupport() {
    var queryAttempts = 0
        private set

    private val requiredConfirmation =
        QueryExecutionConfirmation(
            connectionId = "c1",
            connectionFingerprint = "cf",
            queryFingerprint = "fp",
            conditions =
                setOf(
                    QueryConfirmationCondition(
                        QueryConfirmationReasonCode.PlanUnavailable,
                        "TimedOut",
                    ),
                ),
        )

    private val confirmationReason =
        RiskDecisionReason(
            code = "plan_unavailable",
            message = "Query plan assessment is unavailable.",
        )

    override suspend fun getSchema(connectionId: String) = Schema(listOf(ordersTable))

    override suspend fun runQuery(request: QueryRunRequest): QueryRunResult {
        queryAttempts += 1
        if (request.confirmation != requiredConfirmation) {
            val requirement =
                QueryConfirmationRequirement(requiredConfirmation, listOf(confirmationReason))
            throw QueryFailureException(
                QueryError.ConfirmationRequired(
                    evaluation =
                        QueryRiskEvaluation(
                            staticAssessment = null,
                            finalAssessment = null,
                            planStatus = QueryPlanStatus.Unavailable,
                            decision =
                                QueryRiskDecision(
                                    queryFingerprint = "fp",
                                    state = RiskGateState.ConfirmationRequired,
                                    effectiveGate = QueryRiskGate.Standard,
                                    blockingBand = null,
                                    reasons = listOf(confirmationReason),
                                ),
                            confirmationRequirement = requirement,
                        ),
                    requirement = requirement,
                    historySpec = request.spec,
                ),
            )
        }
        return QueryRunResult(
            QueryResult(
                columns = listOf(ResultColumn("customer_id", "bigint")),
                rows = listOf(listOf(ResultCell.text("1"))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            ),
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
    }
}
