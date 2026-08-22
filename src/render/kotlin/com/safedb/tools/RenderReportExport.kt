package com.safedb.tools

import com.safedb.explore.ChartType
import com.safedb.explore.ExploreMode
import com.safedb.explore.MeasureFn
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import com.safedb.viewmodel.ExploreViewModel
import com.safedb.viewmodel.createExploreSession
import java.nio.file.Files
import java.nio.file.Path

// Real export path (ExploreViewModel.saveVisualizationHtml), so the marketing site records the
// actual artifact. Output: /tmp/safedb-preview/report/explore-production-replica-chart.html
fun main() {
    val statuses = listOf("pending", "shipped", "delivered", "returned", "cancelled")
    // Deterministic sample: weights and prices vary per status so the chart has shape.
    val weights = listOf(3, 4, 3, 1, 1)
    val baseCents = listOf(48_00L, 96_00L, 72_00L, 39_00L, 22_00L)
    val rows =
        (0 until 120).map { i ->
            val statusIndex = weights.flatMapIndexed { idx, w -> List(w) { idx } }[i % 12]
            val totalCents = baseCents[statusIndex] + (i * 137L) % 9100L
            listOf(
                ResultCell.integer(48_100L + i),
                ResultCell.integer(800L + (i * 61) % 2600),
                ResultCell.text(statuses[statusIndex]),
                ResultCell.integer(totalCents),
                ResultCell.text(
                    "2026-08-${"%02d".format((i % 17) + 1)} ${"%02d".format(8 + (i % 11))}:${"%02d".format((i * 7) % 60)}"
                ),
            )
        }
    val sample =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("t0__id", "bigint"),
                    ResultColumn("t0__customer_id", "bigint"),
                    ResultColumn("t0__status", "varchar"),
                    ResultColumn("t0__total_cents", "bigint"),
                    ResultColumn("t0__placed_at", "timestamp"),
                ),
            rows = rows,
            rowCount = rows.size,
            truncated = false,
            warnings = emptyList(),
        )
    val connection =
        ConnectionDef(
            id = "c1",
            name = "Production Replica",
            dialect = Dialect.Postgres,
            host = "replica.internal.acme.io",
            port = 5432,
            database = "acme_prod",
            username = "readonly",
        )
    val spec =
        QuerySpec(
            tables = listOf(TableRef("public", "orders", "t0")),
            columns = emptyList(),
            joins = emptyList(),
            filters = FilterGroup.empty(),
            limit = 500,
        )

    val viewModel = ExploreViewModel(createExploreSession(connection, spec, sample))
    viewModel.selectMode(ExploreMode.Visualization)
    viewModel.updateVisualization {
        VisualizationConfig(
            chartType = ChartType.Bar,
            x = VisualizationField("t0__status", "Status"),
            values =
                listOf(
                    VisualizationMeasure(
                        "revenue",
                        MeasureFn.Sum,
                        "t0__total_cents",
                        "Revenue",
                        numberFormat = PivotNumberFormat(NumberFormatKind.Currency, decimals = 0),
                    )
                ),
            title = "Revenue by order status",
        )
    }

    val dir = Path.of("/tmp/safedb-preview/report")
    Files.createDirectories(dir)
    val output = dir.resolve("explore-production-replica-chart.html")
    viewModel.saveVisualizationHtml(output)
    viewModel.exportError?.let { error("Export failed: $it") }
    println("Wrote $output")
}
