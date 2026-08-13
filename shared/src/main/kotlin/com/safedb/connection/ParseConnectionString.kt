package com.safedb.connection

import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.model.isReservedDriverPropertyName
import com.safedb.model.isSensitiveDriverPropertyName
import com.safedb.model.mySqlSslMode
import com.safedb.model.postgresSslMode
import com.safedb.model.validateDriverProperties
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ParsedConnection(
    val dialect: Dialect,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String?,
    val transportSecurity: TransportSecurity,
    val driverProperties: List<DriverProperty>,
    val inferredLocation: DatabaseLocation,
    val warnings: List<String>,
    val sanitizedInput: String,
)

class ConnectionStringParseError(message: String) : Exception(message)

private data class MutableTransport(
    var mode: TransportSecurityMode,
    var oracleWalletLocation: String? = null,
)

private fun defaultPortFor(dialect: Dialect): Int =
    DIALECTS.first { it.value == dialect }.defaultPort

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
        else ->
            throw ConnectionStringParseError(
                "This connection string format is not recognized. Try the guided setup instead."
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
    driverProperties: List<DriverProperty> = emptyList(),
    warnings: List<String> = emptyList(),
    sanitizedInput: String,
): ParsedConnection {
    val cleanHost = stripBrackets(host.trim())
    if (cleanHost.isEmpty())
        throw ConnectionStringParseError("Connection string is missing a host.")
    if (database.trim().isEmpty()) {
        throw ConnectionStringParseError("Connection string is missing a database name.")
    }

    val resolvedPort = port ?: defaultPortFor(dialect)
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
        transportSecurity =
            TransportSecurity(
                mode = transport.mode,
                oracleWalletLocation = transport.oracleWalletLocation,
            ),
        driverProperties = driverProperties,
        inferredLocation = inferLocation(cleanHost),
        warnings = warnings,
        sanitizedInput = sanitizedInput,
    )
}

private fun parsePostgresUri(raw: String): ParsedConnection {
    val url = parseUrl(raw, "PostgreSQL")
    val sslmode = lowercaseParam(url, "sslmode")
    val extracted = extractDriverProperties(Dialect.Postgres, urlQueryPairs(url), setOf("sslmode"))
    return baseResult(
        dialect = Dialect.Postgres,
        host = url.host,
        port = parsePort(url, Dialect.Postgres),
        database = pathnameDatabase(url.rawPath),
        username = decodeComponent(url.rawUserInfo?.substringBefore(':')),
        password = passwordFromUrl(raw, url),
        transport = postgresTransport(sslmode, url.host),
        driverProperties = extracted.properties,
        warnings = extracted.warnings,
        sanitizedInput = sanitizeUrlPassword(url),
    )
}

private fun parsePostgresJdbc(raw: String): ParsedConnection =
    parsePostgresUri(raw.replace(Regex("^jdbc:", RegexOption.IGNORE_CASE), ""))

