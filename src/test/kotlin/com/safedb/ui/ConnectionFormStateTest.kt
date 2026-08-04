package com.safedb.ui

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionFormStateTest {
    @Test
    fun newFormUsesStableIdAndLocalTransportDefaults() {
        val state = ConnectionFormState()
        val first = state.buildDef()
        val second = state.buildDef()

        assertEquals(first.id, second.id)
        assertEquals(TransportSecurityMode.Disabled, state.transportMode)
    }

    @Test
    fun remoteHostAutomaticallyUsesVerifiedTransportUntilManuallyOverridden() {
        val state = ConnectionFormState()

        state.handleHostInput("db.example.com")
        assertEquals(TransportSecurityMode.VerifyIdentity, state.transportMode)

        state.changeTransportMode(TransportSecurityMode.EncryptOnly)
        state.handleHostInput("localhost")
        assertEquals(TransportSecurityMode.EncryptOnly, state.transportMode)
    }

    @Test
    fun applyConnectionStringPopulatesFieldsAndSafeDriverProperties() {
        val state = ConnectionFormState()
        state.connectionString =
            "postgresql://user:secret@db.example.com:5432/app?sslmode=verify-full&currentSchema=reporting"

        state.applyParsedInput()

        assertEquals("db.example.com", state.host)
        assertEquals("app", state.database)
        assertEquals("user", state.username)
        assertEquals("secret", state.password)
        assertEquals("currentSchema", state.driverProperties.single().name)
        assertEquals("reporting", state.driverProperties.single().value)
        assertFalse(state.connectionString.contains("secret"))
    }

    @Test
    fun editFormPreservesIdAndNeverPreloadsPassword() {
        val original = sampleConnection()
        val state = ConnectionFormState(original)

        assertEquals(original.id, state.buildDef().id)
        assertEquals("", state.password)
        assertFalse(state.passwordChangeEnabled)
        assertNull(state.passwordForOperation())
        assertTrue(state.connectionString.contains("readonly@"))
    }

    @Test
    fun nameOnlyEditKeepsSavedPassword() {
        val state = ConnectionFormState(sampleConnection())

        state.updateName("Renamed")

        assertFalse(state.credentialMaterialChanged())
        assertNull(state.validateForm())
        assertNull(state.passwordForOperation())
    }

    @Test
    fun endpointOrPropertyEditRequiresExplicitPasswordChange() {
        val state = ConnectionFormState(sampleConnection())
        state.handleHostInput("other.example.com")

        assertTrue(state.credentialMaterialChanged())
        assertTrue(state.validateForm()!!.contains("password"))

        state.enablePasswordChange()
        assertNull(state.validateForm())
        assertEquals("", state.passwordForOperation())
    }

    @Test
    fun driverPropertyOperationsAffectBuiltDefinitionAndFingerprint() {
        val state = ConnectionFormState(sampleConnection())
        val originalFingerprint = state.original!!.credentialFingerprint()

        state.addDriverProperty()
        state.updateDriverPropertyName(1, "currentSchema")
        state.updateDriverPropertyValue(1, "analytics")
        state.enablePasswordChange()

        assertEquals(2, state.buildDef().driverProperties.size)
        assertNotEquals(originalFingerprint, state.buildDef().credentialFingerprint())

        state.removeDriverProperty(1)
        assertEquals(1, state.buildDef().driverProperties.size)
    }

    @Test
    fun dialectSelectionKeepsAutomaticPortInSync() {
        val state = ConnectionFormState()

        state.selectDialect(Dialect.MySql)

        assertEquals(3306, state.port)
    }

    @Test
    fun manuallyEnteredPortSurvivesDialectChange() {
        val state = ConnectionFormState()
        state.handlePortInput("15432")

        state.selectDialect(Dialect.MySql)

        assertEquals(15432, state.port)
    }

    @Test
    fun applyingStringWithoutPasswordClearsNewConnectionPassword() {
        val state = ConnectionFormState()
        state.updatePassword("old")
        state.connectionString = "postgresql://user@localhost/db"

        state.applyParsedInput()

        assertEquals("", state.password)
        assertTrue(state.passwordChangeEnabled)
    }

    @Test
    fun applyingPasswordFreeStringToEditDoesNotPretendCredentialWasLoaded() {
        val state = ConnectionFormState(sampleConnection())
        state.connectionString = "postgresql://readonly@db.example.com/app"

        state.applyParsedInput()

        assertFalse(state.passwordChangeEnabled)
        assertNull(state.passwordForOperation())
    }

    @Test
    fun applyingGeneratedOracleStringKeepsTheSavedUsernameAndPassword() {
        val original = sampleConnection().copy(
            dialect = Dialect.Oracle,
            port = 1521,
            database = "service",
            transportSecurity = TransportSecurity(TransportSecurityMode.Disabled),
            driverProperties = emptyList(),
        )
        val state = ConnectionFormState(original)

        state.applyParsedInput()

        assertEquals("readonly", state.username)
        assertFalse(state.passwordChangeEnabled)
        assertNull(state.passwordForOperation())
        assertFalse(state.credentialMaterialChanged())
        assertNull(state.validateForm())
    }

    @Test
    fun duplicateDriverPropertyNamesFailValidationIgnoringCase() {
        val state = ConnectionFormState()
        state.updateDatabase("app")
        state.updateUsername("readonly")
        state.addDriverProperty()
        state.updateDriverPropertyName(0, "currentSchema")
        state.addDriverProperty()
        state.updateDriverPropertyName(1, "CURRENTSCHEMA")

        assertTrue(state.validateForm()!!.contains("unique"))
    }

    @Test
    fun secretLikeDriverPropertyFailsValidation() {
        val state = ConnectionFormState()
        state.updateDatabase("app")
        state.updateUsername("readonly")
        state.addDriverProperty()
        state.updateDriverPropertyName(0, "clientSecret")

        assertTrue(state.validateForm()!!.contains("secret"))
    }

    @Test
    fun oracleVerifiedTransportRequiresWallet() {
        val state = ConnectionFormState()
        state.selectDialect(Dialect.Oracle)
        state.updateDatabase("service")
        state.updateUsername("readonly")
        state.changeTransportMode(TransportSecurityMode.VerifyIdentity)

        assertTrue(state.validateForm()!!.contains("wallet"))

        state.updateOracleWallet("/wallet")
        assertNull(state.validateForm())
    }

    private fun sampleConnection() = ConnectionDef(
        id = "c1",
        name = "Production Replica",
        dialect = Dialect.Postgres,
        host = "db.example.com",
        port = 5432,
        database = "app",
        username = "readonly",
        transportSecurity = TransportSecurity(TransportSecurityMode.VerifyIdentity),
        driverProperties = listOf(DriverProperty("applicationName", "Safe-DB")),
    )
}
