package com.safedb.connection

enum class ConnectionErrorKind {
    UntrustedCa,
    HostnameMismatch,
    CertificateRequired,
    Unknown,
}

data class ConnectionErrorClassification(
    val kind: ConnectionErrorKind,
    val showTroubleshooting: Boolean,
)

fun classifyConnectionError(
    message: String,
    context: ConnectionErrorContext,
): ConnectionErrorClassification {
    val normalized = message.lowercase()
    val hasCertificateHostnameMismatch =
        normalized.contains("certificate is not valid for") ||
            normalized.contains("cert is not valid for") ||
            (normalized.contains("x509") && normalized.contains("not valid for"))

    if (
        normalized.contains("hostname mismatch") ||
        normalized.contains("name mismatch") ||
        hasCertificateHostnameMismatch
    ) {
        return ConnectionErrorClassification(ConnectionErrorKind.HostnameMismatch, showTroubleshooting = true)
    }

    if (
        normalized.contains("wallet") ||
        normalized.contains("tcps") ||
        normalized.contains("ssl required") ||
        normalized.contains("requires ssl") ||
        normalized.contains("requires tls") ||
        normalized.contains("certificate required")
    ) {
        return ConnectionErrorClassification(ConnectionErrorKind.CertificateRequired, showTroubleshooting = true)
    }

    if (
        normalized.contains("certificate verify failed") ||
        normalized.contains("unknown issuer") ||
        normalized.contains("unknown ca") ||
        normalized.contains("self signed") ||
        normalized.contains("self-signed") ||
        normalized.contains("invalid certificate")
    ) {
        return ConnectionErrorClassification(ConnectionErrorKind.UntrustedCa, showTroubleshooting = true)
    }

    return ConnectionErrorClassification(
        kind = ConnectionErrorKind.Unknown,
        showTroubleshooting = context.location == DatabaseLocation.Organization && context.remoteHost,
    )
}

data class ConnectionErrorContext(
    val location: DatabaseLocation?,
    val remoteHost: Boolean,
)
