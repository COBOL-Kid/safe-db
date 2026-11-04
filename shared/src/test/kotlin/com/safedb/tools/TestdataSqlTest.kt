package com.safedb.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class TestdataSqlTest {
    @Test
    fun postgresReloadDropsDependentObjectsWithCascade() {
        val sql = testdata("testdata_postgres.sql")
        assertTrue(sql.contains("DROP SCHEMA IF EXISTS ops CASCADE"))
        assertTrue(sql.contains("DROP SCHEMA IF EXISTS analytics CASCADE"))
        assertTrue(
            sql.contains(
                "DROP TABLE IF EXISTS order_items, inventory_log, orders, products, customers, categories CASCADE"
            )
        )
    }

    @Test
    fun wideRowsColBoolIsTrueForEvenIdsOnEveryEngine() {
        val postgres = testdata("testdata_postgres.sql")
        val mysql = testdata("testdata_mysql.sql")
        val mssql = testdata("testdata_mssql.sql")
        val oracle = testdata("testdata_oracle.sql")
        val db2 = testdata("testdata_db2.sql")

        assertTrue(postgres.contains("(g % 2 = 0)"))
        assertTrue(mysql.contains("(n % 2 = 0)"))
        assertTrue(mssql.contains("CASE WHEN n % 2 = 0 THEN 1 ELSE 0 END"))
        assertTrue(oracle.contains("CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END"))
        assertTrue(db2.contains("CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END"))
    }

    private fun testdata(name: String): String {
        var dir = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(name)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("missing $name")
    }
}
