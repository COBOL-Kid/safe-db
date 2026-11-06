package com.safedb.adapter

import com.safedb.model.PlanUnavailableReason
import java.net.ConnectException
import java.net.SocketException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun plannerBugsStayExecutionFailures() {
        val unavailable = unavailablePlanFromError(IllegalStateException("planner offline"))

        assertEquals(PlanUnavailableReason.ExecutionFailure, unavailable.reasonCode)
        assertEquals("planner offline", unavailable.message)
    }
}
