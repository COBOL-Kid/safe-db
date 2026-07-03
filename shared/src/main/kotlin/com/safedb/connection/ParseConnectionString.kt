package com.safedb.connection

import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ParsedConnection(
    val dialect: Dialect,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String?,
    val transportSecurity: TransportSecurity,
    val inferredLocation: DatabaseLocation,
    val warnings: List<String>,
    val sanitizedInput: String,
)

class ConnectionStringParseError(message: String) : Exception(message)

private data class MutableTransport(
    var mode: TransportSecurityMode,
    var caPem: String? = null,
    var oracleWalletLocation: String? = null,
)

private val DEFAULT_PORTS = mapOf(
    Dialect.Postgres to 5432,
    Dialect.MySql to 3306,
    Dialect.Mssql to 1433,
    Dialect.Oracle to 1521,
)

fun parseConnectionString(input: String): ParsedConnection {
    val raw = input.trim()
    if (raw.isEmpty()) throw ConnectionStringParseError("Paste a connection string to continue.")

    return when {
        matchesPrefix(raw, "^jdbc:postgresql://") -> parsePostgresJdbc(raw)
        matchesPrefix(raw, "^postgres(?:ql)?://") -> parsePostgresUri(raw)
        matchesPrefix(raw, "^jdbc:mysql://") -> parseMysqlJdbc(raw)
        matchesPrefix(raw, "^mysql://") -> parseMysqlUri(raw)
        matchesPrefix(raw, "^jdbc:sqlserver://") -> parseSqlServerJdbc(raw)
        matchesPrefix(raw, "^jdbc:oracle:thin:") ||
            matchesPrefix(raw, "^@?tcps?:") ||
            raw.startsWith("@//") -> parseOracle(raw)

        looksLikeSqlServerKeyValue(raw) -> parseSqlServerKeyValue(raw)
        else -> throw ConnectionStringParseError(
            "This connection string format is not recognized. Try the guided setup instead.",
        )
    }
}

private fun matchesPrefix(raw: String, pattern: String): Boolean =
    Regex(pattern, RegexOption.IGNORE_CASE).find(raw)?.range?.first == 0

private fun baseResult(
    dialect: Dialect,
    host: String,
    port: Int? = null,
    database: String,
    username: String? = null,
    password: String? = null,
    transport: MutableTransport,
    warnings: List<String> = emptyList(),
    sanitizedInput: String,
): ParsedConnection {
    val cleanHost = stripBrackets(host.trim())
    if (cleanHost.isEmpty()) throw ConnectionStringParseError("Connection string is missing a host.")
    if (database.trim().isEmpty()) {
        throw ConnectionStringParseError("Connection string is missing a database name.")
    }

    val resolvedPort = port ?: DEFAULT_PORTS.getValue(dialect)
    if (resolvedPort !in 1..65535) {
        throw ConnectionStringParseError("Connection string has an invalid port.")
    }

    return ParsedConnection(
        dialect = dialect,
        host = cleanHost,
        port = resolvedPort,
        database = database.trim(),
        username = username?.trim().orEmpty(),
        password = password,
        transportSecurity = TransportSecurity(
            mode = transport.mode,
            caPem = transport.caPem,
            oracleWalletLocation = transport.oracleWalletLocation,
            legacyImplicit = false,
        ),
        inferredLocation = inferLocation(cleanHost),
        warnings = warnings,
        sanitizedInput = sanitizedInput,
    )
}

private fun parsePostgresUri(raw: String): ParsedConnection {
    val url = parseUrl(raw, "PostgreSQL")
    val sslmode = lowercaseParam(url, "sslmode")
    return baseResult(
        dialect = Dialect.Postgres,
        host = url.host,
        port = parsePort(url, Dialect.Postgres),
        database = pathnameDatabase(url.path),
        username = decodeComponent(url.userInfo?.substringBefore(':')),
        password = passwordFromUrl(raw, url),
        transport = postgresTransport(sslmode, url.host),
        sanitizedInput = sanitizeUrlPassword(url),
    )
}

private fun parsePostgresJdbc(raw: String): ParsedConnection =
    parsePostgresUri(raw.replace(Regex("^jdbc:", RegexOption.IGNORE_CASE), ""))

private fun parseMysqlUri(raw: String): ParsedConnection {
    val url = parseUrl(raw, "MySQL")
    val sslMode = lowercaseParam(url, "ssl-mode")
        ?: lowercaseParam(url, "ssl_mode")
        ?: lowercaseParam(url, "sslMode")
    val warnings = sslCaWarnings(url)
    return baseResult(
        dialect = Dialect.MySql,
        host = url.host,
        port = parsePort(url, Dialect.MySql),
        database = pathnameDatabase(url.path),
        username = decodeComponent(url.userInfo?.substringBefore(':')),
        password = passwordFromUrl(raw, url),
        transport = mysqlTransport(sslMode, url.host),
        warnings = warnings,
        sanitizedInput = sanitizeUrlPassword(url),
    )
}

