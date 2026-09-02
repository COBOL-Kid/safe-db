package com.safedb.secrets

import com.safedb.adapter.SERVICE_NAME
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.persist.isPosix
import com.safedb.platform.DesktopPlatform
import com.safedb.platform.DesktopStoreUnavailableException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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
    fun linuxCannotSelectTheOsCredentialStore() {
        listOf("auto", "protected").forEach { backend ->
            val error =
                assertFailsWith<DesktopStoreUnavailableException> {
                    SecretsManager.initStoreForOsName(backend, "Linux")
                }

            assertEquals("OS credential store is not available on Linux", error.message)
        }
        assertNull(createStrictPlatformCredentialStoreOrNull(DesktopPlatform.Linux))
        val fallbackError =
            assertFailsWith<DesktopStoreUnavailableException> {
                PlatformCredentialStore.createOrFallback(DesktopPlatform.Linux)
            }
        assertEquals("OS credential store is not available on Linux", fallbackError.message)
    }

    @Test
    fun disabledBackendRemainsAvailableOnLinux() {
        SecretsManager.initStoreForOsName("disabled", "Linux")

        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun initFileStoreLocksDownPreexistingCredentialsDir() {
        val credentialsDir = Files.createTempDirectory("safedb-file-store")
        if (!isPosix(credentialsDir)) return
        Files.setPosixFilePermissions(credentialsDir, PosixFilePermissions.fromString("rwxr-xr-x"))

        SecretsManager.initFileStore(credentialsDir)

        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(credentialsDir),
        )
        assertEquals("file", SecretsManager.activeBackendLabel())
    }

    @Test
    fun fileStoreRoundTripBindsFingerprint() {
        val dir = Files.createTempDirectory("safedb-file-store")
        SecretsManager.initFileStore(dir.resolve("credentials"))
        val def = connectionDef("conn-file")

        SecretsManager.savePasswordForDefinition(def, "secret").getOrThrow()
        CredentialSession.lockCredentials()
        assertEquals("secret", SecretsManager.passwordForDefinition(def).getOrThrow())
        assertEquals("file", SecretsManager.activeBackendLabel())

        SecretsManager.deletePassword(def.id).getOrThrow()
        assertTrue(SecretsManager.passwordForDefinition(def).isFailure)
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
    fun passwordForDefinitionSurfacesStoreReadFailures() {
        SecretsManager.useStoreForTest(
            object : CredentialStore {
                override fun setPassword(service: String, account: String, password: String) = Unit

                override fun getPassword(service: String, account: String): String? {
                    throw IllegalStateException("credman exploded")
                }

                override fun deletePassword(service: String, account: String) = Unit

                override fun vendor(): String = "throwing"
            }
        )

        val result = SecretsManager.passwordForDefinition(connectionDef("conn-read-fail"))
        assertTrue(result.isFailure)
        assertEquals(
            "Could not read credentials (credman exploded).",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun saveErrorUnwrapsNestedCauses() {
        val nested = IllegalStateException("Error code 5")
        val wrapped = RuntimeException("wrapper", nested)
        assertEquals(
            "Could not store credentials (Error code 5).",
            SecretsManager.formatSaveCredentialError(wrapped),
        )
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
