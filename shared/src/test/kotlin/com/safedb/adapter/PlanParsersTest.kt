package com.safedb.adapter

import com.safedb.model.PlanAccessMethod
import com.safedb.model.PlanOperationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanParsersTest {
    @Test
    fun postgresJsonNormalizesRelationsJoinsAndBlockingOperations() {
        val plan =
            parsePostgresPlan(
                """[{"Plan":{"Node Type":"Sort","Plan Rows":25000,"Plans":[{"Node Type":"Hash Join","Plan Rows":25000,"Plans":[{"Node Type":"Index Scan","Schema":"public","Relation Name":"orders","Alias":"t0","Plan Rows":12,"Index Cond":"(id = 1)"},{"Node Type":"Seq Scan","Schema":"public","Relation Name":"items","Alias":"t1","Plan Rows":120000}]}],"Total Cost":42.5}}]"""
            )

        assertNotNull(plan)
        assertEquals(
            PlanAccessMethod.BoundedLookup,
            plan.relations.first { it.alias == "t0" }.method,
        )
        assertEquals(PlanAccessMethod.TableScan, plan.relations.first { it.alias == "t1" }.method)
        assertEquals(25_000, plan.joins.single().estimatedOutputRows)
        assertEquals(PlanOperationKind.Sort, plan.blockingOperations.single().kind)
        assertEquals(42.5, plan.rawOptimizerCost)
    }

    @Test
    fun mysqlJsonNormalizesAccessAndNestedLoopOutput() {
        val plan =
            parseMySqlPlan(
                """{"query_block":{"query_cost":"9.5","ordering_operation":{"rows_produced_per_join":50000,"nested_loop":[{"table":{"table_name":"orders","table_alias":"t0","access_type":"ref","rows_examined_per_scan":8}},{"table":{"table_name":"items","table_alias":"t1","access_type":"ALL","rows_examined_per_scan":120000}}]}}}"""
            )

        assertNotNull(plan)
        assertEquals(
            PlanAccessMethod.BoundedLookup,
            plan.relations.first { it.alias == "t0" }.method,
        )
        assertEquals(PlanAccessMethod.TableScan, plan.relations.first { it.alias == "t1" }.method)
        assertTrue(plan.joins.any { it.aliases == setOf("t0", "t1") })
        assertTrue(plan.blockingOperations.any { it.kind == PlanOperationKind.Sort })
        assertEquals(9.5, plan.rawOptimizerCost)
    }

    @Test
    fun mysqlJoinOutputFallsBackToTheLastNestedLoopTablesCumulativeRows() {
        val plan =
            parseMySqlPlan(
                """{"query_block":{"query_cost":"9.5","nested_loop":[{"table":{"table_name":"orders","table_alias":"t0","access_type":"ref","rows_examined_per_scan":8,"rows_produced_per_join":8}},{"table":{"table_name":"items","table_alias":"t1","access_type":"ALL","rows_examined_per_scan":200,"rows_produced_per_join":2000000}}]}}"""
            )

        assertNotNull(plan)
        // The last table's cumulative output is the join output, not the max per-scan value.
        assertEquals(
            2_000_000,
            plan.joins.single { it.aliases == setOf("t0", "t1") }.estimatedOutputRows,
        )
        assertEquals(200, plan.relations.first { it.alias == "t1" }.estimatedRows)
    }

    @Test
    fun mysql9JsonSchemaNormalizesInputsAndIndexAccessType() {
        val plan =
            parseMySqlPlan(
                """{"query_plan":{"inputs":[{"operation":"Index range scan on t0 using customer_idx","table_name":"customers","schema_name":"app","access_type":"index","index_access_type":"index_range_scan","estimated_rows":17.0,"estimated_total_cost":3.6}],"operation":"Filter","access_type":"filter","estimated_rows":17.0,"estimated_total_cost":3.6},"json_schema_version":"2.0"}"""
            )

        assertNotNull(plan)
        val access = plan.relations.single()
        assertEquals("t0", access.alias)
        assertEquals("customers", access.table)
        assertEquals(PlanAccessMethod.BoundedRange, access.method)
        assertEquals(17, access.estimatedRows)
    }

    @Test
    fun sqlServerXmlIsParsedWithoutExternalEntitySupport() {
        val plan =
            parseSqlServerPlan(
                """<ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan"><BatchSequence><Batch><Statements><StmtSimple StatementSubTreeCost="12.5"><QueryPlan><RelOp PhysicalOp="Sort" LogicalOp="Sort" EstimateRows="25000"><RelOp PhysicalOp="Index Seek" LogicalOp="Index Seek" EstimateRows="5"><IndexScan><Object Schema="[dbo]" Table="[orders]" Alias="[t0]"/><SeekPredicates><SeekPredicateNew><SeekKeys><Prefix ScanType="EQ"/></SeekKeys></SeekPredicateNew></SeekPredicates></IndexScan></RelOp></RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>"""
            )

        assertNotNull(plan)
        assertEquals(PlanAccessMethod.BoundedLookup, plan.relations.single().method)
        assertEquals("orders", plan.relations.single().table)
        assertTrue(plan.blockingOperations.any { it.kind == PlanOperationKind.Sort })
        assertEquals(12.5, plan.rawOptimizerCost)
    }

    @Test
    fun sqlServerRebindsScaleInnerLoopRowEstimates() {
        val plan =
            parseSqlServerPlan(
                """<ShowPlanXML xmlns="http://schemas.microsoft.com/sqlserver/2004/07/showplan"><BatchSequence><Batch><Statements><StmtSimple StatementSubTreeCost="12.5"><QueryPlan><RelOp PhysicalOp="Index Seek" LogicalOp="Index Seek" EstimateRows="50" EstimateRebinds="999" EstimateRewinds="0"><IndexScan><Object Schema="[dbo]" Table="[items]" Alias="[t1]"/><SeekPredicates><SeekPredicateNew><SeekKeys><Prefix ScanType="EQ"/></SeekKeys></SeekPredicateNew></SeekPredicates></IndexScan></RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>"""
            )

        assertNotNull(plan)
        assertEquals(50_000, plan.relations.single().estimatedRows)
    }

    @Test
    fun oraclePlanTableRowsNormalizeIndexJoinAndSortEvidence() {
        val plan =
            normalizeOraclePlan(
                listOf(
                    OraclePlanRow(0, null, "SELECT STATEMENT", "", null, null, null, 50_000, 18.0),
                    OraclePlanRow(1, 0, "SORT", "ORDER BY", null, null, null, 50_000, 18.0),
                    OraclePlanRow(2, 1, "NESTED LOOPS", "", null, null, null, 50_000, 17.0),
                    OraclePlanRow(
                        3,
                        2,
                        "TABLE ACCESS",
                        "BY INDEX ROWID",
                        "APP",
                        "ORDERS",
                        "t0",
                        10,
                        4.0,
                    ),
                    OraclePlanRow(4, 3, "INDEX", "UNIQUE SCAN", "APP", "ORDERS_PK", null, 1, 1.0),
                    OraclePlanRow(
                        5,
                        2,
                        "TABLE ACCESS",
                        "FULL",
                        "APP",
                        "ITEMS",
                        "t1",
                        120_000,
                        12.0,
                    ),
                )
            )

        assertNotNull(plan)
        assertEquals(
            PlanAccessMethod.BoundedLookup,
            plan.relations.first { it.alias == "t0" }.method,
        )
        assertEquals("ORDERS", plan.relations.first { it.alias == "t0" }.table)
        assertEquals(PlanAccessMethod.TableScan, plan.relations.first { it.alias == "t1" }.method)
        assertEquals(setOf("t0", "t1"), plan.joins.single().aliases)
        assertEquals(PlanOperationKind.Sort, plan.blockingOperations.single().kind)
    }

    @Test
    fun malformedAndUnknownShapesRemainUnavailableToCallers() {
        assertNull(parsePostgresPlan("not json"))
        assertNull(parseMySqlPlan("{}"))
        assertNull(
            parseSqlServerPlan(
                "<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><x>&ext;</x>"
            )
        )
        assertNull(parseDb2LuwExplain(emptyList()))
        assertNull(parseDb2ZosPlan(emptyList()))
    }

    @Test
    fun db2LuwExplainNormalizesIndexJoinAndSortEvidence() {
        val plan =
            parseDb2LuwExplain(
                listOf(
                    Db2LuwExplainRow(1, null, "RETURN", null, null, null, "", null, 42.0),
                    Db2LuwExplainRow(2, 1, "NLJOIN", null, null, null, "", 50_000, 41.0),
                    Db2LuwExplainRow(3, 2, "FETCH", "APP", "ORDERS", "TA", "", 10, 4.0),
                    Db2LuwExplainRow(
                        4,
                        3,
                        "IXSCAN",
                        "APP",
                        "ORDERS_PK",
                        "IX",
                        "Q1.ID = 1",
                        1,
                        1.0,
                    ),
                    Db2LuwExplainRow(
                        5,
                        2,
                        "TBSCAN",
                        "APP",
                        "ITEMS",
                        "TA",
                        "",
                        120_000,
                        12.0,
                    ),
                    Db2LuwExplainRow(6, 1, "SORT", null, null, null, "", 50_000, 42.0),
                ),
                statementCost = 42.0,
            )

        assertNotNull(plan)
        // The FETCH row is index-fed, so only the IXSCAN carries the ORDERS access.
        assertEquals(
            PlanAccessMethod.BoundedLookup,
            plan.relations.first { it.table == "ORDERS" }.method,
        )
        assertEquals(1, plan.relations.count { it.table == "ORDERS" })
        assertEquals(
            PlanAccessMethod.TableScan,
            plan.relations.first { it.table == "ITEMS" }.method,
        )
        assertEquals(setOf("ORDERS", "ITEMS"), plan.joins.single().aliases)
        assertEquals(PlanOperationKind.Sort, plan.blockingOperations.single().kind)
        assertEquals(42.0, plan.rawOptimizerCost)
    }

    @Test
    fun db2LuwExplainIndexScanDoesNotUseSiblingFetchTable() {
        val plan =
            parseDb2LuwExplain(
                listOf(
                    Db2LuwExplainRow(1, null, "RETURN", null, null, null, "", null, null),
                    Db2LuwExplainRow(2, 1, "NLJOIN", null, null, null, "", null, null),
                    Db2LuwExplainRow(
                        3,
                        2,
                        "IXSCAN",
                        "APP",
                        "ORDERS_PK",
                        "IX",
                        "Q1.ID = 1",
                        null,
                        null,
                    ),
                    Db2LuwExplainRow(4, 2, "FETCH", "APP", "ITEMS", "TA", "", null, null),
                )
            )

        assertNotNull(plan)
        assertTrue(
            plan.relations.none {
                it.table == "ITEMS" && it.method == PlanAccessMethod.BoundedLookup
            }
        )
        assertEquals(
            PlanAccessMethod.BoundedLookup,
            plan.relations.first { it.table == "ORDERS_PK" }.method,
        )
    }

    @Test
    fun db2LuwIxscanWithoutEqualityFallsBackToRangeOrFullScan() {
        val plan =
            parseDb2LuwExplain(
                listOf(
                    Db2LuwExplainRow(1, null, "RETURN", null, null, null, "", null, null),
                    Db2LuwExplainRow(
                        2,
                        1,
                        "IXSCAN",
                        "APP",
                        "ORDERS",
                        "IX",
                        "Q1.AMOUNT > 100",
                        800,
                        3.0,
                    ),
                    Db2LuwExplainRow(3, 1, "IXSCAN", "APP", "ITEMS", "IX", "", 50_000, 9.0),
                    Db2LuwExplainRow(
                        4,
                        1,
                        "IXSCAN",
                        "APP",
                        "RANGE_GE",
                        "IX",
                        "Q1.ORDER_DATE >= ?",
                        10,
                        1.0,
                    ),
                    Db2LuwExplainRow(
                        5,
                        1,
                        "IXSCAN",
                        "APP",
                        "RANGE_LE",
                        "IX",
                        "Q1.ORDER_DATE <= ?",
                        10,
                        1.0,
                    ),
                    Db2LuwExplainRow(
                        6,
                        1,
                        "IXSCAN",
                        "APP",
                        "MIXED",
                        "IX",
                        "Q1.ID = 1 AND Q1.ORDER_DATE >= ?",
                        10,
                        1.0,
                    ),
                )
            )

        assertNotNull(plan)
        assertEquals(
            PlanAccessMethod.BoundedRange,
            plan.relations.first { it.table == "ORDERS" }.method,
        )
        assertEquals(
            PlanAccessMethod.FullIndexScan,
            plan.relations.first { it.table == "ITEMS" }.method,
        )
        assertEquals(
            PlanAccessMethod.BoundedRange,
            plan.relations.first { it.table == "RANGE_GE" }.method,
        )
        assertEquals(
            PlanAccessMethod.BoundedRange,
            plan.relations.first { it.table == "RANGE_LE" }.method,
        )
        assertEquals(
            PlanAccessMethod.BoundedRange,
            plan.relations.first { it.table == "MIXED" }.method,
        )
    }

    @Test
    fun db2ZosPlanNormalizesAccessJoinAndSortEvidence() {
        val plan =
            parseDb2ZosPlan(
                listOf(
                    Db2ZosPlanRow(7001, 1, 1, 0, 0, "APP", "ORDERS", "I", 2, "APP", "ORDERS_PK"),
                    Db2ZosPlanRow(
                        7001,
                        1,
                        2,
                        0,
                        1,
                        "APP",
                        "ITEMS",
                        "R",
                        0,
                        null,
                        null,
                        sortOrderBy = true,
                    ),
                    Db2ZosPlanRow(7001, 1, 3, 0, 1, "APP", "AUDIT", "N", 0, "APP", "AUDIT_IX"),
                ),
                statementCost = 18.0,
            )

        assertNotNull(plan)
        assertEquals(
            PlanAccessMethod.BoundedRange,
            plan.relations.first { it.table == "ORDERS" }.method,
        )
        assertEquals(
            PlanAccessMethod.TableScan,
            plan.relations.first { it.table == "ITEMS" }.method,
        )
        assertEquals(
            PlanAccessMethod.FullIndexScan,
            plan.relations.first { it.table == "AUDIT" }.method,
        )
        assertTrue(plan.joins.single().aliases == setOf("ORDERS", "ITEMS", "AUDIT"))
        assertTrue(plan.blockingOperations.any { it.kind == PlanOperationKind.Sort })
        assertEquals(18.0, plan.rawOptimizerCost)
    }
}