private fun parseMysqlUri(raw: String): ParsedConnection {
    val url = parseUrl(raw, "MySQL")
    val sslMode =
        lowercaseParam(url, "ssl-mode")
            ?: lowercaseParam(url, "ssl_mode")
            ?: lowercaseParam(url, "sslMode")
    val extracted =
        extractDriverProperties(
            Dialect.MySql,
            urlQueryPairs(url),
            setOf("ssl-mode", "ssl_mode", "sslmode", "ssl-ca", "ssl_ca"),
        )
    val warnings = sslCaWarnings(url) + extracted.warnings
    return baseResult(
        dialect = Dialect.MySql,
        host = url.host,
        port = parsePort(url, Dialect.MySql),
        database = pathnameDatabase(url.rawPath),
        username = decodeComponent(url.rawUserInfo?.substringBefore(':')),
        password = passwordFromUrl(raw, url),
        transport = mysqlTransport(sslMode, url.host),
        driverProperties = extracted.properties,
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
    val (host, port) = parseSqlServerHost(serverPart)
    return sqlServerResult(
        raw = raw,
        properties = records.drop(1).joinToString(";"),
        host = host,
        port = port,
        databaseKeys = listOf("databasename", "database", "initial catalog"),
        usernameKeys = listOf("user", "username", "user id", "uid"),
    )
}

private fun parseSqlServerKeyValue(raw: String): ParsedConnection {
    val server =
        findKey(
                parseSemicolonKeyValues(raw),
                listOf("server", "data source", "address", "addr", "network address"),
            )
            .orEmpty()
    val (host, port) = parseSqlServerHost(server)
    return sqlServerResult(
        raw = raw,
        properties = raw,
        host = host,
        port = port,
        databaseKeys = listOf("database", "initial catalog"),
        usernameKeys = listOf("user id", "uid", "user", "username"),
    )
}

// The key lists stay caller-supplied: the two syntaxes prefer different aliases first, so merging
// them would change which value wins on input carrying both.
private fun sqlServerResult(
    raw: String,
    properties: String,
    host: String,
    port: Int?,
    databaseKeys: List<String>,
    usernameKeys: List<String>,
): ParsedConnection {
    val props = parseSemicolonKeyValues(properties)
    val encrypt = findKey(props, listOf("encrypt"))
    val trustServerCertificate =
        findKey(props, listOf("trustservercertificate", "trust server certificate"))
    val extracted =
        extractDriverProperties(
            Dialect.Mssql,
            parseSemicolonKeyValueList(properties),
            SQL_SERVER_MANAGED_KEYS,
        )

    return baseResult(
        dialect = Dialect.Mssql,
        host = host,
        port = port,
        database = findKey(props, databaseKeys).orEmpty(),
        username = findKey(props, usernameKeys).orEmpty(),
        password = findKey(props, listOf("password", "pwd")),
        transport = sqlServerTransport(encrypt, trustServerCertificate, host),
        driverProperties = extracted.properties,
        warnings = extracted.warnings,
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
            "Oracle TNS DESCRIPTION blocks are not supported. Use guided setup or paste an Easy Connect URL."
        )
    }

    val protocol = if (matchesPrefix(rest, "^tcps:")) "tcps" else "tcp"
    rest = rest.replace(Regex("^tcps?:", RegexOption.IGNORE_CASE), "").replace(Regex("^//"), "")

    val pseudoUrl = parseUrl("$protocol://$rest", "Oracle")
    val walletLocation =
        paramValue(pseudoUrl, "wallet_location") ?: paramValue(pseudoUrl, "walletLocation")
    val mode =
        if (protocol == "tcps") TransportSecurityMode.VerifyIdentity
        else TransportSecurityMode.Disabled
    val warnings =
        if (mode != TransportSecurityMode.Disabled && walletLocation == null) {
            listOf("Oracle TCPS requires a wallet location before testing or saving.")
        } else {
            emptyList()
        }
    val extracted =
        extractDriverProperties(
            Dialect.Oracle,
            urlQueryPairs(pseudoUrl),
            setOf("wallet_location", "walletlocation"),
        )

    return baseResult(
        dialect = Dialect.Oracle,
        host = pseudoUrl.host,
        port = parsePort(pseudoUrl, Dialect.Oracle),
        database = pathnameDatabase(pseudoUrl.rawPath),
        username = decodeComponent(username),
        password = password?.let { decodeComponent(it) },
        transport = MutableTransport(mode = mode, oracleWalletLocation = walletLocation),
        driverProperties = extracted.properties,
        warnings = warnings + extracted.warnings,
        sanitizedInput = sanitizeOracleInput(raw),
    )
}

private data class ParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val rawPath: String,
    val rawUserInfo: String?,
    val rawQuery: String?,
    val rawFragment: String?,
    val raw: String,
)

private fun parseUrl(raw: String, label: String): ParsedUrl {
    val uri =
        try {
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
        rawPath = uri.rawPath.orEmpty(),
        rawUserInfo = uri.rawUserInfo,
        rawQuery = uri.rawQuery,
        rawFragment = uri.rawFragment,
        raw = raw,
    )
}

