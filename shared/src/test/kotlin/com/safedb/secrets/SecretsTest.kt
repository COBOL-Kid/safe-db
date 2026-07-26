package com.safedb.secrets

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun lockingCredentialsClearsSessionAndAllowsStoreReload() {
        val id = "conn-lock"
        SecretsManager.savePassword(id, "secret").getOrThrow()
        assertTrue(CredentialSession.containsForTest(id))

        SecretsManager.lockCredentials()

        assertFalse(CredentialSession.containsForTest(id))
        assertEquals("secret", SecretsManager.getPassword(id).getOrThrow())
        assertTrue(CredentialSession.containsForTest(id))
    }
}
