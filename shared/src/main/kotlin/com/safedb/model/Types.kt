package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

const val CURRENT_CONNECTION_VERSION = 2
const val MAX_DRIVER_PROPERTIES = 50
const val MAX_DRIVER_PROPERTY_NAME_LENGTH = 128
const val MAX_DRIVER_PROPERTY_VALUE_LENGTH = 4_096

@Serializable
enum class TransportSecurityMode {
    @SerialName("VerifyIdentity")
    VerifyIdentity,

    @SerialName("VerifyCa")
    VerifyCa,

    @SerialName("EncryptOnly")
    EncryptOnly,

    @SerialName("Disabled")
    Disabled,
}

@Serializable
data class TransportSecurity(
    val mode: TransportSecurityMode = TransportSecurityMode.VerifyIdentity,
    @SerialName("ca_pem") val caPem: String? = null,
    @SerialName("oracle_wallet_location") val oracleWalletLocation: String? = null,
    @SerialName("legacy_implicit") val legacyImplicit: Boolean = false,
)

@Serializable
enum class Dialect {
    @SerialName("Postgres")
    Postgres,

    @SerialName("MySql")
    MySql,

    @SerialName("Mssql")
    Mssql,

    @SerialName("Oracle")
    Oracle,
}

@Serializable
data class DriverProperty(
    val name: String,
    val value: String,
)

private val SENSITIVE_DRIVER_PROPERTY_FRAGMENTS = listOf(
    "password",
    "passwd",
    "pwd",
    "secret",
    "token",
    "credential",
    "privatekey",
    "accesskey",
    "apikey",
)

private val SENSITIVE_DRIVER_PROPERTY_NAMES = setOf(
    // Microsoft JDBC's deprecated Azure Key Vault client-secret property does not contain a generic secret fragment.
    "keyvaultproviderclientkey",
)

private val COMMON_RESERVED_DRIVER_PROPERTIES = setOf(
    "url",
    "jdbcurl",
    "user",
    "username",
    "host",
    "hostname",
    "servername",
    "port",
    "portnumber",
    "database",
    "databasename",
)

private val DIALECT_RESERVED_DRIVER_PROPERTIES = mapOf(
    Dialect.Postgres to setOf("ssl", "sslmode", "sslfactory", "sslrootcert", "sslcert", "sslkey"),
    Dialect.MySql to setOf(
        "sslmode",
        "usessl",
        "requiressl",
        "verifyservercertificate",
        "allowpublickeyretrieval",
        "trustcertificatekeystoreurl",
        "trustcertificatekeystoretype",
        "clientcertificatekeystoreurl",
        "clientcertificatekeystoretype",
    ),
    Dialect.Mssql to setOf(
        "encrypt",
        "trustservercertificate",
        "hostnameincertificate",
        "truststore",
        "truststoretype",
    ),
    Dialect.Oracle to setOf(
        "walletlocation",
        "oraclenetwalletlocation",
        "oraclenetsslserverdnmatch",
    ),
)

private fun normalizedDriverPropertyName(name: String): String =
    name.lowercase().filter(Char::isLetterOrDigit)

fun isSensitiveDriverPropertyName(name: String): Boolean {
    val normalized = normalizedDriverPropertyName(name)
    return normalized in SENSITIVE_DRIVER_PROPERTY_NAMES ||
        SENSITIVE_DRIVER_PROPERTY_FRAGMENTS.any(normalized::contains)
}

fun isReservedDriverPropertyName(dialect: Dialect, name: String): Boolean {
    val normalized = normalizedDriverPropertyName(name)
    return normalized in COMMON_RESERVED_DRIVER_PROPERTIES ||
        normalized in DIALECT_RESERVED_DRIVER_PROPERTIES.getValue(dialect)
}

