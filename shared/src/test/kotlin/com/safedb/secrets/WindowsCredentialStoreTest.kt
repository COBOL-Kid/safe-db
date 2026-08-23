package com.safedb.secrets

import com.safedb.adapter.Adapter
import com.safedb.adapter.SERVICE_NAME
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.platform.DesktopPlatform
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WindowsCredentialStoreTest {
    private var connectionId: String? = null

    @AfterTest
    fun cleanup() {
        connectionId?.let { id -> runCatching { SecretsManager.deletePassword(id) } }
        connectionId = null
        CredentialSession.lockCredentials()
        SecretsManager.useStoreForTest(DisabledMemoryStore())
    }

    @Test
    fun platformStoreIsWindowsCredentialManager() {
        if (!runningOnWindows()) return
        SecretsManager.initStore(platform = DesktopPlatform.Windows)
        assertEquals("windows", SecretsManager.activeBackendLabel())
        val store = createJavaKeyringDelegateOrNull()
        assertNotNull(store, "java-keyring Windows backend should be available")
        assertEquals("java-keyring", store.vendor())
    }

    @Test
    fun boundPasswordRoundTripsThroughCredentialManager() {
        if (!runningOnWindows()) return
        SecretsManager.initStore(platform = DesktopPlatform.Windows)
        val def = mysqlDef(UUID.randomUUID().toString())
        connectionId = def.id

        SecretsManager.savePasswordForDefinition(def, "safedb").getOrThrow()
        CredentialSession.lockCredentials()

        assertEquals("safedb", SecretsManager.passwordForDefinition(def).getOrThrow())
        assertNotNull(createJavaKeyringDelegateOrNull()!!.getPassword(SERVICE_NAME, def.id))
    }

    @Test
    fun missingCredentialReadsAsAbsentRatherThanThrowing() {
        if (!runningOnWindows()) return
        val store = createJavaKeyringDelegateOrNull()
        assertNotNull(store)
        assertNull(store.getPassword(SERVICE_NAME, "missing-${UUID.randomUUID()}"))
        store.deletePassword(SERVICE_NAME, "missing-${UUID.randomUUID()}")
    }

    @Test
    fun missingPasswordIsAFailureNotAThrow() {
        if (!runningOnWindows()) return
        SecretsManager.initStore(platform = DesktopPlatform.Windows)
        val def = mysqlDef(UUID.randomUUID().toString())
        val result = SecretsManager.passwordForDefinition(def)
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("Password not found for this connection")
        )
    }

    @Test
    fun createMysqlConnectionThenReadsPasswordFromCredentialManager() = runBlocking {
        if (!runningOnWindows()) return@runBlocking
        SecretsManager.initStore(platform = DesktopPlatform.Windows)
        val candidate = reachableMysqlDef() ?: return@runBlocking
        val def = candidate
        connectionId = def.id
        val dir = Files.createTempDirectory("safedb-credman-mysql")
        val service =
            SafeDbServiceImpl(
                configStore = ConfigStore.new(dir),
                queryStore = QueryStore.new(dir),
                settingsStore = SettingsStore.new(dir),
            )

        service.createConnection(def, "safedb")
        CredentialSession.lockCredentials()

        assertEquals("safedb", SecretsManager.passwordForDefinition(def).getOrThrow())
        val schema = service.getSchema(def.id)
        assertTrue(schema.tables.isNotEmpty(), "expected tables after saving the MySQL connection")
        service.deleteConnection(def.id)
        connectionId = null
    }

    private fun reachableMysqlDef(): ConnectionDef? {
        for (database in listOf("safedb_test", "safedb")) {
            val def = mysqlDef(UUID.randomUUID().toString(), database)
            val reachable = runBlocking {
                runCatching {
                        val adapter = Adapter.connect(def, "safedb")
                        try {
                            adapter.test()
                            true
                        } finally {
                            adapter.close()
                        }
                    }
                    .getOrDefault(false)
            }
            if (reachable) return def
        }
        return null
    }

    private fun mysqlDef(id: String, database: String = "safedb_test") =
        ConnectionDef(
            id = id,
            name = "Windows CredMan MySQL",
            dialect = Dialect.MySql,
            host = "127.0.0.1",
            port = 3306,
            database = database,
            username = "safedb",
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
        )

    private fun runningOnWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
