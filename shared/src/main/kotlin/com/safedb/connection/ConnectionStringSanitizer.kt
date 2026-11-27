package com.safedb.connection

import com.safedb.model.isSensitiveDriverPropertyName

private fun bestEffortPasswordRegex(prefixPattern: String, stopChars: String): Regex =
    Regex(
        """(?i)($prefixPattern\s*(?:password|pwd)\s*=)(?:[ \t]*\{(?:[^}]|}})*}?[^$stopChars]*|[ \t]*"(?:[^"]|"")*"?[^$stopChars]*|[ \t]*'(?:[^']|'')*'?[^$stopChars]*|[^$stopChars]*)""",
    )

private val BEST_EFFORT_KV_PASSWORD_REGEX =
    bestEffortPasswordRegex(prefixPattern = "(?:^|[;:])", stopChars = ";")

private val BEST_EFFORT_URI_QUERY_PASSWORD_REGEX =
    bestEffortPasswordRegex(prefixPattern = "[?&]", stopChars = "&#")

private val ORACLE_CONNECT_MARKER_REGEX = Regex("@(?://|tcps?:|\\()", RegexOption.IGNORE_CASE)
private val ORACLE_THIN_PREFIX_REGEX = Regex("^jdbc:oracle:thin:", RegexOption.IGNORE_CASE)
private val QUERY_OR_FRAGMENT_CHARS = charArrayOf('?', '#')

private fun scrubBestEffortOracleAuth(raw: String): String {
    val prefix = ORACLE_THIN_PREFIX_REGEX.find(raw)?.value.orEmpty()
    val rest = raw.substring(prefix.length)
    if (rest.startsWith("@")) return raw
    val atIndex = findBestEffortOracleAuthSeparator(rest, hasOraclePrefix = prefix.isNotEmpty())
    if (atIndex <= 0) return raw
    val auth = rest.substring(0, atIndex)
    if (prefix.isEmpty() && auth.any { it == ';' || it == '=' || it.isWhitespace() }) return raw
    val slashIndex = auth.indexOf('/')
    if (slashIndex < 0) return raw
    return prefix + auth.substring(0, slashIndex) + rest.substring(atIndex)
}

private fun findBestEffortOracleAuthSeparator(rest: String, hasOraclePrefix: Boolean): Int {
    if (hasOraclePrefix) {
        // Prefer the last explicit connect marker (`@//`, `@tcps:`, `@(`) so `?`/`#`/`@` inside
        // passwords cannot truncate the credential segment early.
        ORACLE_CONNECT_MARKER_REGEX.findAll(rest).lastOrNull()?.let {
            return it.range.first
        }
    }
    val queryStart =
        rest.indexOfAny(QUERY_OR_FRAGMENT_CHARS).let { if (it < 0) rest.length else it }
    val schemeStart = rest.indexOf("://")
    if (schemeStart in 0 until queryStart && '@' !in rest.substring(0, schemeStart)) {
        return -1
    }
    val authLimit = if (schemeStart in 0 until queryStart) schemeStart else queryStart
    val beforeQueryAt = rest.substring(0, authLimit).lastIndexOf('@')
    if (beforeQueryAt > 0 || !hasOraclePrefix) return beforeQueryAt
    // Fallback for markerless `jdbc:oracle:thin:user/p?ss@host:port/svc`: `?`/`#` in the password
    // precedes `@`, so scan up to the next `?`/`#` after the first `@`.
    val firstAt = rest.indexOf('@')
    if (firstAt <= 0) return -1
    val trailingQuery =
        rest.indexOfAny(QUERY_OR_FRAGMENT_CHARS, startIndex = firstAt + 1).let {
            if (it < 0) rest.length else it
        }
    return rest.substring(0, trailingQuery).lastIndexOf('@')
}

fun sanitizeBestEffortConnectionString(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    var sanitized = scrubBestEffortOracleAuth(trimmed)
    if (!sanitized.startsWith("jdbc:sqlserver://", ignoreCase = true)) {
        findUrlAuthorityAuth(sanitized)?.let { passwordSpan ->
            sanitized = sanitized.removeRange(passwordSpan)
        }
    }
    return sanitized
        .replace(BEST_EFFORT_KV_PASSWORD_REGEX, "$1")
        .replace(BEST_EFFORT_URI_QUERY_PASSWORD_REGEX, "$1")
}

internal fun sanitizeUrlPassword(url: ParsedUrl): String {
    val user = url.rawUserInfo?.substringBefore(':')
    val authorityPrefix = user?.let { "${encodeComponent(decodeComponent(it))}@" }.orEmpty()
    val portPart = if (url.port != -1) ":${url.port}" else ""
    val safeQuery =
        urlQueryPairs(url)
            .filterNot { (name, _) -> isSensitiveDriverPropertyName(name) }
            .joinToString("&") { (name, value) ->
                "${encodeComponent(name)}=${encodeComponent(value)}"
            }
    val queryPart = safeQuery.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
    val refPart = url.rawFragment?.let { "#$it" }.orEmpty()
    return "${url.scheme}://$authorityPrefix${url.host}$portPart${url.rawPath}$queryPart$refPart"
}

internal fun sanitizeSqlServerKeyValue(raw: String): String =
    splitSemicolonRecords(raw).joinToString(";") { record ->
        val eqIndex = record.indexOf('=')
        if (eqIndex < 0) return@joinToString record
        val key = record.substring(0, eqIndex).trim()
        if (isSensitiveDriverPropertyName(key)) {
            "$key="
        } else {
            record
        }
    }

internal fun sanitizeOracleInput(raw: String): String {
    val prefixMatch = ORACLE_THIN_PREFIX_REGEX.find(raw)
    val prefix = prefixMatch?.value.orEmpty()
    val rest = raw.substring(prefix.length)
    if (rest.startsWith("@")) return sanitizeSensitiveQueryProperties(raw)

    val atIndex = findOracleAuthSeparator(rest)
    if (atIndex < 0) return sanitizeSensitiveQueryProperties(raw)

    val auth = rest.substring(0, atIndex)
    val slashIndex = auth.indexOf('/')
    if (slashIndex < 0) return sanitizeSensitiveQueryProperties(raw)

    return sanitizeSensitiveQueryProperties(prefix + rest.substring(atIndex))
}

private fun sanitizeSensitiveQueryProperties(raw: String): String {
    val queryStart = raw.indexOf('?')
    if (queryStart < 0) return raw
    val fragmentStart = raw.indexOf('#', queryStart)
    val queryEnd = if (fragmentStart < 0) raw.length else fragmentStart
    val safeQuery =
        raw.substring(queryStart + 1, queryEnd)
            .split('&')
            .filter(String::isNotEmpty)
            .filterNot { part ->
                val name = decodeComponent(part.substringBefore('='))
                isSensitiveDriverPropertyName(name)
            }
            .joinToString("&")
    val fragment = if (fragmentStart < 0) "" else raw.substring(fragmentStart)
    return raw.substring(0, queryStart) +
        safeQuery.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty() +
        fragment
}

internal fun findOracleAuthSeparator(rest: String): Int {
    // Stop before query/fragment delimiters so `@` in parameter values (e.g. wallet paths) is
    // ignored, then take the final `@` because an unescaped password may contain `@`.
    val queryStart = rest.indexOfAny(QUERY_OR_FRAGMENT_CHARS)
    val authAndConnect = if (queryStart < 0) rest else rest.substring(0, queryStart)
    return authAndConnect.lastIndexOf('@')
}
