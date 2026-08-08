package com.safedb.integration

import com.safedb.adapter.Adapter
import com.safedb.adapter.buildJdbcUrl
import com.safedb.adapter.createDataSourceConfig
import com.safedb.launch.LaunchProfileBootstrap
import com.safedb.launch.POSTGRES_LAUNCH_ROOT_CERT_PROPERTY
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.model.validateDriverProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Live SSL/TLS compatibility checks for all four dialects.
 *
 * Enable with SAFEDB_TEST_REQUIRE_SSL=true and the SAFEDB_TEST_*_SSL_* endpoints prepared by
 * scripts/verify_ssl_compat.sh.
 */
@Tag("integration")
class SslCompatIntegrationTest {
    private val env: Map<String, String> = System.getenv()

    private val requireSsl: Boolean
        get() = env["SAFEDB_TEST_REQUIRE_SSL"].equals("true", ignoreCase = true)

    private val profilePath: Path?
        get() = env["SAFEDB_TEST_SSL_LAUNCH_PROFILE"]?.let(Path::of)

    private val wrongProfilePath: Path?
        get() = env["SAFEDB_TEST_SSL_WRONG_LAUNCH_PROFILE"]?.let(Path::of)

    private val oracleWallet: String?
        get() = env["SAFEDB_TEST_ORACLE_WALLET"]

    @Test
    fun launchProfileAppliesJsseAndPostgresRootCertWithoutLeakingPassword() {
        assumeSslHarness()
        val profile = requireNotNull(profilePath)
        assumeTrue(Files.isRegularFile(profile), "launch profile missing: $profile")

        val properties = mutableMapOf<String, String>()
        assertTrue(
            LaunchProfileBootstrap.configure(
                args = arrayOf("--launch-profile", profile.toString()),
                credentialStoreFactory = { null },
                propertySetter = properties::put,
            )
        )
        assertEquals("PKCS12", properties["javax.net.ssl.trustStoreType"])
        assertNotNull(properties["javax.net.ssl.trustStore"])
        assertNotNull(properties["javax.net.ssl.trustStorePassword"])
        val pem = Path.of(properties.getValue(POSTGRES_LAUNCH_ROOT_CERT_PROPERTY))
        assertTrue(Files.isRegularFile(pem))
        assertTrue(Files.readString(pem).contains("BEGIN CERTIFICATE"))
        // Password must never appear in the profile JSON itself.
        assertFalse(
            Files.readString(profile)
                .contains(properties.getValue("javax.net.ssl.trustStorePassword"))
        )
        Files.deleteIfExists(pem)
    }

    @Test
    fun reservedTlsDriverPropertiesAreBlockedForEveryDialect() {
        val cases =
            mapOf(
                Dialect.Postgres to
                    listOf("sslmode", "sslrootcert", "sslfactory", "sslcert", "sslkey"),
                Dialect.MySql to
                    listOf(
                        "sslMode",
                        "trustCertificateKeyStoreUrl",
                        "trustCertificateKeyStoreType",
                        "allowPublicKeyRetrieval",
                    ),
                Dialect.Mssql to
                    listOf(
                        "encrypt",
                        "trustServerCertificate",
                        "hostNameInCertificate",
                        "trustStore",
                    ),
                Dialect.Oracle to
                    listOf(
                        "wallet_location",
                        "oracle.net.wallet_location",
                        "oracle.net.ssl_server_dn_match",
                    ),
            )
        cases.forEach { (dialect, names) ->
            names.forEach { name ->
                val result = validateDriverProperties(dialect, listOf(DriverProperty(name, "x")))
                assertTrue(result.isFailure, "expected $dialect/$name to be reserved")
            }
        }
    }

    @Test
    fun jdbcUrlsAndDatasourcePropertiesCoverAllTransportModes() {
        for (mode in TransportSecurityMode.entries) {
            assertTrue(buildJdbcUrl(mysql(mode)).contains("sslMode="))
            assertTrue(buildJdbcUrl(postgres(mode)).contains("sslmode="))
            assertTrue(buildJdbcUrl(mssql(mode)).startsWith("jdbc:sqlserver://"))
            if (mode == TransportSecurityMode.Disabled) {
                assertTrue(buildJdbcUrl(oracle(mode)).contains("@//"))
            } else {
                val wallet = oracle(mode, wallet = "/tmp/wallet")
                assertTrue(buildJdbcUrl(wallet).contains("@tcps://"))
                assertTrue(buildJdbcUrl(wallet).contains("wallet_location="))
            }
        }

        val mssqlVerified =
            createDataSourceConfig(mssql(TransportSecurityMode.VerifyIdentity), "pw")
                .dataSourceProperties
        assertEquals("true", mssqlVerified.getProperty("encrypt"))
        assertEquals("false", mssqlVerified.getProperty("trustServerCertificate"))
        assertEquals("localhost", mssqlVerified.getProperty("hostNameInCertificate"))
        assertNull(mssqlVerified.getProperty("trustStore"))

        val oracleIdentity =
            createDataSourceConfig(oracle(TransportSecurityMode.VerifyIdentity, "/wallet"), "pw")
                .dataSourceProperties
        assertEquals("true", oracleIdentity.getProperty("oracle.net.ssl_server_dn_match"))
        assertEquals("/wallet", oracleIdentity.getProperty("oracle.net.wallet_location"))
    }

