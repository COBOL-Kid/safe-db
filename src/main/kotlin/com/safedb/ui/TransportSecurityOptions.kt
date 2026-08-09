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
        "SSL with hostname verification",
        "Encrypts traffic, validates the certificate chain, and verifies the database hostname.",
        recommended = true,
    )
private val VERIFY_CA =
    TransportSecurityOption(
        TransportSecurityMode.VerifyCa,
        "SSL certificate verification only (no hostname check)",
        "Encrypts traffic and validates the certificate chain without verifying the database hostname.",
    )
private val ENCRYPT_ONLY =
    TransportSecurityOption(
        TransportSecurityMode.EncryptOnly,
        "SSL encrypt only (no cert check)",
        "Encrypts traffic without validating the server certificate or database hostname.",
    )
private val DISABLED =
    TransportSecurityOption(
        TransportSecurityMode.Disabled,
        "SSL disabled (unencrypted)",
        "Disables SSL; database traffic is not encrypted and the server identity is not verified.",
    )
private val ORACLE_VERIFY_IDENTITY =
    VERIFY_IDENTITY.copy(
        label = "TCPS with server identity verification",
        description =
            "Encrypts traffic, uses the Oracle wallet to validate the certificate chain, and verifies the database server identity.",
    )
private val ORACLE_VERIFY_CA =
    VERIFY_CA.copy(
        label = "TCPS certificate verification only (no identity check)",
        description =
            "Encrypts traffic and uses the Oracle wallet to validate the certificate chain without verifying the database server identity.",
    )
private val ORACLE_DISABLED =
    DISABLED.copy(
        label = "TCPS disabled (unencrypted TCP)",
        description =
            "Disables TCPS; database traffic uses unencrypted TCP and the server identity is not verified.",
    )

internal fun transportOptionsFor(dialect: Dialect): List<TransportSecurityOption> =
    when (dialect) {
        Dialect.Postgres,
        Dialect.MySql -> listOf(VERIFY_IDENTITY, VERIFY_CA, ENCRYPT_ONLY, DISABLED)
        Dialect.Mssql -> listOf(VERIFY_IDENTITY, ENCRYPT_ONLY, DISABLED)
        Dialect.Oracle -> listOf(ORACLE_VERIFY_IDENTITY, ORACLE_VERIFY_CA, ORACLE_DISABLED)
    }

internal fun displayedTransportMode(
    dialect: Dialect,
    mode: TransportSecurityMode,
): TransportSecurityMode =
    when (dialect) {
        Dialect.Mssql if mode == TransportSecurityMode.VerifyCa ->
            TransportSecurityMode.VerifyIdentity
        Dialect.Oracle if mode == TransportSecurityMode.EncryptOnly ->
            TransportSecurityMode.VerifyCa
        else -> mode
    }

internal fun normalizedTransportMode(
    dialect: Dialect,
    mode: TransportSecurityMode,
): TransportSecurityMode = displayedTransportMode(dialect, mode)

internal fun transportCompatibilityWarning(dialect: Dialect, mode: TransportSecurityMode): String? =
    when (dialect) {
        Dialect.Mssql if mode == TransportSecurityMode.VerifyCa ->
            "SQL Server validates the certificate hostname in this mode; it is shown as certificate and hostname verification."
        Dialect.Oracle if mode == TransportSecurityMode.EncryptOnly ->
            "Oracle wallet-based TCPS validates certificate trust; this legacy mode is shown as certificate-only verification."
        else -> null
    }