private fun pathnameDatabase(pathname: String): String =
    decodeComponent(pathname.replace(Regex("^/+"), "").split('/').firstOrNull().orEmpty())

private fun parsePort(url: ParsedUrl, dialect: Dialect): Int =
    if (url.port != -1) url.port else defaultPortFor(dialect)

private fun postgresTransport(sslmode: String?, host: String): MutableTransport =
    when (sslmode) {
        "disable" -> MutableTransport(TransportSecurityMode.Disabled)
        "require" -> MutableTransport(TransportSecurityMode.EncryptOnly)
        "verify-ca" -> MutableTransport(TransportSecurityMode.VerifyCa)
        "verify-full" -> MutableTransport(TransportSecurityMode.VerifyIdentity)
        else ->
            MutableTransport(
                if (isLocalHost(host)) TransportSecurityMode.Disabled
                else TransportSecurityMode.VerifyIdentity
            )
    }

private fun mysqlTransport(sslMode: String?, host: String): MutableTransport =
    when (sslMode?.replace('-', '_')) {
        "disabled" -> MutableTransport(TransportSecurityMode.Disabled)
        "required" -> MutableTransport(TransportSecurityMode.EncryptOnly)
        "verify_ca" -> MutableTransport(TransportSecurityMode.VerifyCa)
        "verify_identity" -> MutableTransport(TransportSecurityMode.VerifyIdentity)
        else ->
            MutableTransport(
                if (isLocalHost(host)) TransportSecurityMode.Disabled
                else TransportSecurityMode.VerifyIdentity
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
        if (isLocalHost(host)) TransportSecurityMode.Disabled
        else TransportSecurityMode.VerifyIdentity
    )
}

private fun isTrue(value: String?): Boolean =
    listOf("true", "yes", "mandatory").contains(value?.trim()?.lowercase())

private fun isFalse(value: String?): Boolean =
    listOf("false", "no", "optional").contains(value?.trim()?.lowercase())

private fun lowercaseParam(url: ParsedUrl, key: String): String? = paramValue(url, key)?.lowercase()

private fun paramValue(url: ParsedUrl, key: String): String? {
    val query = url.rawQuery ?: return null
    return query
        .split('&')
        .mapNotNull { part ->
            val eqIndex = part.indexOf('=')
            if (eqIndex < 0) return@mapNotNull null
            val candidate = decodeComponent(part.substring(0, eqIndex))
            if (candidate.equals(key, ignoreCase = true)) {
                decodeComponent(part.substring(eqIndex + 1))
            } else {
                null
            }
        }
        .firstOrNull()
}

private fun urlQueryPairs(url: ParsedUrl): List<Pair<String, String>> =
    url.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { part ->
        val eqIndex = part.indexOf('=')
        if (eqIndex < 0) {
            decodeComponent(part) to ""
        } else {
            decodeComponent(part.substring(0, eqIndex)) to
                decodeComponent(part.substring(eqIndex + 1))
        }
    }

private data class ExtractedDriverProperties(
    val properties: List<DriverProperty>,
    val warnings: List<String>,
)

private fun extractDriverProperties(
    dialect: Dialect,
    pairs: List<Pair<String, String>>,
    consumedKeys: Set<String>,
): ExtractedDriverProperties {
    val consumed = consumedKeys.map { it.lowercase() }.toSet()
    val properties = linkedMapOf<String, DriverProperty>()
    val warnings = mutableListOf<String>()
    pairs.forEach { (rawName, value) ->
        val name = rawName.trim()
        val normalized = name.lowercase()
        if (normalized in consumed) return@forEach
        when {
            isSensitiveDriverPropertyName(name) ->
                warnings += "Driver parameter '$name' may contain a secret and was not imported."
            isReservedDriverPropertyName(dialect, name) ->
                warnings += "Driver parameter '$name' is managed by Safe-DB and was not imported."
            else -> {
                val property = DriverProperty(name, value)
                val error = validateDriverProperties(dialect, listOf(property)).exceptionOrNull()
                if (error != null) {
                    warnings += "Driver parameter '$name' was not imported: ${error.message}."
                } else {
                    if (properties.remove(normalized) != null) {
                        warnings +=
                            "Driver parameter '$name' appeared more than once; the last value was imported."
                    }
                    properties[normalized] = property
                }
            }
        }
    }
    return ExtractedDriverProperties(properties.values.toList(), warnings.distinct())
}

