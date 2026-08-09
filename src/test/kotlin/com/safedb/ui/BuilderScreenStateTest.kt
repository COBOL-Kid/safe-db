package com.safedb.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.safedb.canvas.CanvasPoint
import com.safedb.canvas.CanvasTableLike
import com.safedb.canvas.ColumnJoinPort
import com.safedb.canvas.JoinPortSide
import com.safedb.canvas.JoinPortVisibility
import com.safedb.canvas.RoutedJoinEdge
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.QueryRiskGate
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskAssessment
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.viewmodel.CanvasViewportState
import com.safedb.viewmodel.canvasAxisScrollState
import com.safedb.viewmodel.canvasConstrainedPan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuilderScreenStateTest {
    @Test
    fun connectionSwitchIgnoresTheAlreadyActiveConnection() {
        assertEquals(
            BuilderConnectionSwitchDecision.NoOp,
            builderConnectionSwitchDecision("c1", "c1", hasDraft = true),
        )
    }

    @Test
    fun connectionSwitchIsImmediateForAnEmptyCanvas() {
        assertEquals(
            BuilderConnectionSwitchDecision.SwitchImmediately,
            builderConnectionSwitchDecision("c1", "c2", hasDraft = false),
        )
    }

    @Test
    fun connectionSwitchRequiresConfirmationForAnExistingDraft() {
        assertEquals(
            BuilderConnectionSwitchDecision.ConfirmClear,
            builderConnectionSwitchDecision("c1", "c2", hasDraft = true),
        )
    }

    @Test
    fun builderContentBoundsIncludeTablesAndRoutedJoinLanes() {
        val table =
            CanvasTableLike(
                alias = "t0",
                x = 100f,
                y = 200f,
                width = 300f,
                height = 400f,
                tableInfo = TableInfo("public", "orders", emptyList(), emptyList()),
            )
        val first = CanvasPoint(50f, 150f)
        val last = CanvasPoint(500f, 700f)
        val edge =
            RoutedJoinEdge(
                points = listOf(first, CanvasPoint(500f, 150f), last),
                sourcePort = ColumnJoinPort(first, JoinPortSide.Left, JoinPortVisibility.Visible),
                targetPort = ColumnJoinPort(last, JoinPortSide.Right, JoinPortVisibility.Visible),
            )

        assertEquals(
            Rect(50f, 150f, 500f, 700f),
            builderCanvasContentBounds(listOf(table), listOf(edge)),
        )
    }

    @Test
    fun builderWorkspaceIncludesNegativeContentAndPreservesTheMinimumLayoutArea() {
        val workspace =
            builderCanvasWorkspaceBounds(
                contentBounds = Rect(-240f, -80f, 500f, 700f),
                minimumSize = Size(2_400f, 1_800f),
                padding = 36f,
            )

        assertEquals(Rect(-276f, -116f, 2_400f, 1_800f), workspace)
    }

    @Test
    fun fittedBuilderPanSurvivesWorkspaceConstraintForNegativeContent() {
        val content = Rect(-240f, -80f, 500f, 700f)
        val workspace =
            builderCanvasWorkspaceBounds(
                contentBounds = content,
                minimumSize = Size(2_400f, 1_800f),
                padding = 36f,
            )
        val viewportSize = Size(1_000f, 600f)
        val viewport = CanvasViewportState()
        viewport.fit(content, viewportSize, padding = 40f)
        val horizontal =
            canvasAxisScrollState(
                contentStart = workspace.left,
                contentEnd = workspace.right,
                viewportSize = viewportSize.width,
                zoom = viewport.zoom,
                pan = viewport.pan.x,
                padding = viewportSize.width / 2f,
            )
        val vertical =
            canvasAxisScrollState(
                contentStart = workspace.top,
                contentEnd = workspace.bottom,
                viewportSize = viewportSize.height,
                zoom = viewport.zoom,
                pan = viewport.pan.y,
                padding = viewportSize.height / 2f,
            )

        val constrained = canvasConstrainedPan(viewport.pan, horizontal, vertical)
        assertEquals(viewport.pan.x, constrained.x, 0.001f)
        assertEquals(viewport.pan.y, constrained.y, 0.001f)
        assertTrue(content.left * viewport.zoom + constrained.x >= 40f - 0.001f)
        assertTrue(content.top * viewport.zoom + constrained.y >= 40f - 0.001f)
        assertTrue(content.right * viewport.zoom + constrained.x <= 960f + 0.001f)
        assertTrue(content.bottom * viewport.zoom + constrained.y <= 560f + 0.001f)
    }

    @Test
    fun downArrowRestoresMaximizedResultsPaneToMinimumHeight() {
        val state = toggleResultsPane(ResultsPaneMode.Maximized, height = 240f)

        assertEquals(ResultsPaneMode.Normal, state.mode)
        assertEquals(128f, state.height)
    }

    @Test
    fun upArrowMaximizesResultsPaneWithoutChangingItsResizeHeight() {
        val state = toggleResultsPane(ResultsPaneMode.Normal, height = 320f)

        assertEquals(ResultsPaneMode.Maximized, state.mode)
        assertEquals(320f, state.height)
    }

    @Test
    fun groupingOrderLabelsPreservePriorityAndResolveTableNames() {
        val labels =
            groupingOrderLabels(
                groups =
                    listOf(
                        GroupSpec("t1", "region"),
                        GroupSpec("t0", "status"),
                        GroupSpec("missing", "category"),
                    ),
                tableNamesByAlias = mapOf("t0" to "orders", "t1" to "customers"),
            )

        assertEquals(listOf("customers.region", "orders.status", "missing.category"), labels)
    }

    @Test
    fun sortOrderLabelsPreservePriorityDirectionAndResolveTableNames() {
        val labels =
            sortOrderLabels(
                sorts =
                    listOf(
                        SortSpec("t0", "status", SortDirection.Asc),
                        SortSpec("missing", "created_at", SortDirection.Desc),
                    ),
                tableNamesByAlias = mapOf("t0" to "orders"),
            )

        assertEquals(listOf("orders.status ascending", "missing.created_at descending"), labels)
    }

    @Test
    fun joinLabelResolvesKnownTableNamesAndKeepsUnknownAliases() {
        assertEquals(
            "join: orders.customer_id = customers.id",
            joinLabel(
                JoinSpec("t0", "customer_id", "t1", "id"),
                mapOf("t0" to "orders", "t1" to "customers"),
            ),
        )
        assertEquals(
            "join: orders.customer_id = missing.id",
            joinLabel(JoinSpec("t0", "customer_id", "missing", "id"), mapOf("t0" to "orders")),
        )
    }

    @Test
    fun queryOptionsShowNoneOnlyForEmptySections() {
        assertEquals("None", queryOptionEmptyLabel(emptyList()))
        assertNull(queryOptionEmptyLabel(listOf("orders.status")))
    }

    @Test
    fun queryControlsCanvasInsetScalesWithDisplayDensity() {
        val insetPx = with(Density(2f)) { QueryControlsCanvasInset.toPx() }

        assertEquals(464f, insetPx)
        assertEquals(464f, canvasDisplayY(tableY = 0f, contentTopInsetPx = insetPx))
        assertEquals(494f, canvasDisplayY(tableY = 30f, contentTopInsetPx = insetPx))
    }

    @Test
    fun canvasPointForViewportPositionAccountsForPanZoomAndInset() {
        val point =
            canvasPointForViewportPosition(
                position = Offset(220f, 180f),
                zoom = 2f,
                pan = Offset(20f, 40f),
                contentTopInset = 60f,
            )

        assertEquals(100f, point.x)
        assertEquals(40f, point.y)
    }

    @Test
    fun queryControlsCanvasInsetKeepsBaselineAndExpandsForTallerOverlays() {
        assertEquals(QueryControlsCanvasInset, queryControlsCanvasInset(120.dp))
        assertEquals(324.dp, queryControlsCanvasInset(300.dp))
    }

    @Test
    fun queryControlsHeightUsesTheTallestVisibleOverlay() {
        assertEquals(
            310,
            queryControlsHeightPx(filterCount = 1, filterHeightPx = 208, optionHeightPx = 310),
        )
        assertEquals(
            260,
            queryControlsHeightPx(filterCount = 1, filterHeightPx = 260, optionHeightPx = 208),
        )
        assertEquals(
            208,
            queryControlsHeightPx(filterCount = 0, filterHeightPx = 310, optionHeightPx = 208),
        )
    }

    @Test
    fun queryOrderMoveTargetsRespectCurrentListBounds() {
        assertEquals(2, queryOrderMoveTarget(index = 1, offset = 1, lastIndex = 2))
        assertNull(queryOrderMoveTarget(index = 1, offset = 1, lastIndex = 1))
        assertEquals(1, queryOrderMoveTarget(index = 2, offset = -1, lastIndex = 2))
        assertNull(queryOrderMoveTarget(index = 0, offset = -1, lastIndex = 2))
    }

    @Test
    fun riskGateIndicatorNamesEveryEffectiveSetting() {
        assertEquals(
            "Risk gate: Cautious · blocks Elevated concern and above",
            riskGateIndicatorText(QueryRiskGate.Cautious),
        )
        assertEquals(
            "Risk gate: Standard · blocks High concern and above",
            riskGateIndicatorText(QueryRiskGate.Standard),
        )
        assertEquals(
            "Risk gate: Flexible · blocks Very high concern and above",
            riskGateIndicatorText(QueryRiskGate.Flexible),
        )
        assertEquals("Risk gate: Off", riskGateIndicatorText(QueryRiskGate.Disabled))
    }

    @Test
    fun queryRiskIndicatorKeepsSeveritySeparateFromGateDecision() {
        val assessment =
            QueryRiskAssessment(
                2,
                "f",
                6,
                QueryRiskSeverity.High,
                emptyMap(),
                emptyList(),
                emptyList(),
            )
        val blocked =
            QueryRiskDecision(
                "f",
                RiskGateState.Blocked,
                QueryRiskGate.Standard,
                QueryRiskSeverity.High,
                emptyList(),
            )
        val allowed =
            blocked.copy(
                state = RiskGateState.Allowed,
                effectiveGate = QueryRiskGate.Flexible,
                blockingBand = QueryRiskSeverity.VeryHigh,
            )

        val blockedEvaluation =
            QueryRiskEvaluation(
                assessment,
                assessment,
                QueryPlanStatus.Available,
                decision = blocked,
            )
        val allowedEvaluation = blockedEvaluation.copy(decision = allowed)

        assertEquals(
            "Query risk: High concern · Run blocked",
            queryRiskIndicatorText(assessment, blockedEvaluation, false, QueryRiskGate.Standard),
        )
        assertEquals(
            "Query risk: High concern · plan refined · Run enabled",
            queryRiskIndicatorText(assessment, allowedEvaluation, false, QueryRiskGate.Flexible),
        )
        assertEquals(
            "Assessing query risk · Run unavailable",
            queryRiskIndicatorText(assessment, null, true, QueryRiskGate.Standard),
        )
        assertEquals(
            "Preliminary query risk: High concern · Run available",
            queryRiskIndicatorText(assessment, null, false, QueryRiskGate.Standard),
        )
        assertEquals(
            "Query risk: Not required · Run enabled",
            queryRiskIndicatorText(null, null, false, QueryRiskGate.Disabled),
        )
    }

    @Test
    fun queryRiskIndicatorKeepsValidationSeparateFromAssessmentPending() {
        assertEquals(
            "Query validation: Choose at least one table",
            queryRiskIndicatorText(
                null,
                null,
                false,
                QueryRiskGate.Standard,
                "Choose at least one table",
            ),
        )
    }

    @Test
    fun queryRiskIndicatorAndDialogReportUnavailablePlanConfirmation() {
        val assessment =
            QueryRiskAssessment(
                2,
                "f",
                2,
                QueryRiskSeverity.Minimal,
                emptyMap(),
                emptyList(),
                emptyList(),
            )
        val requirement =
            QueryConfirmationRequirement(
                confirmation =
                    QueryExecutionConfirmation(
                        connectionId = "c1",
                        connectionFingerprint = "connection",
                        queryFingerprint = "f",
                        conditions =
                            setOf(
                                QueryConfirmationCondition(
                                    QueryConfirmationReasonCode.PlanUnavailable,
                                    "PermissionDenied",
                                )
                            ),
                    ),
                reasons =
                    listOf(
                        RiskDecisionReason("plan_unavailable", "The query plan is unavailable.")
                    ),
            )
        val evaluation =
            QueryRiskEvaluation(
                assessment,
                assessment,
                QueryPlanStatus.Unavailable,
                decision =
                    QueryRiskDecision(
                        "f",
                        RiskGateState.ConfirmationRequired,
                        QueryRiskGate.Standard,
                        QueryRiskSeverity.High,
                        requirement.reasons,
                    ),
                confirmationRequirement = requirement,
            )

        assertEquals(
            "Query plan safeguard: Confirmation required · Run unavailable",
            queryRiskIndicatorText(assessment, evaluation, false, QueryRiskGate.Standard),
        )
        assertEquals("Query plan unavailable", queryConfirmationDialogCopy(requirement).title)
        assertEquals(
            "The query plan is unavailable.",
            queryConfirmationDialogCopy(requirement).message,
        )
        assertEquals(
            "Plan unavailable; explicit confirmation is required.",
            planSafeguardBannerText(evaluation),
        )

        val accepted =
            evaluation.copy(
                decision =
                    evaluation.decision.copy(state = RiskGateState.Allowed, reasons = emptyList()),
                confirmationAccepted = true,
            )
        assertEquals(
            "Query risk: Minimal concern · plan safeguard confirmed · Run enabled",
            queryRiskIndicatorText(assessment, accepted, false, QueryRiskGate.Standard),
        )
        assertEquals(
            "Plan unavailable; execution was explicitly confirmed.",
            planSafeguardBannerText(accepted),
        )

        val disabled =
            evaluation.copy(
                staticAssessment = null,
                finalAssessment = null,
                decision =
                    evaluation.decision.copy(
                        effectiveGate = QueryRiskGate.Disabled,
                        blockingBand = null,
                    ),
            )
        assertEquals(
            "Query plan safeguard: Confirmation required · Run unavailable",
            queryRiskIndicatorText(null, disabled, false, QueryRiskGate.Disabled),
        )
        assertEquals(
            "Query risk: Not required · plan safeguard confirmed · Run enabled",
            queryRiskIndicatorText(
                null,
                disabled.copy(
                    decision =
                        disabled.decision.copy(
                            state = RiskGateState.Allowed,
                            reasons = emptyList(),
                        ),
                    confirmationAccepted = true,
                ),
                false,
                QueryRiskGate.Disabled,
            ),
        )
    }

    @Test
    fun preliminaryHighRiskDoesNotPresentAsRunBlocked() {
        val assessment =
            QueryRiskAssessment(
                2,
                "f",
                8,
                QueryRiskSeverity.VeryHigh,
                emptyMap(),
                emptyList(),
                emptyList(),
            )

        assertEquals(
            "Preliminary query risk: Very high concern · Run available",
            queryRiskIndicatorText(assessment, null, false, QueryRiskGate.Cautious),
        )
    }
}
