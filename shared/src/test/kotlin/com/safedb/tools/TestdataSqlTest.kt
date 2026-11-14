package com.safedb.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestdataSqlTest {
    @Test
    fun postgresReloadDropsDependentObjectsWithCascade() {
        val sql = testdata("testdata_postgres.sql")
        assertTrue(sql.contains("DROP SCHEMA IF EXISTS ops CASCADE"))
        assertTrue(sql.contains("DROP SCHEMA IF EXISTS analytics CASCADE"))
        assertTrue(
            sql.contains(
                "DROP TABLE IF EXISTS order_items, inventory_log, orders, products, customers, categories CASCADE",
            ),
        )
    }

    @Test
    fun db2InitAndSeedShareDropList() {
        val root = repoRoot()
        val drops = db2DropStatements(root)
        assertEquals(
            listOf(
                "DROP VIEW CUSTOMER_ORDER_SUMMARY",
                "DROP TABLE ANALYTICS.EVENT_NOTES",
                "DROP TABLE ANALYTICS.PAYLOAD_ROWS",
                "DROP TABLE ANALYTICS.WIDE_ROWS",
                "DROP TABLE ANALYTICS.SCAN_EVENTS",
                "DROP SCHEMA ANALYTICS RESTRICT",
                "DROP TABLE ORDER_ITEMS",
                "DROP TABLE INVENTORY_LOG",
                "DROP TABLE ORDERS",
                "DROP TABLE PRODUCTS",
                "DROP TABLE CUSTOMERS",
                "DROP TABLE CATEGORIES",
            ),
            drops,
        )
        val init = Files.readString(root.resolve("docker/test-databases/db2-init.sh"))
        assertTrue(init.contains("/fixtures/db2-drop-statements.sql"))
        assertTrue(!init.contains("DROP VIEW CUSTOMER_ORDER_SUMMARY"))
        val seed =
            Files.readString(
                root.resolve("shared/src/tools/kotlin/com/safedb/tools/SeedRelational.kt"),
            )
        assertTrue(seed.contains("db2DropStatements("))
        assertTrue(seed.contains(DB2_DROP_STATEMENTS_RELATIVE))
        assertTrue(!seed.contains("DROP VIEW CUSTOMER_ORDER_SUMMARY"))
        val compose = Files.readString(root.resolve("compose.yaml"))
        assertTrue(compose.contains("db2-drop-statements.sql:/fixtures/db2-drop-statements.sql"))
    }

    @Test
    fun evenIdBooleansMatchAcrossEngines() {
        val postgres = testdata("testdata_postgres.sql")
        val mysql = testdata("testdata_mysql.sql")
        val mssql = testdata("testdata_mssql.sql")
        val oracle = testdata("testdata_oracle.sql")
        val db2 = testdata("testdata_db2.sql")

        assertTrue(insertBlock(postgres, "wide_rows").contains("(g % 2 = 0)"))
        assertTrue(insertBlock(postgres, "scan_events").contains("(g % 2 = 0)"))
        assertTrue(insertBlock(mysql, "wide_rows").contains("(n % 2 = 0)"))
        assertTrue(insertBlock(mysql, "scan_events").contains("(n % 2 = 0)"))
        assertTrue(
            insertBlock(mssql, "wide_rows").contains("CASE WHEN n % 2 = 0 THEN 1 ELSE 0 END"),
        )
        assertTrue(
            insertBlock(mssql, "scan_events").contains("CASE WHEN n % 2 = 0 THEN 1 ELSE 0 END"),
        )
        assertTrue(
            insertBlock(oracle, "wide_rows").contains("CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END"),
        )
        assertTrue(
            insertBlock(oracle, "scan_events")
                .contains("CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END"),
        )
        assertTrue(
            insertBlock(db2, "wide_rows").contains("CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END"),
        )
        assertTrue(
            insertBlock(db2, "scan_events").contains("CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END"),
        )
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun testdata(name: String): String = Files.readString(repoRoot().resolve(name))

    private fun insertBlock(sql: String, table: String): String {
        val marker =
            Regex("""INSERT INTO\s+(?:\w+\.)?${Regex.escape(table)}\b""").find(sql)
                ?: error("missing INSERT for $table")
        val start = marker.range.first
        val nextCreate = sql.indexOf("CREATE TABLE", start + 1)
        val end = if (nextCreate >= 0) nextCreate else sql.length
        return sql.substring(start, end)
    }
}
