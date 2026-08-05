package com.safedb.ui

import com.safedb.model.Dialect
import com.safedb.model.TransportSecurityMode

internal data class TransportSecurityOption(
    val value: TransportSecurityMode,
    val label: String,
    val description: String,
    val recommended: Boolean = false,
)

private val VERIFY_IDENTITY =
    TransportSecurityOption(
        TransportSecurityMode.VerifyIdentity,
        "Verify certificate and hostname",
        "Encrypts traffic, validates the certificate chain, and checks the database hostname.",
        recommended = true,
    )
private val VERIFY_CA =
    TransportSecurityOption(
        TransportSecurityMode.VerifyCa,
        "Verify certificate only",
        "Encrypts traffic and validates the certificate chain without checking the database hostname.",
    )
private val ENCRYPT_ONLY =
    TransportSecurityOption(
        TransportSecurityMode.EncryptOnly,
        "Encrypt only - certificate not verified",
        "Encrypts traffic but does not verify the server certificate. Use only for legacy compatibility.",
    )
private val DISABLED =
    TransportSecurityOption(
        TransportSecurityMode.Disabled,
        "No encryption",
        "Sends database traffic without TLS. Use only for a trusted local connection.",
    )
private val ORACLE_VERIFY_IDENTITY =
    VERIFY_IDENTITY.copy(
        label = "TCPS: verify certificate and server identity",
        description =
            "Uses the Oracle wallet to validate trust and checks the database server identity.",
    )
private val ORACLE_VERIFY_CA =
    VERIFY_CA.copy(
        label = "TCPS: verify certificate only",
        description =
            "Uses the Oracle wallet to validate trust without checking the database server identity.",
    )

internal fun transportOptionsFor(dialect: Dialect): List<TransportSecurityOption> =
    when (dialect) {
        Dialect.Postgres,
        Dialect.MySql -> listOf(VERIFY_IDENTITY, VERIFY_CA, ENCRYPT_ONLY, DISABLED)
        Dialect.Mssql -> listOf(VERIFY_IDENTITY, ENCRYPT_ONLY, DISABLED)
        Dialect.Oracle -> listOf(ORACLE_VERIFY_IDENTITY, ORACLE_VERIFY_CA, DISABLED)
    }

internal fun displayedTransportMode(
    dialect: Dialect,
    mode: TransportSecurityMode,
): TransportSecurityMode =
    when {
        dialect == Dialect.Mssql && mode == TransportSecurityMode.VerifyCa ->
            TransportSecurityMode.VerifyIdentity
        dialect == Dialect.Oracle && mode == TransportSecurityMode.EncryptOnly ->
            TransportSecurityMode.VerifyCa
        else -> mode
    }

internal fun normalizedTransportMode(
    dialect: Dialect,
    mode: TransportSecurityMode,
): TransportSecurityMode = displayedTransportMode(dialect, mode)

internal fun isCertificateVerifying(mode: TransportSecurityMode): Boolean =
    mode == TransportSecurityMode.VerifyIdentity || mode == TransportSecurityMode.VerifyCa

internal fun supportsConnectionCa(dialect: Dialect, mode: TransportSecurityMode): Boolean =
    dialect != Dialect.Oracle && isCertificateVerifying(mode)

internal fun transportCompatibilityWarning(dialect: Dialect, mode: TransportSecurityMode): String? =
    when {
        dialect == Dialect.Mssql && mode == TransportSecurityMode.VerifyCa ->
            "SQL Server validates the certificate hostname in this mode; it is shown as certificate and hostname verification."
        dialect == Dialect.Oracle && mode == TransportSecurityMode.EncryptOnly ->
            "Oracle wallet-based TCPS validates certificate trust; this legacy mode is shown as certificate-only verification."
        else -> null
    }