private fun sslCaWarnings(url: ParsedUrl): List<String> =
    if (paramValue(url, "ssl-ca") != null || paramValue(url, "ssl_ca") != null) {
        listOf(
            "A CA path was included in the URL and was not imported. Configure custom trust through launch-profile JSON."
        )
    } else {
        emptyList()
    }

private fun looksLikeSqlServerKeyValue(raw: String): Boolean =
    Regex(
            "(^|;)\\s*(server|data source|database|initial catalog|user id|uid)\\s*=",
            RegexOption.IGNORE_CASE,
        )
        .containsMatchIn(raw)

private fun splitSemicolonRecords(raw: String): List<String> {
    val records = mutableListOf<String>()
    var current = StringBuilder()
    var quote: Char? = null
    var inBracedValue = false
    var atValueStart = false
    var seenEquals = false
    var index = 0

    while (index < raw.length) {
        val char = raw[index]
        if (quote != null) {
            current.append(char)
            if (char == quote) quote = null
            index += 1
            continue
        }

        if (inBracedValue) {
            current.append(char)
            if (char == '}') {
                if (index + 1 < raw.length && raw[index + 1] == '}') {
                    current.append('}')
                    index += 2
                    continue
                }
                inBracedValue = false
            }
            index += 1
            continue
        }

        if (atValueStart) {
            if (char.isWhitespace()) {
                current.append(char)
                index += 1
                continue
            }
            atValueStart = false
            if (char == '{') {
                inBracedValue = true
                current.append(char)
                index += 1
                continue
            }
        }

        if (char == '\'' || char == '"') {
            quote = char
            current.append(char)
            index += 1
            continue
        }

        if (char == '=' && !seenEquals) {
            seenEquals = true
            atValueStart = true
            current.append(char)
            index += 1
            continue
        }

        if (char == ';') {
            records.add(current.toString())
            current = StringBuilder()
            atValueStart = false
            seenEquals = false
            index += 1
            continue
        }

        current.append(char)
        index += 1
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

private fun parseSemicolonKeyValueList(raw: String): List<Pair<String, String>> =
    splitSemicolonRecords(raw).mapNotNull { record ->
        val eqIndex = record.indexOf('=')
        if (eqIndex < 0) return@mapNotNull null
        record.substring(0, eqIndex).trim() to unwrapValue(record.substring(eqIndex + 1).trim())
    }

private val SQL_SERVER_MANAGED_KEYS =
    setOf(
        "server",
        "data source",
        "address",
        "addr",
        "network address",
        "databasename",
        "database",
        "initial catalog",
        "user",
        "username",
        "user id",
        "uid",
        "password",
        "pwd",
        "encrypt",
        "trustservercertificate",
        "trust server certificate",
    )

private fun unwrapValue(value: String): String {
    if (value.startsWith('{') && value.endsWith('}')) {
        return value.substring(1, value.length - 1).replace("}}", "}")
    }
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
    if (server.isEmpty())
        throw ConnectionStringParseError("SQL Server connection string is missing a server.")

    if (server.startsWith('[')) {
        val close = server.indexOf(']')
        val host = server.substring(1, close)
        val port = server.substring(close + 1).replace(Regex("^[:,]"), "")
        return host to port.toIntOrNull()
    }

    val parts =
        if (server.contains(',')) server.split(',', limit = 2) else server.split(':', limit = 2)
    val host = parts[0]
    val port = parts.getOrNull(1)?.toIntOrNull()
    return host to port
}

private fun sanitizeUrlPassword(url: ParsedUrl): String {
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

private fun passwordFromUrl(raw: String, url: ParsedUrl): String? {
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd < 0) return null
    val authorityStart = schemeEnd + 3
    val authorityEndCandidates =
        listOf('/', '?', '#').map { char -> raw.indexOf(char, authorityStart) }.filter { it >= 0 }
    val authorityEnd = authorityEndCandidates.minOrNull() ?: raw.length
    val authority = raw.substring(authorityStart, authorityEnd)
    // Use the final @ because an unescaped password may contain @.
    val atIndex = authority.lastIndexOf('@')
    if (atIndex < 0) return null
    val auth = authority.substring(0, atIndex)
    if (!auth.contains(':')) return null
    val password = url.rawUserInfo?.substringAfter(':').orEmpty()
    return decodeComponent(password)
}

private fun sanitizeSqlServerKeyValue(raw: String): String =
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

private fun sanitizeOracleInput(raw: String): String {
    val prefixMatch = Regex("^jdbc:oracle:thin:", RegexOption.IGNORE_CASE).find(raw)
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

private fun encodeComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun encodeSqlServerPropertyValue(value: String): String = "{${value.replace("}", "}}")}}"

