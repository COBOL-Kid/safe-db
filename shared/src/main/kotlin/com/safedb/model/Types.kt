package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

const val CURRENT_CONNECTION_VERSION = 2

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
) {
    fun credentialFingerprint(): String {
        val material = Json.encodeToString(
            FingerprintMaterial.serializer(),
            FingerprintMaterial(dialect, host, port, database, username, transportSecurity),
        )
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
        return Result.success(Unit)
    }
}

@Serializable
private data class FingerprintMaterial(
    val dialect: Dialect,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    @SerialName("transport_security") val transportSecurity: TransportSecurity,
)
