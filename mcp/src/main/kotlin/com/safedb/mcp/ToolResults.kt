package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.jsonObject

// The text receipt and structuredContent are the same JSON object.
internal inline fun <reified T> objectToolResult(value: T): CallToolResult {
    val text = toolJson.encodeToString(value)
    return CallToolResult(
        content = listOf(TextContent(text = text)),
        structuredContent = toolJson.parseToJsonElement(text).jsonObject,
    )
}