    @Test
    fun mysqlLiveSslModesRespectLaunchProfileTrust() {
        assumeSslHarness()
        assumeMysql()
        runBlocking {
            applyLaunchProfile(requireNotNull(profilePath))

            connectAndTest(mysql(TransportSecurityMode.EncryptOnly), mysqlPassword())
            connectAndTest(mysql(TransportSecurityMode.VerifyCa), mysqlPassword())
            connectAndTest(mysql(TransportSecurityMode.VerifyIdentity), mysqlPassword())

            // Disabled cannot satisfy require_secure_transport servers.
            assertFails { connectAndTest(mysql(TransportSecurityMode.Disabled), mysqlPassword()) }

            applyLaunchProfile(requireNotNull(wrongProfilePath))
            assertFails {
                connectAndTest(mysql(TransportSecurityMode.VerifyIdentity), mysqlPassword())
            }
        }
    }

    @Test
    fun postgresLiveSslModesUseLaunchProfileRootCert() {
        assumeSslHarness()
        assumePostgres()
        runBlocking {
            applyLaunchProfile(requireNotNull(profilePath))

            connectAndTest(postgres(TransportSecurityMode.EncryptOnly), postgresPassword())
            connectAndTest(postgres(TransportSecurityMode.VerifyCa), postgresPassword())
            connectAndTest(postgres(TransportSecurityMode.VerifyIdentity), postgresPassword())

            applyLaunchProfile(requireNotNull(wrongProfilePath))
            assertFails {
                connectAndTest(postgres(TransportSecurityMode.VerifyIdentity), postgresPassword())
            }
        }
    }

    @Test
    fun mssqlLiveSslModesUseJvmTrustFromLaunchProfile() {
        assumeSslHarness()
        assumeMssql()
        runBlocking {
            applyLaunchProfile(requireNotNull(profilePath))

            connectAndTest(mssql(TransportSecurityMode.EncryptOnly), mssqlPassword())
            connectAndTest(mssql(TransportSecurityMode.VerifyCa), mssqlPassword())
            connectAndTest(mssql(TransportSecurityMode.VerifyIdentity), mssqlPassword())

            applyLaunchProfile(requireNotNull(wrongProfilePath))
            assertFails {
                connectAndTest(mssql(TransportSecurityMode.VerifyIdentity), mssqlPassword())
            }
        }
    }

