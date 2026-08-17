package com.safedb.tools

import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.SortDirection
import com.safedb.viewmodel.AppViewModel

/**
 * Renders the query-builder scene as a sequence of cumulative real UI states for the marketing
 * site's hero clip. Each frame is an independent deterministic render; the website stitches the
 * PNGs into a short looping video. Output: /tmp/safedb-preview/hero/frame-NN.png (dark, Control
 * Blue). Frame NN applies the first NN build steps; the last frame runs the query.
 */
private const val TOTAL_STEPS = 7

private fun applySteps(state: AppState, vm: AppViewModel, steps: Int) {
    val selection = SchemaSelectionIntent("public", SchemaSelectionSource.User)
    state.setActiveConnection("c1", selection)
    state.navigate(AppRoute.Builder)
    vm.schema.load("c1", selection = selection) { loaded ->
        if (!loaded) return@load
        if (steps >= 1) vm.query.addTable(vm.schema.tables[1])
        if (steps >= 2) {
            vm.query.addTable(vm.schema.tables[0])
            vm.query.moveTable("t1", 360f, 28f)
        }
        if (steps >= 3) vm.query.addJoin(JoinSpec("t0", "customer_id", "t1", "id"))
        if (steps >= 4) {
            vm.query.toggleColumn("t0", "id")
            vm.query.toggleColumn("t0", "status")
            vm.query.toggleColumn("t0", "total_cents")
            vm.query.setGroups(
                listOf(
                    GroupSpec("t0", "status"),
                    GroupSpec("t0", "id"),
                    GroupSpec("t0", "total_cents"),
                )
            )
        }
        if (steps >= 5) {
            vm.query.setSort("t0", "status", SortDirection.Asc)
            vm.query.setSort("t0", "total_cents", SortDirection.Desc)
            vm.query.addFilter(
                FilterSpec(
                    tableAlias = "t0",
                    column = "status",
                    op = FilterOp.Eq,
                    value = FilterValue.Single(FilterLiteral(LiteralKind.Text, "pending")),
                )
            )
        }
        if (steps >= 6) vm.query.run("c1")
    }
    if (steps >= 6) Thread.sleep(900)
}

fun main() {
    for (step in 0 until TOTAL_STEPS) {
        render(name = "hero/frame-%02d".format(step), isDark = true) { state, vm ->
            applySteps(state, vm, step)
        }
    }
    println("hero frames written to /tmp/safedb-preview/hero/")
}