private fun parseMysqlJdbc(raw: String): ParsedConnection =
    parseMysqlUri(raw.replace(Regex("^jdbc:", RegexOption.IGNORE_CASE), ""))

private fun parseSqlServerJdbc(raw: String): ParsedConnection {
    val rest = raw.replace(Regex("^jdbc:sqlserver://", RegexOption.IGNORE_CASE), "")
    val records = splitSemicolonRecords(rest)
    val serverPart = records.firstOrNull().orEmpty()
    val propertyParts = records.drop(1)
    val (host, port) = parseSqlServerHost(serverPart)
    val props = parseSemicolonKeyValues(propertyParts.joinToString(";"))
    val database = findKey(props, listOf("databasename", "database", "initial catalog")).orEmpty()
    val username = findKey(props, listOf("user", "username", "user id", "uid")).orEmpty()
    val password = findKey(props, listOf("password", "pwd"))
    val encrypt = findKey(props, listOf("encrypt"))
    val trustServerCertificate = findKey(props, listOf("trustservercertificate", "trust server certificate"))

    return baseResult(
        dialect = Dialect.Mssql,
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
        transport = sqlServerTransport(encrypt, trustServerCertificate, host),
        sanitizedInput = sanitizeSqlServerKeyValue(raw),
    )
}

private fun parseSqlServerKeyValue(raw: String): ParsedConnection {
    val props = parseSemicolonKeyValues(raw)
    val server = findKey(props, listOf("server", "data source", "address", "addr", "network address")).orEmpty()
    val (host, port) = parseSqlServerHost(server)
    val database = findKey(props, listOf("database", "initial catalog")).orEmpty()
    val username = findKey(props, listOf("user id", "uid", "user", "username")).orEmpty()
    val password = findKey(props, listOf("password", "pwd"))
    val encrypt = findKey(props, listOf("encrypt"))
    val trustServerCertificate = findKey(props, listOf("trustservercertificate", "trust server certificate"))

    return baseResult(
        dialect = Dialect.Mssql,
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
        transport = sqlServerTransport(encrypt, trustServerCertificate, host),
        sanitizedInput = sanitizeSqlServerKeyValue(raw),
    )
}

