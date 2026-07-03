package com.safedb.testsupport

import com.safedb.adapter.Adapter
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.net.Socket

object IntegrationAssumptions {
    private val env: Map<String, String> = System.getenv()

    val mysqlHost: String get() = env["SAFEDB_TEST_MYSQL_HOST"] ?: "localhost"
    val mysqlPort: Int get() = env["SAFEDB_TEST_MYSQL_PORT"]?.toIntOrNull() ?: 3306
    val mysqlUser: String get() = env["SAFEDB_TEST_MYSQL_USER"] ?: "root"
    val mysqlPassword: String get() = env["SAFEDB_TEST_MYSQL_PASSWORD"] ?: ""
    val mysqlDatabase: String get() = env["SAFEDB_TEST_MYSQL_DATABASE"] ?: "safedb_test"

    fun assumeMysqlAvailable() {
        assumeTrue(
            isMysqlReachable(),
            "Seeded MySQL fixture not reachable at $mysqlHost:$mysqlPort; " +
                "set SAFEDB_TEST_MYSQL_* or run scripts/seed_mysql.sh --static",
        )
    }

    fun isMysqlReachable(): Boolean = runBlocking {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(mysqlHost, mysqlPort), 3_000)
            }
            val def = mysqlConnectionDef()
            val adapter = Adapter.connect(def, mysqlPassword)
            try {
                adapter.test()
                isCi() || hasSeededSchema(adapter)
            } finally {
                adapter.close()
            }
        }.getOrDefault(false)
    }

    private fun isCi(): Boolean = env["CI"].equals("true", ignoreCase = true)

    private suspend fun hasSeededSchema(adapter: Adapter): Boolean {
        val schema = Adapter.introspectWithTimeout(adapter)
        val tableNames = schema.tables.map { it.name }.toSet()
        return "customers" in tableNames && "orders" in tableNames
    }

    fun mysqlConnectionDef(
        id: String = "integration-mysql",
        name: String = "Integration MySQL",
    ): ConnectionDef = ConnectionDef(
        id = id,
        name = name,
        dialect = Dialect.MySql,
        host = mysqlHost,
        port = mysqlPort,
        database = mysqlDatabase,
        username = mysqlUser,
        transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
    )
}
