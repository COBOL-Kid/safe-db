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
        assertFailsWith<IllegalArgumentException> { base.copy(database = " ").validate().getOrThrow() }
        assertFailsWith<IllegalArgumentException> { base.copy(username = "").validate().getOrThrow() }
        assertFailsWith<IllegalArgumentException> { base.copy(port = 0).validate().getOrThrow() }
    }

    @Test
    fun validateRequiresOracleWalletForVerifiedTransport() {
        val def = sampleConnection().copy(
            dialect = Dialect.Oracle,
            transportSecurity = TransportSecurity(mode = TransportSecurityMode.VerifyIdentity),
        )
        assertFailsWith<IllegalArgumentException> { def.validate().getOrThrow() }
        assertTrue(
            def.copy(
                transportSecurity = TransportSecurity(
                    mode = TransportSecurityMode.VerifyIdentity,
                    oracleWalletLocation = "/wallet",
                ),
            ).validate().isSuccess,
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
}

private fun sampleConnection() = ConnectionDef(
    id = "c1",
    name = "Test",
    dialect = Dialect.Postgres,
    host = "localhost",
    port = 5432,
    database = "demo",
    username = "readonly",
    transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
)
