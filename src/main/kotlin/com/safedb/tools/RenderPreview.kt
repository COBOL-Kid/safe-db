package com.safedb.tools

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.explore.MeasureFn
import com.safedb.explore.DateGroupUnit
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.PivotShowAs
import com.safedb.explore.ShowAsMode
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.HistoryEntry
import com.safedb.model.IndexInfo
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.markIndexedColumns
import com.safedb.service.SafeDbService
import com.safedb.ui.AppShell
import com.safedb.ui.ExploreWindowContent
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.ExploreViewModel
import com.safedb.viewmodel.createExploreSession
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Dev-only utility: renders the main screens headlessly to PNG files in
 * /tmp/safedb-preview for visual verification without a display.
 */
private class FakeService : SafeDbService {
    val connections = listOf(
        ConnectionDef(
            id = "c1", name = "Production Replica", dialect = Dialect.Postgres,
            host = "replica.internal.acme.io", port = 5432, database = "acme_prod", username = "readonly",
        ),
        ConnectionDef(
            id = "c2", name = "Local MySQL", dialect = Dialect.MySql,
            host = "localhost", port = 3306, database = "safedb_test", username = "root",
        ),
    )

    private fun table(
        name: String,
        cols: List<ColumnInfo>,
        indexes: List<IndexInfo>,
        foreignKeys: List<ForeignKeyInfo> = emptyList(),
    ): TableInfo {
        val mutable = cols.toMutableList()
        markIndexedColumns(mutable, indexes)
        return TableInfo(
            schema = "public",
            name = name,
            columns = mutable,
            indexes = indexes,
            foreignKeys = foreignKeys,
        )
    }

    val schema = Schema(
        tables = listOf(
            table(
                "customers",
                listOf(
                    ColumnInfo("id", "bigint", false),
                    ColumnInfo("email", "varchar", false),
                    ColumnInfo("full_name", "varchar", true),
                    ColumnInfo("created_at", "timestamp", false),
                ),
                listOf(IndexInfo("customers_pkey", listOf("id"), isPrimary = true, isUnique = true)),
            ),
            table(
                "orders",
                listOf(
                    ColumnInfo("id", "bigint", false),
                    ColumnInfo("customer_id", "bigint", false),
                    ColumnInfo("status", "varchar", false),
                    ColumnInfo("total_cents", "bigint", false),
                    ColumnInfo("placed_at", "timestamp", false),
                ),
                listOf(
                    IndexInfo("orders_pkey", listOf("id"), isPrimary = true, isUnique = true),
                    IndexInfo("orders_customer_idx", listOf("customer_id")),
                ),
                listOf(
                    ForeignKeyInfo(
                        name = "orders_customer_id_fkey",
                        columns = listOf("customer_id"),
                        referencedSchema = "public",
                        referencedTable = "customers",
                        referencedColumns = listOf("id"),
                    ),
                ),
            ),
            table(
                "products",
                listOf(
                    ColumnInfo("id", "bigint", false),
                    ColumnInfo("sku", "varchar", false),
                    ColumnInfo("name", "varchar", false),
                    ColumnInfo("price_cents", "bigint", false),
                ),
                listOf(IndexInfo("products_pkey", listOf("id"), isPrimary = true, isUnique = true)),
            ),
        ),
    )