private fun parseOracle(raw: String): ParsedConnection {
    var rest = raw.replace(Regex("^jdbc:oracle:thin:", RegexOption.IGNORE_CASE), "")
    var username = ""
    var password: String? = null

    if (!rest.startsWith("@")) {
        val atIndex = findOracleAuthSeparator(rest)
        if (atIndex > -1) {
            val auth = rest.substring(0, atIndex)
            rest = rest.substring(atIndex)
            val slashIndex = auth.indexOf('/')
            if (slashIndex > -1) {
                username = auth.substring(0, slashIndex)
                password = auth.substring(slashIndex + 1)
            }
        }
    }

    rest = rest.removePrefix("@")
    if (matchesPrefix(rest, "^\\(description=")) {
        throw ConnectionStringParseError(
            "Oracle TNS DESCRIPTION blocks are not supported. Use guided setup or paste an Easy Connect URL.",
        )
    }

    val protocol = if (matchesPrefix(rest, "^tcps:")) "tcps" else "tcp"
    rest = rest.replace(Regex("^tcps?:", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^//"), "")

    val pseudoUrl = parseUrl("$protocol://$rest", "Oracle")
    val walletLocation = paramValue(pseudoUrl, "wallet_location")
        ?: paramValue(pseudoUrl, "walletLocation")
    val mode = if (protocol == "tcps") TransportSecurityMode.VerifyIdentity else TransportSecurityMode.Disabled
    val warnings =
        if (mode != TransportSecurityMode.Disabled && walletLocation == null) {
            listOf("Oracle TCPS requires a wallet location before testing or saving.")
        } else {
            emptyList()
        }

    return baseResult(
        dialect = Dialect.Oracle,
        host = pseudoUrl.host,
        port = parsePort(pseudoUrl, Dialect.Oracle),
        database = pathnameDatabase(pseudoUrl.path),
        username = decodeComponent(username),
        password = password?.let { decodeComponent(it) },
        transport = MutableTransport(
            mode = mode,
            oracleWalletLocation = walletLocation,
        ),
        warnings = warnings,
        sanitizedInput = sanitizeOracleInput(raw),
    )
}

private data class ParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val userInfo: String?,
    val query: String?,
    val fragment: String?,
    val raw: String,
)

private fun parseUrl(raw: String, label: String): ParsedUrl {
    val uri = try {
        URI(raw)
    } catch (_: Exception) {
        throw ConnectionStringParseError("$label connection string is not a valid URL.")
    }
    val host = uri.host
    if (host.isNullOrBlank()) {
        throw ConnectionStringParseError("$label connection string is not a valid URL.")
    }
    return ParsedUrl(
        scheme = uri.scheme.orEmpty(),
        host = host,
        port = uri.port,
        path = uri.path.orEmpty(),
        userInfo = uri.userInfo,
        query = uri.query,
        fragment = uri.fragment,
        raw = raw,
    )
}

private fun pathnameDatabase(pathname: String): String =
    decodeComponent(pathname.replace(Regex("^/+"), "").split('/').firstOrNull().orEmpty())

private fun parsePort(url: ParsedUrl, dialect: Dialect): Int =
    if (url.port != -1) url.port else DEFAULT_PORTS.getValue(dialect)

private fun postgresTransport(sslmode: String?, host: String): MutableTransport =
    when (sslmode) {
        "disable" -> MutableTransport(TransportSecurityMode.Disabled)
        "require" -> MutableTransport(TransportSecurityMode.EncryptOnly)
        "verify-ca" -> MutableTransport(TransportSecurityMode.VerifyCa)
        "verify-full" -> MutableTransport(TransportSecurityMode.VerifyIdentity)
        else -> MutableTransport(
            if (isLocalHost(host)) TransportSecurityMode.Disabled else TransportSecurityMode.VerifyIdentity,
        )
    }

private fun mysqlTransport(sslMode: String?, host: String): MutableTransport =
    when (sslMode?.replace('-', '_')) {
        "disabled" -> MutableTransport(TransportSecurityMode.Disabled)
        "required" -> MutableTransport(TransportSecurityMode.EncryptOnly)
        "verify_ca" -> MutableTransport(TransportSecurityMode.VerifyCa)
        "verify_identity" -> MutableTransport(TransportSecurityMode.VerifyIdentity)
        else -> MutableTransport(
            if (isLocalHost(host)) TransportSecurityMode.Disabled else TransportSecurityMode.VerifyIdentity,
        )
    }

private fun sqlServerTransport(
    encrypt: String?,
    trustServerCertificate: String?,
    host: String,
): MutableTransport {
    if (isFalse(encrypt)) return MutableTransport(TransportSecurityMode.Disabled)
    if (isTrue(trustServerCertificate)) return MutableTransport(TransportSecurityMode.EncryptOnly)
    if (isTrue(encrypt)) return MutableTransport(TransportSecurityMode.VerifyIdentity)
    return MutableTransport(
        if (isLocalHost(host)) TransportSecurityMode.Disabled else TransportSecurityMode.VerifyIdentity,
    )
}

private fun isTrue(value: String?): Boolean =
    listOf("true", "yes", "mandatory").contains(value?.trim()?.lowercase())

private fun isFalse(value: String?): Boolean =
    listOf("false", "no", "optional").contains(value?.trim()?.lowercase())

private fun lowercaseParam(url: ParsedUrl, key: String): String? =
    paramValue(url, key)?.lowercase()

private fun paramValue(url: ParsedUrl, key: String): String? {
    val query = url.query ?: return null
    return query.split('&')
        .mapNotNull { part ->
            val eqIndex = part.indexOf('=')
            if (eqIndex < 0) return@mapNotNull null
            val candidate = part.substring(0, eqIndex)
            if (candidate.equals(key, ignoreCase = true)) {
                decodeComponent(part.substring(eqIndex + 1))
            } else {
                null
            }
        }
        .firstOrNull()
}

private fun sslCaWarnings(url: ParsedUrl): List<String> =
    if (paramValue(url, "ssl-ca") != null || paramValue(url, "ssl_ca") != null) {
        listOf("A CA path was included in the URL. Paste the PEM certificate after parsing.")
    } else {
        emptyList()
    }

private fun looksLikeSqlServerKeyValue(raw: String): Boolean =
    Regex("(^|;)\\s*(server|data source|database|initial catalog|user id|uid)\\s*=", RegexOption.IGNORE_CASE)
        .containsMatchIn(raw)

private fun splitSemicolonRecords(raw: String): List<String> {
    val records = mutableListOf<String>()
    var current = StringBuilder()
    var quote: Char? = null
    var braceDepth = 0

    for (char in raw) {
        if (quote != null) {
            current.append(char)
            if (char == quote) quote = null
            continue
        }
        if (char == '\'' || char == '"') {
            quote = char
            current.append(char)
            continue
        }
        if (char == '{') {
            braceDepth += 1
            current.append(char)
            continue
        }
        if (char == '}') {
            braceDepth = maxOf(0, braceDepth - 1)
            current.append(char)
            continue
        }
        if (char == ';' && braceDepth == 0) {
            records.add(current.toString())
            current = StringBuilder()
            continue
        }
        current.append(char)
    }

    if (current.isNotEmpty()) records.add(current.toString())
    return records.filter { it.trim().isNotEmpty() }
}

private fun parseSemicolonKeyValues(raw: String): Map<String, String> {
    val props = linkedMapOf<String, String>()
    for (record in splitSemicolonRecords(raw)) {
        val eqIndex = record.indexOf('=')
        if (eqIndex < 0) continue
        val key = record.substring(0, eqIndex).trim().lowercase()
        val value = unwrapValue(record.substring(eqIndex + 1).trim())
        props[key] = value
    }
    return props
}

private fun unwrapValue(value: String): String {
    if (value.startsWith('{') && value.endsWith('}')) return value.substring(1, value.length - 1)
    if (
        (value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith('\'') && value.endsWith('\''))
    ) {
        return value.substring(1, value.length - 1)
    }
    return value
}

private fun findKey(props: Map<String, String>, keys: List<String>): String? {
    for (key in keys) {
        val value = props[key.lowercase()]
        if (value != null) return value
    }
    return null
}

private fun parseSqlServerHost(value: String): Pair<String, Int?> {
    var server = unwrapValue(value.trim())
    server = server.replace(Regex("^tcp:", RegexOption.IGNORE_CASE), "")
    if (server.isEmpty()) throw ConnectionStringParseError("SQL Server connection string is missing a server.")

    if (server.startsWith('[')) {
        val close = server.indexOf(']')
        val host = server.substring(1, close)
        val port = server.substring(close + 1).replace(Regex("^[:,]"), "")
        return host to port.toIntOrNull()
    }

    val parts = if (server.contains(',')) server.split(',', limit = 2) else server.split(':', limit = 2)
    val host = parts[0]
    val port = parts.getOrNull(1)?.toIntOrNull()
    return host to port
}

private fun sanitizeUrlPassword(url: ParsedUrl): String {
    val userInfo = url.userInfo ?: return url.raw
    if (!userInfo.contains(':')) return url.raw
    val user = userInfo.substringBefore(':')
    val portPart = if (url.port != -1) ":${url.port}" else ""
    val queryPart = url.query?.let { "?$it" }.orEmpty()
    val refPart = url.fragment?.let { "#$it" }.orEmpty()
    return "${url.scheme}://$user@${url.host}$portPart${url.path}$queryPart$refPart"
}

private fun passwordFromUrl(raw: String, url: ParsedUrl): String? {
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd < 0) return null
    val authorityStart = schemeEnd + 3
    val authorityEndCandidates = listOf('/', '?', '#')
        .map { char -> raw.indexOf(char, authorityStart) }
        .filter { it >= 0 }
    val authorityEnd = authorityEndCandidates.minOrNull() ?: raw.length
    val authority = raw.substring(authorityStart, authorityEnd)
    val atIndex = authority.lastIndexOf('@')
    if (atIndex < 0) return null
    val auth = authority.substring(0, atIndex)
    if (!auth.contains(':')) return null
    val password = url.userInfo?.substringAfter(':').orEmpty()
    return decodeComponent(password)
}

