package com.safedb.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SslErrorsTest {
    @Test
    fun classifyHostnameMismatch() {
        val result =
            classifyConnectionError(
                "javax.net.ssl.SSLHandshakeException: hostname mismatch",
                ConnectionErrorContext(DatabaseLocation.Cloud, remoteHost = true),
            )
        assertEquals(ConnectionErrorKind.HostnameMismatch, result.kind)
        assertTrue(result.showTroubleshooting)
    }

    @Test
    fun classifyCertificateNotValidForHost() {
        val result =
            classifyConnectionError(
                "Certificate is not valid for host db.example.com",
                ConnectionErrorContext(DatabaseLocation.Organization, remoteHost = true),
            )
        assertEquals(ConnectionErrorKind.HostnameMismatch, result.kind)
    }

    @Test
    fun classifyCertificateRequired() {
        val result =
            classifyConnectionError(
                "Connections using insecure transport are prohibited. SSL required",
                ConnectionErrorContext(DatabaseLocation.Cloud, remoteHost = true),
            )
        assertEquals(ConnectionErrorKind.CertificateRequired, result.kind)
        assertTrue(result.showTroubleshooting)
    }

    @Test
    fun classifyUntrustedCa() {
        val result =
            classifyConnectionError(
                "certificate verify failed: self signed certificate in certificate chain",
                ConnectionErrorContext(DatabaseLocation.Cloud, remoteHost = true),
            )
        assertEquals(ConnectionErrorKind.UntrustedCa, result.kind)
        assertTrue(result.showTroubleshooting)
    }

    @Test
    fun classifyUnknownOrgRemoteShowsTroubleshooting() {
        val result =
            classifyConnectionError(
                "connection refused",
                ConnectionErrorContext(DatabaseLocation.Organization, remoteHost = true),
            )
        assertEquals(ConnectionErrorKind.Unknown, result.kind)
        assertTrue(result.showTroubleshooting)
    }

    @Test
    fun classifyUnknownLocalDoesNotShowTroubleshooting() {
        val result =
            classifyConnectionError(
                "connection refused",
                ConnectionErrorContext(DatabaseLocation.Local, remoteHost = false),
            )
        assertEquals(ConnectionErrorKind.Unknown, result.kind)
        assertFalse(result.showTroubleshooting)
    }
}
