package com.safedb.connection

import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode

enum class DatabaseLocation {
    Local,
    Cloud,
    Organization,
}

data class SecurityLabel(val tone: SecurityTone, val text: String)

enum class SecurityTone {
    Success,
    Warning,
    Danger,
}

data class DialectInfo(val value: com.safedb.model.Dialect, val label: String, val defaultPort: Int)

val DIALECTS: List<DialectInfo> =
    listOf(
        DialectInfo(com.safedb.model.Dialect.Postgres, "PostgreSQL", 5432),
        DialectInfo(com.safedb.model.Dialect.MySql, "MySQL", 3306),
        DialectInfo(com.safedb.model.Dialect.Mssql, "SQL Server", 1433),
        DialectInfo(com.safedb.model.Dialect.Oracle, "Oracle", 1521),
    )

fun transportPresetForLocation(location: DatabaseLocation): TransportSecurity =
    if (location == DatabaseLocation.Local) {
        TransportSecurity(
            mode = TransportSecurityMode.Disabled,
            caPem = null,
            oracleWalletLocation = null,
            legacyImplicit = false,
        )
    } else {
        TransportSecurity(
            mode = TransportSecurityMode.VerifyIdentity,
            caPem = null,
            oracleWalletLocation = null,
            legacyImplicit = false,
        )
    }

fun securityLabelForMode(mode: TransportSecurityMode, host: String = ""): SecurityLabel =
    when (mode) {
        TransportSecurityMode.VerifyIdentity,
        TransportSecurityMode.VerifyCa -> SecurityLabel(SecurityTone.Success, "Secure connection")

        TransportSecurityMode.EncryptOnly ->
            SecurityLabel(SecurityTone.Warning, "Encrypted (certificate not verified)")

        TransportSecurityMode.Disabled ->
            if (isLocalHost(host)) {
                SecurityLabel(SecurityTone.Danger, "Not encrypted - local only")
            } else {
                SecurityLabel(SecurityTone.Danger, "Not encrypted")
            }
    }

fun isLocalHost(host: String): Boolean {
    val normalized = host.trim().lowercase()
    return normalized == "localhost" ||
        normalized == "127.0.0.1" ||
        normalized == "::1" ||
        normalized == "[::1]"
}

fun inferLocation(host: String): DatabaseLocation =
    if (isLocalHost(host)) DatabaseLocation.Local else DatabaseLocation.Cloud
