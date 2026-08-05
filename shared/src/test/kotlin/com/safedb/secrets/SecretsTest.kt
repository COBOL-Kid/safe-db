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
    @BeforeTest
    fun setup() {
        SecretsManager.useStoreForTest(DisabledMemoryStore())
        SecretsManager.resetStoreReadCountForTest()
        CredentialSession.lockCredentials()
    }

    @Test
    fun disabledStoreRoundTrip() {
        val id = "conn-1"
        SecretsManager.savePassword(id, "secret").getOrThrow()
        SecretsManager.resetStoreReadCountForTest()

        val first = SecretsManager.getPassword(id).getOrThrow()
        assertEquals("secret", first)
        assertTrue(CredentialSession.containsForTest(id))
        assertEquals(0, SecretsManager.storeReadCountForTest)

        val second = SecretsManager.getPassword(id).getOrThrow()
        assertEquals("secret", second)
        assertEquals(0, SecretsManager.storeReadCountForTest)
    }

    @Test
    fun deleteClearsSessionAndStore() {
        val id = "conn-delete"
        SecretsManager.savePassword(id, "to-delete").getOrThrow()
        SecretsManager.getPassword(id).getOrThrow()
        assertTrue(CredentialSession.containsForTest(id))

        SecretsManager.deletePassword(id).getOrThrow()
        assertFalse(CredentialSession.containsForTest(id))
        assertNull(SecretsManager.getPassword(id).getOrThrow())
    }

    @Test
    fun emptyPasswordRoundTrips() {
        val id = "conn-empty"
        SecretsManager.savePassword(id, "").getOrThrow()
        val value = SecretsManager.passwordForConnection(id).getOrThrow()
        assertEquals("", value)
        assertTrue(CredentialSession.containsForTest(id))
    }

    @Test
    fun initDisabledBackendLabel() {
        SecretsManager.initStore("disabled")
        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun unsupportedPlatformsCannotSelectACredentialBackend() {
        val error = assertFailsWith<com.safedb.platform.UnsupportedDesktopPlatformException> {
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
        val id = "conn-lock"
        SecretsManager.savePassword(id, "secret").getOrThrow()
        assertTrue(CredentialSession.containsForTest(id))

        SecretsManager.lockCredentials()

        assertFalse(CredentialSession.containsForTest(id))
        assertEquals("secret", SecretsManager.getPassword(id).getOrThrow())
        assertTrue(CredentialSession.containsForTest(id))
    }

    @Test
    fun definitionsWithoutDriverPropertiesKeepTheReleasedCredentialFingerprint() {
        val store = DisabledMemoryStore()
        SecretsManager.useStoreForTest(store)
        val def = ConnectionDef(
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
