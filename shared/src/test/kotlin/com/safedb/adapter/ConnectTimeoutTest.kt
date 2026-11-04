package com.safedb.adapter

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.net.ServerSocket
import kotlin.test.Test
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
                error.message!!.startsWith("Could not reach 127.0.0.1:$port") ||
                    error.message!!.startsWith("Could not connect to 127.0.0.1:$port"),
                error.message,
            )
        }
    }
}
