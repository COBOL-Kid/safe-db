package com.safedb.testsupport

import com.safedb.adapter.Adapter
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

object IntegrationAssumptions {
    private val env: Map<String, String> = System.getenv()

    val mysqlHost: String
        get() = env["SAFEDB_TEST_MYSQL_HOST"] ?: "localhost"

    val mysqlPort: Int
        get() = env["SAFEDB_TEST_MYSQL_PORT"]?.toIntOrNull() ?: 3306

    val mysqlUser: String
        get() = env["SAFEDB_TEST_MYSQL_USER"] ?: "root"

    val mysqlPassword: String
        get() = env["SAFEDB_TEST_MYSQL_PASSWORD"] ?: ""

    val mysqlDatabase: String
        get() = env["SAFEDB_TEST_MYSQL_DATABASE"] ?: "safedb_test"

    val postgresHost: String
        get() = env["SAFEDB_TEST_POSTGRES_HOST"] ?: "localhost"

    val postgresPort: Int
        get() = env["SAFEDB_TEST_POSTGRES_PORT"]?.toIntOrNull() ?: 5432

    val postgresUser: String
        get() = env["SAFEDB_TEST_POSTGRES_USER"] ?: "postgres"

    val postgresPassword: String
        get() = env["SAFEDB_TEST_POSTGRES_PASSWORD"] ?: "postgres"

    val postgresDatabase: String
        get() = env["SAFEDB_TEST_POSTGRES_DATABASE"] ?: "safedb_test"

    fun assumeMysqlAvailable() {
        requireOrAssume(
            available = isMysqlReachable(),
            required = env["SAFEDB_TEST_REQUIRE_MYSQL"].equals("true", ignoreCase = true),
            message =
                "Seeded MySQL fixture not reachable at $mysqlHost:$mysqlPort; " +
                    "set SAFEDB_TEST_MYSQL_* or run scripts/seed_mysql.sh --static",
        )
    }

    fun assumePostgresAvailable() {
        requireOrAssume(
            available = isPostgresReachable(),
            required = env["SAFEDB_TEST_REQUIRE_POSTGRES"].equals("true", ignoreCase = true),
            message =
                "Seeded PostgreSQL fixture not reachable at $postgresHost:$postgresPort; " +
                    "set SAFEDB_TEST_POSTGRES_* and load testdata_postgres.sql",
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
                    hasSeededSchema(adapter, mysqlDatabase)
                } finally {
                    adapter.close()
                }
            }
            .getOrDefault(false)
    }

    fun isPostgresReachable(): Boolean = runBlocking {
        runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(postgresHost, postgresPort), 3_000)
                }
                val adapter = Adapter.connect(postgresConnectionDef(), postgresPassword)
                try {
                    adapter.test()
                    hasSeededSchema(adapter, "public")
                } finally {
                    adapter.close()
                }
            }
            .getOrDefault(false)
    }

    private suspend fun hasSeededSchema(adapter: Adapter, expectedSchema: String): Boolean {
        val schema = Adapter.introspectWithTimeout(adapter)
        val tables = schema.tables.filter { it.schema == expectedSchema }.map { it.name }.toSet()
        return "customers" in tables && "orders" in tables
    }

    private fun requireOrAssume(available: Boolean, required: Boolean, message: String) {
        if (required) {
            check(available) { message }
        } else {
            assumeTrue(available, message)
        }
    }

    fun mysqlConnectionDef(
        id: String = "integration-mysql",
        name: String = "Integration MySQL",
    ): ConnectionDef =
        ConnectionDef(
            id = id,
            name = name,
            dialect = Dialect.MySql,
            host = mysqlHost,
            port = mysqlPort,
            database = mysqlDatabase,
            username = mysqlUser,
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
        )

    fun postgresConnectionDef(
        id: String = "integration-postgres",
        name: String = "Integration PostgreSQL",
    ): ConnectionDef =
        ConnectionDef(
            id = id,
            name = name,
            dialect = Dialect.Postgres,
            host = postgresHost,
            port = postgresPort,
            database = postgresDatabase,
            username = postgresUser,
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
        )
}