fun formatConnectionString(def: com.safedb.model.ConnectionDef): String {
    val properties =
        def.driverProperties.joinToString("&") {
            "${encodeComponent(it.name)}=${encodeComponent(it.value)}"
        }
    return when (def.dialect) {
        Dialect.Postgres -> {
            val sslMode = def.transportSecurity.mode.postgresSslMode()
            val query =
                listOf("sslmode=$sslMode", properties).filter(String::isNotEmpty).joinToString("&")
            "postgresql://${encodeComponent(def.username)}@${def.host}:${def.port}/${encodeComponent(def.database)}?$query"
        }
        Dialect.MySql -> {
            val sslMode = def.transportSecurity.mode.mySqlSslMode()
            val query =
                listOf("ssl-mode=$sslMode", properties).filter(String::isNotEmpty).joinToString("&")
            "mysql://${encodeComponent(def.username)}@${def.host}:${def.port}/${encodeComponent(def.database)}?$query"
        }
        Dialect.Mssql -> {
            // Deliberately not shared with applyMssqlSsl: an exported string carries only the
            // properties a driver needs to reproduce the mode, while connecting also pins
            // hostNameInCertificate.
            val security =
                when (def.transportSecurity.mode) {
                    TransportSecurityMode.VerifyIdentity,
                    TransportSecurityMode.VerifyCa ->
                        listOf("encrypt=true", "trustServerCertificate=false")
                    TransportSecurityMode.EncryptOnly ->
                        listOf("encrypt=true", "trustServerCertificate=true")
                    TransportSecurityMode.Disabled ->
                        listOf("encrypt=false", "trustServerCertificate=true")
                }
            val extra =
                def.driverProperties.map { "${it.name}=${encodeSqlServerPropertyValue(it.value)}" }
            (listOf(
                    "jdbc:sqlserver://${def.host}:${def.port}",
                    "databaseName=${encodeSqlServerPropertyValue(def.database)}",
                    "user=${encodeSqlServerPropertyValue(def.username)}",
                ) + security + extra)
                .joinToString(";")
        }
        Dialect.Oracle -> {
            val protocol =
                if (def.transportSecurity.mode == TransportSecurityMode.Disabled) "@//"
                else "@tcps://"
            val wallet =
                def.transportSecurity.oracleWalletLocation
                    ?.let { "wallet_location=${encodeComponent(it)}" }
                    .orEmpty()
            val query = listOf(wallet, properties).filter(String::isNotEmpty).joinToString("&")
            val suffix = query.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
            "jdbc:oracle:thin:$protocol${def.host}:${def.port}/${encodeComponent(def.database)}$suffix"
        }
    }
}
