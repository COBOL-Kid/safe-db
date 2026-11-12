package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer

internal class IssuedTokens {
    private val tokens = ConcurrentHashMap<String, String>()

    fun issue(id: String): String {
        val token = UUID.randomUUID().toString()
        tokens[id] = token
        return token
    }

    fun matches(id: String, token: String): Boolean = tokens[id] == token

    fun consume(id: String) {
        tokens.remove(id)
    }
}

internal suspend fun <C> runDestructiveConfirmedTool(
    request: CallToolRequest,
    tokens: IssuedTokens,
    targetId: String,
    serializer: KSerializer<C>,
    confirmedId: (C) -> String,
    tokenOf: (C) -> String,
    confirmationRequired: (token: String) -> CallToolResult,
    failureMessage: String,
    afterConfirmed: suspend () -> Unit = {},
    action: suspend () -> CallToolResult,
): CallToolResult {
    val confirmation =
        parseDestructiveConfirmation(request, serializer).getOrElse {
            return it
        }
    if (
        confirmation == null ||
            confirmedId(confirmation) != targetId ||
            !tokens.matches(targetId, tokenOf(confirmation))
    ) {
        return confirmationRequired(tokens.issue(targetId))
    }
    return runMcpTool(failureMessage) {
        val result = action()
        tokens.consume(targetId)
        afterConfirmed()
        result
    }
}

private fun <T> parseDestructiveConfirmation(
    request: CallToolRequest,
    deserializer: DeserializationStrategy<T>,
): Arg<T?> {
    val obj =
        optionalObject(request, "confirmation").getOrElse {
            return Arg.Err(it)
        } ?: return Arg.Ok(null)
    return try {
        Arg.Ok(toolJson.decodeFromJsonElement(deserializer, obj))
    } catch (_: IllegalArgumentException) {
        Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "confirmation is invalid"))
    }
}
