package com.safedb.service

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.secrets.CredentialStore
import com.safedb.secrets.SecretsManager
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SafeDbServiceImplTest {
    @BeforeTest
    fun setup() {
        SecretsManager.useStoreForTest(FailingDeleteStore())
    }

    @Test
    fun deleteConnectionRemovesProfileWhenCredentialDeleteFails() = runBlocking {
        val dir = Files.createTempDirectory("safedb-service-test")
        val configStore = ConfigStore.new(dir)
        val service = SafeDbServiceImpl(
            configStore = configStore,
            queryStore = QueryStore.new(dir),
            settingsStore = SettingsStore.new(dir),
        )
        configStore.save(sampleConnection())

        service.deleteConnection("c1")

        assertTrue(configStore.list().isEmpty())
    }
}

private fun sampleConnection() = ConnectionDef(
    id = "c1",
    name = "Delete me",
    dialect = Dialect.MySql,
    host = "localhost",
    port = 3306,
    database = "safedb_test",
    username = "testuser",
    transportSecurity = TransportSecurity(),
)

private class FailingDeleteStore : CredentialStore {
    override fun setPassword(service: String, account: String, password: String) = Unit
    override fun getPassword(service: String, account: String): String? = null
    override fun deletePassword(service: String, account: String) {
        throw IllegalStateException()
    }
    override fun vendor(): String = "failing-delete"
}
