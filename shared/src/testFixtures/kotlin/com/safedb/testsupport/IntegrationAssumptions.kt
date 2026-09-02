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

    val mssqlHost: String
        get() = env["SAFEDB_TEST_MSSQL_HOST"] ?: "localhost"

    val mssqlPort: Int
        get() = env["SAFEDB_TEST_MSSQL_PORT"]?.toIntOrNull() ?: 14333

    val mssqlUser: String
        get() = env["SAFEDB_TEST_MSSQL_USER"] ?: "sa"

    val mssqlPassword: String
        get() = env["SAFEDB_TEST_MSSQL_PASSWORD"] ?: "SafeDb_Ssl_Passw0rd!"

    val mssqlDatabase: String
        get() = env["SAFEDB_TEST_MSSQL_DATABASE"] ?: "safedb_ssl"

    val oracleHost: String
        get() = env["SAFEDB_TEST_ORACLE_HOST"] ?: "127.0.0.1"

    val oraclePort: Int
        get() = env["SAFEDB_TEST_ORACLE_PORT"]?.toIntOrNull() ?: 1522

    val oracleUser: String
        get() = env["SAFEDB_TEST_ORACLE_USER"] ?: "safedb"

    val oraclePassword: String
        get() = env["SAFEDB_TEST_ORACLE_PASSWORD"] ?: "safedb"

    val oracleDatabase: String
        get() = env["SAFEDB_TEST_ORACLE_DATABASE"] ?: "FREEPDB1"

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

    fun assumeMssqlAvailable() {
        requireOrAssume(
            available = isMssqlReachable(),
            required = env["SAFEDB_TEST_REQUIRE_MSSQL"].equals("true", ignoreCase = true),
            message =
                "Seeded SQL Server fixture not reachable at $mssqlHost:$mssqlPort; " +
                    "set SAFEDB_TEST_MSSQL_* or run scripts/docker_test_databases.sh up",
        )
    }

    fun assumeOracleAvailable() {
        requireOrAssume(
            available = isOracleReachable(),
            required = env["SAFEDB_TEST_REQUIRE_ORACLE"].equals("true", ignoreCase = true),
            message =
                "Seeded Oracle fixture not reachable at $oracleHost:$oraclePort; " +
                    "set SAFEDB_TEST_ORACLE_* or run scripts/docker_test_databases.sh up",
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

    fun isMssqlReachable(): Boolean = runBlocking {
        isSeededEndpointReachable(
            mssqlHost,
            mssqlPort,
            mssqlConnectionDef(),
            mssqlPassword,
            "dbo",
            "customers",
            "orders",
        )
    }

    fun isOracleReachable(): Boolean = runBlocking {
        isSeededEndpointReachable(
            oracleHost,
            oraclePort,
            oracleConnectionDef(),
            oraclePassword,
            "SAFEDB",
            "CUSTOMERS",
            "ORDERS",
        )
    }

    private suspend fun isSeededEndpointReachable(
        host: String,
        port: Int,
        def: ConnectionDef,
        password: String,
        expectedSchema: String,
        customersTable: String,
        ordersTable: String,
    ): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 3_000) }
        val adapter = Adapter.connect(def, password)
        try {
            adapter.test()
            val schema = Adapter.introspectWithTimeout(adapter)
            val tables =
                schema.tables.filter { it.schema == expectedSchema }.map { it.name }.toSet()
            customersTable in tables && ordersTable in tables
        } finally {
            adapter.close()
        }
    }
        .getOrDefault(false)

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

    fun mssqlConnectionDef(
        id: String = "integration-mssql",
        name: String = "Integration SQL Server",
    ): ConnectionDef =
        ConnectionDef(
            id = id,
            name = name,
            dialect = Dialect.Mssql,
            host = mssqlHost,
            port = mssqlPort,
            database = mssqlDatabase,
            username = mssqlUser,
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
        )

    fun oracleConnectionDef(
        id: String = "integration-oracle",
        name: String = "Integration Oracle",
    ): ConnectionDef =
        ConnectionDef(
            id = id,
            name = name,
            dialect = Dialect.Oracle,
            host = oracleHost,
            port = oraclePort,
            database = oracleDatabase,
            username = oracleUser,
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
        )
}
