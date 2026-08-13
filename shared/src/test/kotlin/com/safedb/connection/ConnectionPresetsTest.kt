package com.safedb.connection

import com.safedb.model.TransportSecurityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionPresetsTest {
    @Test
    fun isLocalHostRecognizesLoopbackForms() {
        assertTrue(isLocalHost("localhost"))
        assertTrue(isLocalHost("127.0.0.1"))
        assertTrue(isLocalHost("::1"))
        assertTrue(isLocalHost("[::1]"))
        assertTrue(isLocalHost("  LOCALHOST  "))
    }

    @Test
    fun isLocalHostRejectsRemoteHosts() {
        assertFalse(isLocalHost("db.example.com"))
        assertFalse(isLocalHost("10.0.0.5"))
    }

    @Test
    fun inferLocationUsesLocalForLoopback() {
        assertEquals(DatabaseLocation.Local, inferLocation("localhost"))
        assertEquals(DatabaseLocation.Cloud, inferLocation("db.example.com"))
    }

    @Test
    fun transportPresetForLocation() {
        assertEquals(
            TransportSecurityMode.Disabled,
            transportPresetForLocation(DatabaseLocation.Local).mode,
        )
        assertEquals(
            TransportSecurityMode.VerifyIdentity,
            transportPresetForLocation(DatabaseLocation.Cloud).mode,
        )
        assertEquals(
            TransportSecurityMode.VerifyIdentity,
            transportPresetForLocation(DatabaseLocation.Organization).mode,
        )
    }
}
