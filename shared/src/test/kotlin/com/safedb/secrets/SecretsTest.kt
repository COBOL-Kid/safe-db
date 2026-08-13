package com.safedb.secrets

import com.safedb.adapter.SERVICE_NAME
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretsTest {
    private lateinit var store: DisabledMemoryStore

    @BeforeTest
    fun setup() {
        store = DisabledMemoryStore()
        SecretsManager.useStoreForTest(store)
        SecretsManager.resetStoreReadCountForTest()
        CredentialSession.lockCredentials()
    }

    private fun connectionDef(id: String) =
        ConnectionDef(
            id = id,
            name = "Test connection",
            dialect = Dialect.Postgres,
            host = "localhost",
            port = 5432,
            database = "demo",
            username = "readonly",
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
        )

    private fun sessionKey(def: ConnectionDef) = "${def.id}:${def.credentialFingerprint()}"

    @Test
    fun disabledStoreRoundTrip() {
        val def = connectionDef("conn-1")
        SecretsManager.savePasswordForDefinition(def, "secret").getOrThrow()
        SecretsManager.resetStoreReadCountForTest()

        val first = SecretsManager.passwordForDefinition(def).getOrThrow()
        assertEquals("secret", first)
        assertTrue(CredentialSession.containsForTest(sessionKey(def)))
        assertEquals(0, SecretsManager.storeReadCountForTest)

        val second = SecretsManager.passwordForDefinition(def).getOrThrow()
        assertEquals("secret", second)
        assertEquals(0, SecretsManager.storeReadCountForTest)
    }

    @Test
    fun deleteClearsSessionAndStore() {
        val def = connectionDef("conn-delete")
        SecretsManager.savePasswordForDefinition(def, "to-delete").getOrThrow()
        assertTrue(CredentialSession.containsForTest(sessionKey(def)))

        SecretsManager.deletePassword(def.id).getOrThrow()
        assertFalse(CredentialSession.containsForTest(sessionKey(def)))
        assertNull(store.getPassword(SERVICE_NAME, def.id))
        assertTrue(SecretsManager.passwordForDefinition(def).isFailure)
    }

    @Test
    fun emptyPasswordRoundTrips() {
        val def = connectionDef("conn-empty")
        SecretsManager.savePasswordForDefinition(def, "").getOrThrow()
        CredentialSession.lockCredentials()

        assertEquals("", SecretsManager.passwordForDefinition(def).getOrThrow())
        assertTrue(CredentialSession.containsForTest(sessionKey(def)))
    }

    @Test
    fun initDisabledBackendLabel() {
        SecretsManager.initStore("disabled")
        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun unsupportedPlatformsCannotSelectACredentialBackend() {
        val error =
            assertFailsWith<com.safedb.platform.UnsupportedDesktopPlatformException> {
                SecretsManager.initStoreForOsName("auto", "Linux")
            }

        assertEquals(
            "unsupported operating system 'Linux'; supported platforms are macOS and Windows",
            error.message,
        )
    }

    @Test
    fun disabledBackendRemainsAvailableOnUnsupportedBuildHosts() {
        SecretsManager.initStoreForOsName("disabled", "Linux")

        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun lockingCredentialsClearsSessionAndAllowsStoreReload() {
        val def = connectionDef("conn-lock")
        SecretsManager.savePasswordForDefinition(def, "secret").getOrThrow()
        assertTrue(CredentialSession.containsForTest(sessionKey(def)))

        SecretsManager.lockCredentials()

        assertFalse(CredentialSession.containsForTest(sessionKey(def)))
        assertEquals("secret", SecretsManager.passwordForDefinition(def).getOrThrow())
        assertTrue(CredentialSession.containsForTest(sessionKey(def)))
    }

    @Test
    fun definitionsWithoutDriverPropertiesKeepTheReleasedCredentialFingerprint() {
        val def =
            ConnectionDef(
                id = "legacy-bound",
                name = "Existing connection",
                dialect = Dialect.Postgres,
                host = "localhost",
                port = 5432,
                database = "demo",
                username = "readonly",
                transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
            )
        store.setPassword(
            SERVICE_NAME,
            def.id,
            """{"version":1,"fingerprint":"47fb4c855ff6cdfd0c27ba503e4065d373ba402cfc96f402d4110e68cc2cc9fd","password":"stored-secret"}""",
        )

        assertEquals("stored-secret", SecretsManager.passwordForDefinition(def).getOrThrow())
    }
}
