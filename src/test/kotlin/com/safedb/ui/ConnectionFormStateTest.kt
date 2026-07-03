package com.safedb.ui

import com.safedb.connection.DatabaseLocation
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionFormStateTest {
    @Test
    fun localGuidedSetupSendsDisabledTransport() {
        val state = ConnectionFormState()

        state.choosePath(EntryPath.Guided)
        state.applyLocationPreset(DatabaseLocation.Local)

        assertEquals(FormStep.Credentials, state.formStep)
        assertEquals(TransportSecurityMode.Disabled, state.transportMode)
    }

    @Test
    fun cloudGuidedSetupSendsVerifyIdentityTransport() {
        val state = ConnectionFormState()

        state.choosePath(EntryPath.Guided)
        state.applyLocationPreset(DatabaseLocation.Cloud)

        assertEquals(TransportSecurityMode.VerifyIdentity, state.transportMode)
    }

    @Test
    fun cloudHostChangedToLocalhostResyncsToDisabled() {
        val state = ConnectionFormState()
        state.choosePath(EntryPath.Guided)
        state.applyLocationPreset(DatabaseLocation.Cloud)

        state.handleHostInput("localhost")

        assertEquals(DatabaseLocation.Local, state.location)
        assertEquals(TransportSecurityMode.Disabled, state.transportMode)
    }

    @Test
    fun localHostChangedToRemoteResyncsToVerifyIdentity() {
        val state = ConnectionFormState()
        state.choosePath(EntryPath.Guided)
        state.applyLocationPreset(DatabaseLocation.Local)

        state.handleHostInput("db.example.com")

        assertEquals(DatabaseLocation.Cloud, state.location)
        assertEquals(TransportSecurityMode.VerifyIdentity, state.transportMode)
    }

    @Test
    fun organizationHostEditsPreserveOrganizationContext() {
        val state = ConnectionFormState()
        state.choosePath(EntryPath.Guided)
        state.applyLocationPreset(DatabaseLocation.Organization)

        state.handleHostInput("localhost")

        assertEquals(DatabaseLocation.Organization, state.location)
        assertEquals(TransportSecurityMode.VerifyIdentity, state.transportMode)
    }

    @Test
    fun manualTransportOverridePreventsAutomaticResync() {
        val state = ConnectionFormState()
        state.choosePath(EntryPath.Guided)
        state.applyLocationPreset(DatabaseLocation.Cloud)

        state.transportMode = TransportSecurityMode.EncryptOnly
        state.markTransportManual()
        state.handleHostInput("localhost")

        assertEquals(DatabaseLocation.Cloud, state.location)
        assertEquals(TransportSecurityMode.EncryptOnly, state.transportMode)
        assertTrue(state.transportOverridden)
    }

    @Test
    fun parsedRemoteConnectionChangedToLocalhostResyncsToLocalDefaults() {
        val state = ConnectionFormState()
        state.connectionString = "postgresql://user:secret@db.example.com:5432/demo"
        state.applyParsedInput()

        state.handleHostInput("localhost")

        assertEquals(DatabaseLocation.Local, state.location)
        assertEquals(TransportSecurityMode.Disabled, state.transportMode)
    }

    @Test
    fun changingPathClearsParsedPasswordAndRawString() {
        val state = ConnectionFormState()
        state.connectionString = "mysql://user:secret@localhost:3306/demo"
        state.applyParsedInput()

        state.resetToChoose()

        assertEquals("", state.password)
        assertEquals("", state.connectionString)
        assertFalse(state.parsedFromString)
    }

    @Test
    fun parsingSecondStringWithoutPasswordClearsPreviousPassword() {
        val state = ConnectionFormState()
        state.connectionString = "postgresql://user:secret@localhost:5432/demo"
        state.applyParsedInput()

        state.connectionString = "postgresql://user@localhost:5432/demo"
        state.applyParsedInput()

        assertEquals("", state.password)
    }

    @Test
    fun oracleWalletEditsMarkTransportManualAndSurviveHostBoundaryEdits() {
        val state = ConnectionFormState()
        state.connectionString = "jdbc:oracle:thin:user/secret@tcps:db.example.com:1522/service?wallet_location=/wallets/team"
        state.applyParsedInput()

        state.handleOracleWalletInput("/wallets/other")
        state.handleHostInput("localhost")

        assertTrue(state.transportOverridden)
        assertEquals("/wallets/other", state.oracleWalletLocation)
        assertEquals(TransportSecurityMode.VerifyIdentity, state.transportMode)
    }

    @Test
    fun caTroubleshootingSwitchesToVerifyCaAndPreservesPem() {
        val state = ConnectionFormState()

        state.applyTroubleshootingCa("-----BEGIN CERTIFICATE-----")

        assertEquals(TransportSecurityMode.VerifyCa, state.transportMode)
        assertEquals("-----BEGIN CERTIFICATE-----", state.caPem)
        assertTrue(state.transportOverridden)
    }

    @Test
    fun dialectSelectionKeepsAutomaticPortInSync() {
        val state = ConnectionFormState()

        state.selectDialect(Dialect.MySql)

        assertEquals(3306, state.port)
    }
}
