package com.safedb.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypesTest {
    @Test
    fun validateRejectsBlankFields() {
        val base = sampleConnection()
        assertFailsWith<IllegalArgumentException> { base.copy(id = " ").validate().getOrThrow() }
        assertFailsWith<IllegalArgumentException> { base.copy(host = "").validate().getOrThrow() }
        assertFailsWith<IllegalArgumentException> {
            base.copy(database = " ").validate().getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(username = "").validate().getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> { base.copy(port = 0).validate().getOrThrow() }
    }

    @Test
    fun validateRequiresOracleWalletForVerifiedTransport() {
        val def =
            sampleConnection()
                .copy(
                    dialect = Dialect.Oracle,
                    transportSecurity =
                        TransportSecurity(mode = TransportSecurityMode.VerifyIdentity),
                )
        assertFailsWith<IllegalArgumentException> { def.validate().getOrThrow() }
        assertTrue(
            def.copy(
                    transportSecurity =
                        TransportSecurity(
                            mode = TransportSecurityMode.VerifyIdentity,
                            oracleWalletLocation = "/wallet",
                        )
                )
                .validate()
                .isSuccess
        )
    }

    @Test
    fun credentialFingerprintChangesWhenEndpointChanges() {
        val base = sampleConnection()
        val otherHost = base.copy(host = "db.example.com")
        assertNotEquals(base.credentialFingerprint(), otherHost.credentialFingerprint())
    }

    @Test
    fun credentialFingerprintStableForSameMaterial() {
        val first = sampleConnection().credentialFingerprint()
        val second = sampleConnection().credentialFingerprint()
        assertEquals(first, second)
    }

    @Test
    fun driverPropertiesAreValidatedAndCannotStoreSecretsOrManagedSettings() {
        val base = sampleConnection()

        assertFailsWith<IllegalArgumentException> {
            base
                .copy(driverProperties = listOf(DriverProperty("accessToken", "secret")))
                .validate()
                .getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            base
                .copy(
                    dialect = Dialect.Mssql,
                    driverProperties =
                        listOf(DriverProperty("keyVaultProviderClientKey", "client-secret")),
                )
                .validate()
                .getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            base
                .copy(driverProperties = listOf(DriverProperty("sslMode", "disable")))
                .validate()
                .getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            base
                .copy(
                    driverProperties =
                        listOf(
                            DriverProperty("currentSchema", "a"),
                            DriverProperty("CURRENTSCHEMA", "b"),
                        )
                )
                .validate()
                .getOrThrow()
        }
        assertTrue(
            base
                .copy(driverProperties = listOf(DriverProperty("currentSchema", "")))
                .validate()
                .isSuccess
        )
        assertTrue(
            base
                .copy(
                    dialect = Dialect.Mssql,
                    driverProperties =
                        listOf(DriverProperty("clientKey", "/certificates/client-key.pem")),
                )
                .validate()
                .isSuccess
        )
    }

    @Test
    fun managedTlsFactoryAndOracleDnPropertiesCannotBeSaved() {
        assertFailsWith<IllegalArgumentException> {
            sampleConnection()
                .copy(driverProperties = listOf(DriverProperty("SSL_FACTORY", "custom.Factory")))
                .validate()
                .getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            sampleConnection()
                .copy(
                    dialect = Dialect.Oracle,
                    transportSecurity =
                        TransportSecurity(
                            TransportSecurityMode.VerifyIdentity,
                            oracleWalletLocation = "/wallet",
                        ),
                    driverProperties =
                        listOf(DriverProperty("oracle.net.ssl_server_dn_match", "false")),
                )
                .validate()
                .getOrThrow()
        }
    }

    @Test
    fun driverPropertyOrderDoesNotChangeFingerprintButValuesDo() {
        val base = sampleConnection()
        val first =
            base.copy(
                driverProperties =
                    listOf(
                        DriverProperty("currentSchema", "reporting"),
                        DriverProperty("ApplicationName", "Safe-DB"),
                    )
            )
        val reordered = first.copy(driverProperties = first.driverProperties.reversed())
        val changed =
            first.copy(driverProperties = listOf(DriverProperty("currentSchema", "analytics")))

        assertEquals(first.credentialFingerprint(), reordered.credentialFingerprint())
        assertNotEquals(first.credentialFingerprint(), changed.credentialFingerprint())
    }
}

private fun sampleConnection() =
    ConnectionDef(
        id = "c1",
        name = "Test",
        dialect = Dialect.Postgres,
        host = "localhost",
        port = 5432,
        database = "demo",
        username = "readonly",
        transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
    )
