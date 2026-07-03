package com.safedb.query

import com.safedb.model.ColumnInfo
import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.IndexInfo
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.QuerySpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val products = TableInfo(
    schema = "safedb_test",
    name = "products",
    columns = listOf(
        ColumnInfo("id", "int", nullable = false, isIndexed = true),
        ColumnInfo("name", "varchar", nullable = false, isIndexed = false),
        ColumnInfo("created_at", "timestamp", nullable = false, isIndexed = false),
    ),
    indexes = emptyList(),
)

private val categories = TableInfo(
    schema = "safedb_test",
    name = "categories",
    columns = listOf(
        ColumnInfo("id", "int", nullable = false, isIndexed = true),
        ColumnInfo("name", "varchar", nullable = false, isIndexed = true),
    ),
    indexes = emptyList(),
)

private class TestHydrationTarget : QueryHydrationTarget {
    private var aliasCounter = 0
    private val canvasTables = mutableListOf<TableInfo>()
    val selectedColumns = mutableSetOf<String>()
    val joins = mutableListOf<JoinSpec>()
    private var _filters: FilterGroup = FilterGroup.empty()
    private var _connectorOverrides: Map<String, com.safedb.model.GroupConnector> = emptyMap()
    private var _limit: Int = DEFAULT_LIMIT

    val filters: FilterGroup get() = _filters
    val connectorOverrides: Map<String, com.safedb.model.GroupConnector> get() = _connectorOverrides
    val limit: Int get() = _limit

    override fun clear() {
        aliasCounter = 0
        canvasTables.clear()
        selectedColumns.clear()
        joins.clear()
        _filters = FilterGroup.empty()
        _connectorOverrides = emptyMap()
        _limit = DEFAULT_LIMIT
    }

    override fun addTable(tableInfo: TableInfo) {
        canvasTables.add(tableInfo)
        aliasCounter++
    }

    override val tables: List<AliasRef>
        get() = canvasTables.indices.map { AliasRef("t${it}") }

    override fun toggleColumn(alias: String, column: String) {
        val key = columnKey(alias, column)
        if (selectedColumns.contains(key)) {
            selectedColumns.remove(key)
        } else {
            selectedColumns.add(key)
        }
    }

    override fun addJoin(join: JoinSpec) {
        joins.add(join)
    }

    override fun setFilters(group: FilterGroup) {
        _filters = group
    }

    override fun setConnectorOverrides(map: Map<String, com.safedb.model.GroupConnector>) {
        _connectorOverrides = map
    }

    override fun setLimit(limit: Int) {
        _limit = limit
    }
}

class HydrateQueryTest {
    @Test
    fun remapsAliasesAndRestoresColumnsJoinsFiltersAndLimit() {
        val target = TestHydrationTarget()
        val spec = QuerySpec(
            tables = listOf(
                TableRef("safedb_test", "products", "saved_t0"),
                TableRef("safedb_test", "categories", "saved_t1"),
            ),
            columns = listOf(
                com.safedb.model.ColumnSel("saved_t0", "name"),
                com.safedb.model.ColumnSel("saved_t1", "name"),
            ),
            joins = listOf(
                JoinSpec("saved_t0", "id", "saved_t1", "id"),
            ),
            filters = FilterGroup(
                connector = com.safedb.model.GroupConnector.And,
                children = listOf(
                    FilterNode.Leaf(
                        FilterSpec(
                            tableAlias = "saved_t0",
                            column = "name",
                            op = FilterOp.Like,
                            value = FilterValue.Single(
                                com.safedb.model.FilterLiteral(LiteralKind.Text, "%widget%"),
                            ),
                        ),
                    ),
                ),
            ),
            limit = 25,
            schemaVersion = 2,
            connectorOverrides = emptyMap(),
        )

        hydrateQueryFromSpec(spec, listOf(products, categories), target)

        assertEquals(2, target.tables.size)
        assertEquals("t0", target.tables[0].alias)
        assertEquals("t1", target.tables[1].alias)
        assertTrue(target.selectedColumns.contains(columnKey("t0", "name")))
        assertTrue(target.selectedColumns.contains(columnKey("t1", "name")))
        assertEquals(
            JoinSpec("t0", "id", "t1", "id"),
            target.joins.single(),
        )
        assertEquals(1, target.filters.children.size)
        val leaf = target.filters.children[0] as FilterNode.Leaf
        assertEquals("t0", leaf.spec.tableAlias)
        assertEquals(25, target.limit)
    }

    @Test
    fun skipsJoinsAndFiltersWhenReferencedTablesAreMissing() {
        val target = TestHydrationTarget()
        val spec = QuerySpec(
            tables = listOf(
                TableRef("safedb_test", "missing_a", "saved_t0"),
                TableRef("safedb_test", "missing_b", "saved_t1"),
                TableRef("safedb_test", "products", "saved_t2"),
            ),
            columns = listOf(com.safedb.model.ColumnSel("saved_t2", "name")),
            joins = listOf(
                JoinSpec("saved_t0", "id", "saved_t1", "id"),
                JoinSpec("saved_t2", "id", "saved_t1", "id"),
            ),
            filters = FilterGroup(
                children = listOf(
                    FilterNode.Leaf(
                        FilterSpec(
                            tableAlias = "saved_t0",
                            column = "name",
                            op = FilterOp.Eq,
                            value = FilterValue.Single(
                                com.safedb.model.FilterLiteral(LiteralKind.Text, "x"),
                            ),
                        ),
                    ),
                ),
            ),
            limit = DEFAULT_LIMIT,
        )

        val warnings = hydrateQueryFromSpec(spec, listOf(products), target)

        assertEquals(1, target.tables.size)
        assertTrue(target.joins.isEmpty())
        assertTrue(target.filters.children.isEmpty())
        assertEquals(2, warnings.droppedTables.size)
        assertEquals(2, warnings.droppedJoins)
        assertTrue(warnings.droppedFilters)
    }

    @Test
    fun formatHydrationWarningBuildsReadableMessage() {
        val message = formatHydrationWarning(
            HydrationWarnings(
                droppedTables = listOf("public.old_table"),
                droppedColumns = listOf("t0.col1", "t0.col2"),
                droppedJoins = 1,
                droppedFilters = true,
            ),
        )
        assertTrue(message!!.contains("missing tables"))
        assertTrue(message.contains("2 selected columns"))
        assertTrue(message.contains("1 join"))
        assertTrue(message.contains("some filters were dropped"))
    }
}
