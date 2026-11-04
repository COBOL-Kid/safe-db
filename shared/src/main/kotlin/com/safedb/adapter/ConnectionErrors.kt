package com.safedb.adapter

import com.safedb.model.ConnectionDef
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import kotlinx.coroutines.TimeoutCancellationException

fun formatConnectionFailure(error: Throwable, def: ConnectionDef): String {
    val chain = generateSequence(error) { it.cause }.toList()
    val texts = chain.mapNotNull { it.message }.joinToString("\n")
    return when {
        isAuthenticationFailure(chain, texts) -> "Authentication failed for user ${def.username}"
        isUnreachableHost(chain, texts) -> "Could not reach ${def.host}:${def.port}"
        else -> "Could not connect to ${def.host}:${def.port}"
    }
}

private fun isAuthenticationFailure(chain: List<Throwable>, texts: String): Boolean {
    for (error in chain) {
        val sqlError = error as? SQLException ?: continue
        val state = sqlError.sqlState?.uppercase().orEmpty()
        if (state.startsWith("28")) return true
        if (sqlError.errorCode in AUTH_SQL_ERROR_CODES) return true
    }
    val lower = texts.lowercase()
    return AUTH_MESSAGE_FRAGMENTS.any { it in lower }
}

private fun isUnreachableHost(chain: List<Throwable>, texts: String): Boolean {
    if (
        chain.any { error ->
            error is UnknownHostException ||
                error is ConnectException ||
                error is NoRouteToHostException ||
                error is PortUnreachableException ||
                error is SocketTimeoutException ||
                error is SQLTimeoutException ||
                error is SQLTransientConnectionException ||
                error is TimeoutCancellationException
        }
    ) {
        return true
    }
    val lower = texts.lowercase()
    return UNREACHABLE_MESSAGE_FRAGMENTS.any { it in lower }
}

private val AUTH_SQL_ERROR_CODES = setOf(1045, 18456, 1017, 1005, 28000)

private val AUTH_MESSAGE_FRAGMENTS =
    listOf(
        "password authentication failed",
        "access denied for user",
        "login failed for user",
        "authentication failed",
        "invalid authorization",
        "invalid username/password",
        "ora-01017",
        "ora-01005",
        "sql30082",
    )

private val UNREACHABLE_MESSAGE_FRAGMENTS =
    listOf(
        "connection refused",
        "unknown host",
        "unknownhost",
        "no route to host",
        "connection timed out",
        "connect timed out",
        "communications link failure",
        "the connection attempt failed",
        "failed to connect",
        "network is unreachable",
        "host is down",
        "connection reset",
        "timed out after",
    )
