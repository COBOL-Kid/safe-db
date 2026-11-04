package com.safedb.adapter

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.zaxxer.hikari.pool.HikariPool
import java.net.ConnectException
import java.net.UnknownHostException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConnectionErrorsTest {
    @Test
    fun passwordFailuresMapToAPlainAuthenticationMessage() {
        val cause =
            SQLException("FATAL: password authentication failed for user \"app\"", "28P01", 0)
        val hikari = HikariPool.PoolInitializationException(cause)

        assertEquals(
            "Authentication failed for user app",
            formatConnectionFailure(hikari, sampleConnection(username = "app")),
        )
        assertFalse(formatConnectionFailure(hikari, sampleConnection()).contains("initialize pool"))
    }

    @Test
    fun mysqlAccessDeniedMapsToAuthenticationFailed() {
        val error = SQLException("Access denied for user 'reports'@'localhost'", "28000", 1045)
        assertEquals(
            "Authentication failed for user reports",
            formatConnectionFailure(error, sampleConnection(username = "reports")),
        )
    }

    @Test
    fun unknownHostNamesTheHostAndPort() {
        val hikari =
            HikariPool.PoolInitializationException(UnknownHostException("db.internal.example"))

        assertEquals(
            "Could not reach db.internal.example:3306",
            formatConnectionFailure(
                hikari,
                sampleConnection(host = "db.internal.example", port = 3306),
            ),
        )
    }

    @Test
    fun connectionRefusedNamesTheHostAndPort() {
        val hikari = HikariPool.PoolInitializationException(ConnectException("Connection refused"))

        assertEquals(
            "Could not reach 10.0.0.8:5432",
            formatConnectionFailure(hikari, sampleConnection(host = "10.0.0.8", port = 5432)),
        )
    }

    @Test
    fun genericFailuresStillAvoidDriverPoolWording() {
        val hikari = HikariPool.PoolInitializationException(SQLException("Something exploded"))

        val message =
            formatConnectionFailure(hikari, sampleConnection(host = "db.example", port = 1521))
        assertEquals("Could not connect to db.example:1521", message)
        assertFalse(message.contains("initialize pool"))
    }

    private fun sampleConnection(
        host: String = "localhost",
        port: Int = 5432,
        username: String = "app",
    ) =
        ConnectionDef(
            id = "c1",
            name = "Demo",
            dialect = Dialect.Postgres,
            host = host,
            port = port,
            database = "app",
            username = username,
        )
}