private fun sanitizeSqlServerKeyValue(raw: String): String =
    splitSemicolonRecords(raw)
        .joinToString(";") { record ->
            val eqIndex = record.indexOf('=')
            if (eqIndex < 0) return@joinToString record
            val key = record.substring(0, eqIndex).trim()
            if (key.equals("password", ignoreCase = true) || key.equals("pwd", ignoreCase = true)) {
                "$key="
            } else {
                record
            }
        }

private fun sanitizeOracleInput(raw: String): String {
    val prefixMatch = Regex("^jdbc:oracle:thin:", RegexOption.IGNORE_CASE).find(raw)
    val prefix = prefixMatch?.value.orEmpty()
    val rest = raw.substring(prefix.length)
    if (rest.startsWith("@")) return raw

    val atIndex = findOracleAuthSeparator(rest)
    if (atIndex < 0) return raw

    val auth = rest.substring(0, atIndex)
    val slashIndex = auth.indexOf('/')
    if (slashIndex < 0) return raw

    return "${prefix}${auth.substring(0, slashIndex)}/${rest.substring(atIndex)}"
}

private fun findOracleAuthSeparator(rest: String): Int {
    val queryStart = Regex("[?#]").find(rest)?.range?.first ?: -1
    val authAndConnect = if (queryStart < 0) rest else rest.substring(0, queryStart)
    return authAndConnect.lastIndexOf('@')
}

private fun stripBrackets(host: String): String =
    if (host.startsWith('[') && host.endsWith(']')) host.substring(1, host.length - 1) else host

private fun decodeComponent(value: String?): String {
    if (value.isNullOrEmpty()) return value.orEmpty()
    return URLDecoder.decode(value, StandardCharsets.UTF_8)
}
