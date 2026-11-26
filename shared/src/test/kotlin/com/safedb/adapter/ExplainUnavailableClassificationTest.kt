package com.safedb.adapter

import com.safedb.model.PlanUnavailableReason
import java.net.ConnectException
import java.net.SocketException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplainUnavailableClassificationTest {
    @Test
    fun ioDumpIsAConnectionFailureAfterTheFirstSentence() {
        val dump =
            "An I/O error occurred. Communications link failure\n\n" +
                "The last packet sent successfully to the server was 0 milliseconds ago. " +
                "The last packet received successfully from the server was 0 milliseconds ago. " +
                "Caused by: java.net.SocketException: Connection reset. " +
                "Driver dump: " +
                "x".repeat(800)

        val unavailable = unavailablePlanFromError(SQLException(dump))

        assertEquals(PlanUnavailableReason.ConnectionFailure, unavailable.reasonCode)
        assertEquals("An I/O error occurred.", unavailable.message)
    }

    @Test
    fun nestedSocketFailureIsClassifiedBeforeTruncation() {
        val error =
            SQLException("Error", SQLException("wrapper", SocketException("Connection reset")))

        val unavailable = unavailablePlanFromError(error)

        assertEquals(PlanUnavailableReason.ConnectionFailure, unavailable.reasonCode)
        assertEquals("Error", unavailable.message)
    }

    @Test
    fun driverTimeoutsAreConnectionFailuresNotExecutionFailures() {
        val unavailable = unavailablePlanFromError(SQLTimeoutException("statement timed out"))

        assertEquals(PlanUnavailableReason.ConnectionFailure, unavailable.reasonCode)
        assertEquals("statement timed out", unavailable.message)
    }

    @Test
    fun connectExceptionsAreConnectionFailures() {
        val unavailable = unavailablePlanFromError(ConnectException("Connection refused"))

        assertEquals(PlanUnavailableReason.ConnectionFailure, unavailable.reasonCode)
        assertEquals("Connection refused", unavailable.message)
    }

    @Test
    fun queryTimedOutExceptionIsAConnectionFailure() {
        val unavailable = unavailablePlanFromError(QueryTimedOutException(10_000))

        assertEquals(PlanUnavailableReason.ConnectionFailure, unavailable.reasonCode)
        assertEquals("Query timed out after 10000ms", unavailable.message)
    }

    @Test
    fun permissionErrorsStayPermissionDenied() {
        val unavailable =
            unavailablePlanFromError(SQLException("permission denied for table public.users"))

        assertEquals(PlanUnavailableReason.PermissionDenied, unavailable.reasonCode)
        assertEquals("permission denied for table public.users", unavailable.message)
    }

    @Test
    fun mysqlMaxExecutionTimeErrorsAreClassifiedAsTimedOut() {
        val byCode =
            unavailablePlanFromError(
                SQLException("Query execution was interrupted", "HY000", 3024),
            )
        assertEquals(PlanUnavailableReason.TimedOut, byCode.reasonCode)

        val byMessage =
            unavailablePlanFromError(
                SQLException(
                    "Query execution was interrupted, maximum statement execution time exceeded",
                ),
            )
        assertEquals(PlanUnavailableReason.TimedOut, byMessage.reasonCode)
    }

    @Test
    fun postgresStatementTimeoutErrorsAreClassifiedAsTimedOut() {
        val bySqlState = unavailablePlanFromError(SQLException("canceling query", "57014", 0))
        assertEquals(PlanUnavailableReason.TimedOut, bySqlState.reasonCode)

        val byMessage =
            unavailablePlanFromError(
                SQLException(
                    "wrapper",
                    SQLException("ERROR: canceling statement due to statement timeout"),
                ),
            )
        assertEquals(PlanUnavailableReason.TimedOut, byMessage.reasonCode)
    }

    @Test
    fun plannerBugsStayExecutionFailures() {
        val unavailable = unavailablePlanFromError(IllegalStateException("planner offline"))

        assertEquals(PlanUnavailableReason.ExecutionFailure, unavailable.reasonCode)
        assertEquals("planner offline", unavailable.message)
    }

    @Test
    fun executionTimeoutErrorsFormatFriendlySettingsGuidance() {
        val mysqlTimeout =
            SQLException(
                "Query execution was interrupted, maximum statement execution time exceeded",
                "HY000",
                3024,
            )
        val formatted = formatQueryExecutionError(mysqlTimeout, 1_000)
        assertTrue(formatted.contains("timed out after 1s"))
        assertTrue(formatted.contains("Settings"))

        val nonTimeout = formatQueryExecutionError(IllegalStateException("syntax error"), 10_000)
        assertEquals("syntax error", nonTimeout)
    }

    @Test
    fun errorCode1013RequiresOracleContextForTimeoutClassification() {
        val mssql1013 =
            SQLException(
                "The objects 'a' and 'a' in the FROM clause have the same exposed names.",
                "S0001",
                1013,
            )
        assertEquals(mssql1013.message, formatQueryExecutionError(mssql1013, 10_000))
        assertEquals(
            PlanUnavailableReason.ExecutionFailure,
            unavailablePlanFromError(mssql1013).reasonCode,
        )

        val mysql1013 = SQLException("Can't get stat of 'foo'", "HY000", 1013)
        assertEquals(mysql1013.message, formatQueryExecutionError(mysql1013, 10_000))
        assertEquals(
            PlanUnavailableReason.ExecutionFailure,
            unavailablePlanFromError(mysql1013).reasonCode,
        )

        val oracleByMessage = SQLException("ORA-01013: user requested cancel of current operation")
        assertTrue(formatQueryExecutionError(oracleByMessage, 5_000).contains("timed out after 5s"))
        assertEquals(
            PlanUnavailableReason.TimedOut,
            unavailablePlanFromError(oracleByMessage).reasonCode,
        )

        val oracleByState = SQLException("user requested cancel", "72000", 1013)
        assertTrue(formatQueryExecutionError(oracleByState, 5_000).contains("timed out after 5s"))
        assertEquals(
            PlanUnavailableReason.TimedOut,
            unavailablePlanFromError(oracleByState).reasonCode,
        )
    }
}