    @Test
    fun oracleLiveTcpWorksAndTcpsRequiresWalletConfiguration() {
        assumeSslHarness()
        assumeOracle()
        runBlocking {
            connectAndTest(oracle(TransportSecurityMode.Disabled), oraclePassword())

            val missingWallet = oracle(TransportSecurityMode.VerifyIdentity, wallet = null)
            assertTrue(missingWallet.validate().isFailure)

            val walletPath = oracleWallet
            if (!walletPath.isNullOrBlank() && Files.isDirectory(Path.of(walletPath))) {
                applyLaunchProfile(requireNotNull(profilePath))
                // Live TCPS is optional: only attempted when a wallet directory and TCPS listener
                // are
                // provisioned. A PKCS12 placeholder is not an Oracle wallet.
                val def = oracle(TransportSecurityMode.VerifyIdentity, wallet = walletPath)
                val result = runCatching { connectAndTest(def, oraclePassword()) }
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message.orEmpty()
                    assertTrue(
                        message.contains("key store", ignoreCase = true) ||
                            message.contains("wallet", ignoreCase = true) ||
                            message.contains("TCPS", ignoreCase = true) ||
                            message.contains("ORA-", ignoreCase = true),
                        "unexpected Oracle TCPS failure: $message",
                    )
                }
            }
        }
    }

    private suspend fun connectAndTest(def: ConnectionDef, password: String) {
        val adapter = Adapter.connect(def, password)
        try {
            assertTrue(
                adapter.test().isNotBlank(),
                "test() returned blank for ${def.dialect}/${def.transportSecurity.mode}",
            )
        } finally {
            adapter.close()
        }
    }

    private fun applyLaunchProfile(profile: Path) {
        LaunchProfileBootstrap.configure(arrayOf("--launch-profile", profile.toString()))
    }

    private fun assumeSslHarness() {
        if (requireSsl) {
            check(profilePath != null && Files.isRegularFile(profilePath!!)) {
                "SAFEDB_TEST_SSL_LAUNCH_PROFILE must point to a readable launch profile"
            }
        } else {
            assumeTrue(
                profilePath != null && Files.isRegularFile(profilePath!!),
                "SSL harness not configured; set SAFEDB_TEST_REQUIRE_SSL=true to require it",
            )
        }
    }

    private fun assumeMysql() =
        assumeEndpoint(
            "SAFEDB_TEST_MYSQL_SSL_HOST",
            "SAFEDB_TEST_MYSQL_SSL_PORT",
            defaultPort = 3307,
        )

    private fun assumePostgres() =
        assumeEndpoint(
            "SAFEDB_TEST_POSTGRES_SSL_HOST",
            "SAFEDB_TEST_POSTGRES_SSL_PORT",
            defaultPort = 5433,
        )

    private fun assumeMssql() =
        assumeEndpoint(
            "SAFEDB_TEST_MSSQL_SSL_HOST",
            "SAFEDB_TEST_MSSQL_SSL_PORT",
            defaultPort = 14333,
        )

    private fun assumeOracle() =
        assumeEndpoint(
            "SAFEDB_TEST_ORACLE_SSL_HOST",
            "SAFEDB_TEST_ORACLE_SSL_PORT",
            defaultPort = 1522,
        )

    private fun assumeEndpoint(hostKey: String, portKey: String, defaultPort: Int) {
        val host = env[hostKey] ?: "127.0.0.1"
        val port = env[portKey]?.toIntOrNull() ?: defaultPort
        val open = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 2_000)
            }
            true
        }
            .getOrDefault(false)
        if (requireSsl) {
            check(open) { "$hostKey:$portKey endpoint $host:$port is not reachable" }
        } else {
            assumeTrue(open, "$host:$port not reachable")
        }
    }

    private fun mysql(mode: TransportSecurityMode) =
        ConnectionDef(
            id = "ssl-mysql",
            name = "SSL MySQL",
            dialect = Dialect.MySql,
            host = env["SAFEDB_TEST_MYSQL_SSL_HOST"] ?: "127.0.0.1",
            port = env["SAFEDB_TEST_MYSQL_SSL_PORT"]?.toIntOrNull() ?: 3307,
            database = env["SAFEDB_TEST_MYSQL_SSL_DATABASE"] ?: "safedb_ssl",
            username = env["SAFEDB_TEST_MYSQL_SSL_USER"] ?: "safedb",
            transportSecurity = TransportSecurity(mode = mode),
        )

    private fun postgres(mode: TransportSecurityMode) =
        ConnectionDef(
            id = "ssl-postgres",
            name = "SSL PostgreSQL",
            dialect = Dialect.Postgres,
            host = env["SAFEDB_TEST_POSTGRES_SSL_HOST"] ?: "127.0.0.1",
            port = env["SAFEDB_TEST_POSTGRES_SSL_PORT"]?.toIntOrNull() ?: 5433,
            database = env["SAFEDB_TEST_POSTGRES_SSL_DATABASE"] ?: "safedb_ssl",
            username = env["SAFEDB_TEST_POSTGRES_SSL_USER"] ?: "postgres",
            transportSecurity = TransportSecurity(mode = mode),
        )

    private fun mssql(mode: TransportSecurityMode) =
        ConnectionDef(
            id = "ssl-mssql",
            name = "SSL SQL Server",
            dialect = Dialect.Mssql,
            host = env["SAFEDB_TEST_MSSQL_SSL_HOST"] ?: "127.0.0.1",
            port = env["SAFEDB_TEST_MSSQL_SSL_PORT"]?.toIntOrNull() ?: 14333,
            database = env["SAFEDB_TEST_MSSQL_SSL_DATABASE"] ?: "safedb_ssl",
            username = env["SAFEDB_TEST_MSSQL_SSL_USER"] ?: "sa",
            transportSecurity = TransportSecurity(mode = mode),
        )

    private fun oracle(mode: TransportSecurityMode, wallet: String? = null) =
        ConnectionDef(
            id = "ssl-oracle",
            name = "SSL Oracle",
            dialect = Dialect.Oracle,
            host = env["SAFEDB_TEST_ORACLE_SSL_HOST"] ?: "127.0.0.1",
            port = env["SAFEDB_TEST_ORACLE_SSL_PORT"]?.toIntOrNull() ?: 1522,
            database = env["SAFEDB_TEST_ORACLE_SSL_DATABASE"] ?: "FREEPDB1",
            username = env["SAFEDB_TEST_ORACLE_SSL_USER"] ?: "safedb",
            transportSecurity = TransportSecurity(mode = mode, oracleWalletLocation = wallet),
        )

    private fun mysqlPassword() = env["SAFEDB_TEST_MYSQL_SSL_PASSWORD"] ?: "safedb"

    private fun postgresPassword() = env["SAFEDB_TEST_POSTGRES_SSL_PASSWORD"] ?: "postgres"

    private fun mssqlPassword() = env["SAFEDB_TEST_MSSQL_SSL_PASSWORD"] ?: "SafeDb_Ssl_Passw0rd!"

    private fun oraclePassword() = env["SAFEDB_TEST_ORACLE_SSL_PASSWORD"] ?: "safedb"
}
