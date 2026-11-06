package com.safedb.adapter

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

class ConnectTimeoutTest {
    @Test
    @Timeout(30)
    fun connectFailsWithinDeadlineWhenTheHostAcceptsTcpAndNeverAnswers() = runBlocking {
        ServerSocket(0).use { listener ->
            val port = listener.localPort
            val acceptor =
                Thread(
                    {
                        try {
                            listener.accept().use { Thread.sleep(60_000) }
                        } catch (_: Exception) {
                            // Closed when the test finishes.
                        }
                    },
                    "silent-listener",
                )
            acceptor.isDaemon = true
            acceptor.start()
            val started = System.nanoTime()
            val error =
                assertFailsWith<ConnectionFailedException> {
                    Adapter.connect(
                        ConnectionDef(
                            id = "silent",
                            name = "Silent listener",
                            dialect = Dialect.Postgres,
                            host = "127.0.0.1",
                            port = port,
                            database = "safedb_test",
                            username = "safedb",
                            transportSecurity =
                                TransportSecurity(mode = TransportSecurityMode.Disabled),
                        ),
                        "unused",
                    )
                }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                elapsedMs in 5_000..20_000,
                "connect should honor CONNECT_TIMEOUT_MS; elapsed ${elapsedMs}ms",
            )
            assertTrue(
                error.message!!.startsWith("Could not connect to 127.0.0.1:$port"),
                error.message,
            )
        }
    }

    @Test
    fun handshakeTimeoutsStayAtConnectBudgetAndReadTimeoutsFollowIntrospection() {
        val connectSeconds = (CONNECT_TIMEOUT_MS / 1000L).toString()
        val connectMillis = CONNECT_TIMEOUT_MS.toString()
        val readSeconds = (INTROSPECTION_TIMEOUT_MS / 1000L).toString()
        val readMillis = INTROSPECTION_TIMEOUT_MS.toString()

        val postgres =
            createDataSourceConfig(
                    sampleConnection(Dialect.Postgres, 5432)
                        .copy(
                            driverProperties =
                                listOf(
                                    DriverProperty("connectTimeout", "0"),
                                    DriverProperty("socketTimeout", "0"),
                                )
                        ),
                    "pw",
                )
                .dataSourceProperties
        assertEquals(connectSeconds, postgres.getProperty("connectTimeout"))
        assertEquals(connectSeconds, postgres.getProperty("loginTimeout"))
        assertEquals(readSeconds, postgres.getProperty("socketTimeout"))

        val mysql =
            createDataSourceConfig(sampleConnection(Dialect.MySql, 3306), "pw").dataSourceProperties
        assertEquals(connectMillis, mysql.getProperty("connectTimeout"))
        assertEquals(readMillis, mysql.getProperty("socketTimeout"))

        val mssql =
            createDataSourceConfig(sampleConnection(Dialect.Mssql, 1433), "pw").dataSourceProperties
        assertEquals(connectSeconds, mssql.getProperty("loginTimeout"))
        assertEquals(readMillis, mssql.getProperty("socketTimeout"))

        val oracle =
            createDataSourceConfig(sampleConnection(Dialect.Oracle, 1521), "pw")
                .dataSourceProperties
        assertEquals(connectMillis, oracle.getProperty("oracle.net.CONNECT_TIMEOUT"))
        assertEquals(readMillis, oracle.getProperty("oracle.jdbc.ReadTimeout"))
        assertEquals(readMillis, oracle.getProperty("oracle.net.READ_TIMEOUT"))

        val db2 =
            createDataSourceConfig(sampleConnection(Dialect.Db2, 50000), "pw").dataSourceProperties
        assertEquals(connectSeconds, db2.getProperty("loginTimeout"))
        assertEquals(readSeconds, db2.getProperty("blockingReadConnectionTimeout"))
    }

    private fun sampleConnection(dialect: Dialect, port: Int) =
        ConnectionDef(
            id = dialect.name.lowercase(),
            name = dialect.name,
            dialect = dialect,
            host = "127.0.0.1",
            port = port,
            database = "app",
            username = "app",
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
        )
}