fun validateDriverProperties(dialect: Dialect, properties: List<DriverProperty>): Result<Unit> {
    if (properties.size > MAX_DRIVER_PROPERTIES) {
        return Result.failure(IllegalArgumentException("At most $MAX_DRIVER_PROPERTIES driver parameters are allowed"))
    }
    val seen = mutableSetOf<String>()
    properties.forEach { property ->
        if (property.name.isBlank()) {
            return Result.failure(IllegalArgumentException("Driver parameter names are required"))
        }
        if (property.name != property.name.trim()) {
            return Result.failure(IllegalArgumentException("Driver parameter names cannot start or end with whitespace"))
        }
        if (property.name.length > MAX_DRIVER_PROPERTY_NAME_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Driver parameter names cannot exceed $MAX_DRIVER_PROPERTY_NAME_LENGTH characters"),
            )
        }
        if (property.value.length > MAX_DRIVER_PROPERTY_VALUE_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Driver parameter values cannot exceed $MAX_DRIVER_PROPERTY_VALUE_LENGTH characters"),
            )
        }
        if (property.name.any(Char::isISOControl) || property.value.any(Char::isISOControl)) {
            return Result.failure(IllegalArgumentException("Driver parameters cannot contain control characters"))
        }
        if (isSensitiveDriverPropertyName(property.name)) {
            return Result.failure(
                IllegalArgumentException("Driver parameter '${property.name}' may contain a secret and cannot be saved"),
            )
        }
        if (isReservedDriverPropertyName(dialect, property.name)) {
            return Result.failure(
                IllegalArgumentException("Driver parameter '${property.name}' is managed by Safe-DB"),
            )
        }
        if (!seen.add(property.name.lowercase())) {
            return Result.failure(
                IllegalArgumentException("Driver parameter names must be unique (ignoring case)"),
            )
        }
    }
    return Result.success(Unit)
}

@Serializable
data class ConnectionDef(
    val version: Int = CURRENT_CONNECTION_VERSION,
    val id: String,
    val name: String,
    val dialect: Dialect,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    @SerialName("transport_security") val transportSecurity: TransportSecurity = TransportSecurity(),
    @SerialName("driver_properties") val driverProperties: List<DriverProperty> = emptyList(),
) {
    fun credentialFingerprint(): String {
        val material = if (driverProperties.isEmpty()) {
            Json.encodeToString(
                LegacyFingerprintMaterial.serializer(),
                LegacyFingerprintMaterial(dialect, host, port, database, username, transportSecurity),
            )
        } else {
            val canonicalProperties = driverProperties.sortedWith(
                compareBy<DriverProperty> { it.name.lowercase() }.thenBy { it.name }.thenBy { it.value },
            )
            Json.encodeToString(
                DriverPropertyFingerprintMaterial.serializer(),
                DriverPropertyFingerprintMaterial(
                    dialect,
                    host,
                    port,
                    database,
                    username,
                    transportSecurity,
                    canonicalProperties,
                ),
            )
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun validate(): Result<Unit> {
        if (id.trim().isEmpty()) return Result.failure(IllegalArgumentException("Connection id is required"))
        if (host.trim().isEmpty()) return Result.failure(IllegalArgumentException("Host is required"))
        if (database.trim().isEmpty()) return Result.failure(IllegalArgumentException("Database is required"))
        if (username.trim().isEmpty()) return Result.failure(IllegalArgumentException("Username is required"))
        if (port !in 1..65535) {
            return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
        }
        if (dialect == Dialect.Oracle &&
            transportSecurity.mode != TransportSecurityMode.Disabled &&
            transportSecurity.oracleWalletLocation.isNullOrBlank()
        ) {
            return Result.failure(
                IllegalArgumentException("Verified Oracle TCPS requires an Oracle wallet location"),
            )
        }
        validateDriverProperties(dialect, driverProperties).getOrElse { return Result.failure(it) }
        return Result.success(Unit)
    }
}

@Serializable
private data class LegacyFingerprintMaterial(
    val dialect: Dialect,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    @SerialName("transport_security") val transportSecurity: TransportSecurity,
)

@Serializable
private data class DriverPropertyFingerprintMaterial(
    val dialect: Dialect,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    @SerialName("transport_security") val transportSecurity: TransportSecurity,
    @SerialName("driver_properties") val driverProperties: List<DriverProperty>,
)