    private val sampleSpec = QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
    )

    override suspend fun testConnection(def: ConnectionDef, password: String) = "ok"
    override suspend fun saveConnection(def: ConnectionDef, password: String?) {}
    override suspend fun createConnection(def: ConnectionDef, password: String) = def
    override suspend fun updateConnection(def: ConnectionDef, password: String?) {}
    override suspend fun listConnections() = connections
    override suspend fun deleteConnection(id: String) {}
    override suspend fun lockCredentials() {}
    override suspend fun getSchema(connectionId: String) = schema

    override suspend fun runQuery(connectionId: String, spec: QuerySpec, force: Boolean): QueryResult =
        QueryResult(
            columns = listOf(
                ResultColumn("t0__id", "bigint"),
                ResultColumn("t0__customer_id", "bigint"),
                ResultColumn("t0__status", "varchar"),
                ResultColumn("t0__total_cents", "bigint"),
                ResultColumn("t0__placed_at", "timestamp"),
            ),
            rows = (1..40L).map { i ->
                listOf(
                    ResultCell.IntegerCell(i),
                    ResultCell.IntegerCell(1000 + i),
                    ResultCell.text(if (i % 3 == 0L) "shipped" else "pending"),
                    ResultCell.IntegerCell(1299 * i),
                    ResultCell.text("2026-06-${"%02d".format((i % 28) + 1)} 14:${"%02d".format(i % 60)}"),
                )
            },
            rowCount = 40,
            truncated = false,
            warnings = emptyList(),
        )

    override suspend fun listSavedQueries() = listOf(
        SavedQuery("s1", "Pending orders by customer", "c1", sampleSpec, "1719400000"),
        SavedQuery("s2", "Revenue last 30 days", "c1", sampleSpec, "1719300000"),
        SavedQuery("s3", "Low inventory products", "c2", sampleSpec, "1719200000"),
    )

    override suspend fun saveSavedQuery(query: SavedQuery) {}
    override suspend fun deleteSavedQuery(id: String) {}

    override suspend fun listHistory() = listOf(
        HistoryEntry("h1", "c1", "Production Replica", sampleSpec, 87, emptyList(), null, "1719410000"),
        HistoryEntry("h2", "c1", "Production Replica", sampleSpec, 0, listOf("Result truncated at limit"), null, "1719395000"),
        HistoryEntry("h3", "c2", "Local MySQL", sampleSpec, 0, emptyList(), "Join on non-indexed column rejected", "1719380000"),
    )

    override suspend fun clearHistory() {}
    override suspend fun getSettings() = Settings()
    override suspend fun saveSettings(settings: Settings) {}
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(
    name: String,
    isDark: Boolean,
    sidebarCollapsed: Boolean = false,
    prepare: (AppState, AppViewModel) -> Unit,
) {
    val service = FakeService()
    val appState = AppState(service)
    val viewModel = AppViewModel(service)
    Thread.sleep(700)
    prepare(appState, viewModel)
    Thread.sleep(700)

    ImageComposeScene(width = 1280, height = 832, density = Density(1f)) {
        SafeDbTheme(isDark = isDark) {
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                AppShell(
                    appState = appState,
                    viewModel = viewModel,
                    paletteOpen = false,
                    onPaletteOpenChange = {},
                    initialSidebarCollapsed = sidebarCollapsed,
                )
            }
        }
    }.use { scene ->
        scene.render(0L)
        Thread.sleep(300)
        val image = scene.render(300_000_000L)
        val out = File("/tmp/safedb-preview/$name.png")
        out.parentFile.mkdirs()
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${out.absolutePath}")
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderExplore(name: String, isDark: Boolean, pivoted: Boolean = false) {
    val service = FakeService()
    val spec = QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
    )
    val sample = runBlocking { service.runQuery("c1", spec, force = false) }
    val viewModel = ExploreViewModel(createExploreSession(service.connections.first(), spec, sample))
    if (pivoted) {
        viewModel.updateConfig {
            it.copy(
                rowDimensions = listOf(
                    PivotDimension(
                        "t0__placed_at",
                        "Year",
                        id = "placed_year",
                        grouping = PivotGrouping.Date(DateGroupUnit.Year),
                    ),
                    PivotDimension(
                        "t0__placed_at",
                        "Day",
                        id = "placed_day",
                        grouping = PivotGrouping.Date(DateGroupUnit.Day),
                    ),
                ),
                columnDimensions = listOf(PivotDimension("t0__status", "Status", id = "status")),
                columnDimension = null,
                measures = listOf(
                    PivotMeasure.countRows(),
                    PivotMeasure(
                        "sum_t0__total_cents",
                        MeasureFn.Sum,
                        "t0__total_cents",
                        "Revenue",
                        numberFormat = PivotNumberFormat(NumberFormatKind.Currency, decimals = 0),
                    ),
                    PivotMeasure(
                        "share",
                        MeasureFn.Count,
                        label = "Share",
                        showAs = PivotShowAs(ShowAsMode.PercentGrandTotal),
                    ),
                ),
                filters = listOf(PivotFilter.Members("status-filter", "t0__status", "Status")),
            )
        }
    }

    ImageComposeScene(width = 1120, height = 760, density = Density(1f)) {
        SafeDbTheme(isDark = isDark) {
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                ExploreWindowContent(
                    viewModel = viewModel,
                    currentSpec = spec,
                    onClose = {},
                )
            }
        }
    }.use { scene ->
        scene.render(0L)
        Thread.sleep(300)
        val image = scene.render(300_000_000L)
        val out = File("/tmp/safedb-preview/$name.png")
        out.parentFile.mkdirs()
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${out.absolutePath}")
    }
}

fun main() {
    System.setProperty("java.awt.headless", "false")

    for (dark in listOf(false, true)) {
        val suffix = if (dark) "dark" else "light"

        render("home-$suffix", dark) { _, _ -> }

        render("connections-$suffix", dark) { state, _ ->
            state.navigate(AppRoute.Connections)
        }

        render("builder-$suffix", dark) { state, vm ->
            state.setActiveConnection("c1")
            state.navigate(AppRoute.Builder)
            vm.schema.load("c1") { loaded ->
                if (loaded) {
                    vm.query.addTable(vm.schema.tables[1])
                    vm.query.addTable(vm.schema.tables[0])
                    vm.query.moveTable("t1", 360f, 90f)
                    vm.query.toggleColumn("t0", "id")
                    vm.query.toggleColumn("t0", "status")
                    vm.query.toggleColumn("t0", "total_cents")
                    vm.query.run("c1")
                }
            }
            Thread.sleep(900)
        }

        render("builder-collapsed-$suffix", dark, sidebarCollapsed = true) { state, vm ->
            state.setActiveConnection("c1")
            state.navigate(AppRoute.Builder)
            vm.schema.load("c1") { loaded ->
                if (loaded) {
                    vm.query.addTable(vm.schema.tables[1])
                    vm.query.addTable(vm.schema.tables[0])
                    vm.query.moveTable("t1", 360f, 90f)
                    vm.query.toggleColumn("t0", "id")
                    vm.query.toggleColumn("t0", "status")
                    vm.query.toggleColumn("t0", "total_cents")
                    vm.query.run("c1")
                }
            }
            Thread.sleep(900)
        }

        render("history-$suffix", dark) { state, _ ->
            state.navigate(AppRoute.History)
        }

        renderExplore("explore-$suffix", dark)
        renderExplore("explore-pivot-$suffix", dark, pivoted = true)
    }
}
