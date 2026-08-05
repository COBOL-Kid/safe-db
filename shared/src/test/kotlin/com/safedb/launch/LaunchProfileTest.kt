package com.safedb.launch

import com.safedb.secrets.CredentialStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchProfileTest {
    @Test
    fun noProfileLeavesJvmTrustConfigurationUntouched() {
        val properties = mutableMapOf<String, String>()

        val configured = configure(emptyArray(), properties = properties)

        assertFalse(configured)
        assertTrue(properties.isEmpty())
    }

    @Test
    fun passwordFileProfileValidatesStoreAndAppliesJvmProperties() {
        val directory = Files.createTempDirectory("safedb-launch-profile")
        val password = "  trust password  "
        val passwordFile = directory.resolve("password.txt").apply { writeText("$password\r\n") }
        val trustStore =
            createTrustStore(directory.resolve("roots.p12"), password, withCertificate = true)
        val profile =
            writeProfile(
                directory,
                trustStore,
                """{"source":"file","path":"${jsonPath(passwordFile)}"}""",
            )
        val properties = mutableMapOf<String, String>()

        assertTrue(
            configure(arrayOf("--launch-profile", profile.toString()), properties = properties)
        )
        assertEquals(trustStore.toString(), properties["javax.net.ssl.trustStore"])
        assertEquals("PKCS12", properties["javax.net.ssl.trustStoreType"])
        assertEquals(password, properties["javax.net.ssl.trustStorePassword"])
        val postgresRootCert = Path.of(properties.getValue(POSTGRES_LAUNCH_ROOT_CERT_PROPERTY))
        assertTrue(Files.isRegularFile(postgresRootCert))
        assertTrue(Files.readString(postgresRootCert).contains("-----BEGIN CERTIFICATE-----"))
        Files.newInputStream(postgresRootCert).use {
            assertEquals(1, CertificateFactory.getInstance("X.509").generateCertificates(it).size)
        }
        Files.deleteIfExists(postgresRootCert)
    }

    @Test
    fun credentialStoreProfileUsesFixedServiceAndReference() {
        val directory = Files.createTempDirectory("safedb-launch-profile")
        val trustStore =
            createTrustStore(directory.resolve("roots.p12"), "vault-secret", withCertificate = true)
        val profile =
            writeProfile(
                directory,
                trustStore,
                """{"source":"credentialStore","reference":"production-roots"}""",
            )
        val calls = mutableListOf<Pair<String, String>>()
        val store =
            object : CredentialStore {
                override fun getPassword(service: String, account: String): String? {
                    calls += service to account
                    return "vault-secret"
                }

                override fun setPassword(service: String, account: String, password: String) = Unit

                override fun deletePassword(service: String, account: String) = Unit

                override fun vendor(): String = "test"
            }

        assertTrue(configure(arrayOf("--launch-profile", profile.toString()), store = store))
        assertEquals(listOf(TRUST_STORE_CREDENTIAL_SERVICE to "production-roots"), calls)
    }

    @Test
    fun credentialStoreNeverFallsBackWhenBackendOrEntryIsMissing() {
        val directory = Files.createTempDirectory("safedb-launch-profile")
        val trustStore =
            createTrustStore(directory.resolve("roots.p12"), "secret", withCertificate = true)
        val profile =
            writeProfile(
                directory,
                trustStore,
                """{"source":"credentialStore","reference":"missing"}""",
            )

        assertTrue(
            assertFailsWith<LaunchProfileException> {
                    configure(arrayOf("--launch-profile", profile.toString()), store = null)
                }
                .message!!
                .contains("unavailable")
        )
        val emptyStore =
            object : CredentialStore {
                override fun getPassword(service: String, account: String): String? = null

                override fun setPassword(service: String, account: String, password: String) = Unit

                override fun deletePassword(service: String, account: String) = Unit

                override fun vendor(): String = "test"
            }
        assertTrue(
            assertFailsWith<LaunchProfileException> {
                    configure(arrayOf("--launch-profile", profile.toString()), store = emptyStore)
                }
                .message!!
                .contains("not found")
        )
    }

    @Test
    fun profileSchemaAndPasswordSourceAreStrict() {
        val directory = Files.createTempDirectory("safedb-launch-profile")
        val trustStore =
            createTrustStore(directory.resolve("roots.p12"), "secret", withCertificate = true)
        val unknownField =
            directory.resolve("unknown.json").apply {
                writeText(
                    """{"schemaVersion":1,"extra":true,"trustStore":{"type":"PKCS12","path":"${jsonPath(trustStore)}","password":{"source":"file","path":"x"}}}"""
                )
            }
        assertFailsWith<LaunchProfileException> {
            configure(arrayOf("--launch-profile", unknownField.toString()))
        }

        val mixedSource =
            writeProfile(
                directory,
                trustStore,
                """{"source":"credentialStore","reference":"roots","path":"/secret"}""",
            )
        assertFailsWith<LaunchProfileException> {
            configure(arrayOf("--launch-profile", mixedSource.toString()))
        }
    }

    @Test
    fun profileAndReferencedPathsMustBeAbsoluteReadableFiles() {
        assertFailsWith<LaunchProfileException> {
            configure(arrayOf("--launch-profile", "relative.json"))
        }

        val directory = Files.createTempDirectory("safedb-launch-profile")
        val profile =
            directory.resolve("profile.json").apply {
                writeText(
                    """{"schemaVersion":1,"trustStore":{"type":"PKCS12","path":"relative.p12","password":{"source":"file","path":"relative.txt"}}}"""
                )
            }
        assertFailsWith<LaunchProfileException> {
            configure(arrayOf("--launch-profile", profile.toString()))
        }
    }

    @Test
    fun oversizedLaunchProfileIsRejectedBeforeJsonParsing() {
        val directory = Files.createTempDirectory("safedb-launch-profile")
        val profile =
            directory.resolve("oversized.json").apply {
                writeBytes(ByteArray(65_537) { ' '.code.toByte() })
            }

        val error =
            assertFailsWith<LaunchProfileException> {
                configure(arrayOf("--launch-profile", profile.toString()))
            }

        assertEquals("Launch profile is too large", error.message)
    }

    @Test
    fun passwordFileRejectsMalformedOrMultilineContentWithoutLeakingIt() {
        val directory = Files.createTempDirectory("safedb-launch-profile")
        val trustStore =
            createTrustStore(directory.resolve("roots.p12"), "secret", withCertificate = true)
        val multiline =
            directory.resolve("password.txt").apply { writeText("top-secret\nsecond-line") }
        val profile =
            writeProfile(
                directory,
                trustStore,
                """{"source":"file","path":"${jsonPath(multiline)}"}""",
            )

        val error =
            assertFailsWith<LaunchProfileException> {
                configure(arrayOf("--launch-profile", profile.toString()))
            }
        assertFalse(error.message.orEmpty().contains("top-secret"))

        multiline.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(
            assertFailsWith<LaunchProfileException> {
                    configure(arrayOf("--launch-profile", profile.toString()))
                }
                .message!!
                .contains("UTF-8")
        )
    }

    @Test
    fun wrongPasswordAndEmptyTrustStoreFailBeforePropertiesAreChanged() {
        val directory = Files.createTempDirectory("safedb-launch-profile")
        val passwordFile = directory.resolve("password.txt").apply { writeText("wrong") }
        val trustStore =
            createTrustStore(directory.resolve("roots.p12"), "right", withCertificate = true)
        val profile =
            writeProfile(
                directory,
                trustStore,
                """{"source":"file","path":"${jsonPath(passwordFile)}"}""",
            )
        val properties = mutableMapOf<String, String>()

        assertFailsWith<LaunchProfileException> {
            configure(arrayOf("--launch-profile", profile.toString()), properties = properties)
        }
        assertTrue(properties.isEmpty())

        passwordFile.writeText("right")
        createTrustStore(trustStore, "right", withCertificate = false)
        assertTrue(
            assertFailsWith<LaunchProfileException> {
                    configure(
                        arrayOf("--launch-profile", profile.toString()),
                        properties = properties,
                    )
                }
                .message!!
                .contains("no trusted certificates")
        )
        assertTrue(properties.isEmpty())
    }

    private fun configure(
        args: Array<String>,
        store: CredentialStore? = null,
        properties: MutableMap<String, String> = mutableMapOf(),
    ): Boolean =
        LaunchProfileBootstrap.configure(
            args = args,
            credentialStoreFactory = { store },
            propertySetter = properties::put,
        )

    private fun writeProfile(directory: Path, trustStore: Path, password: String): Path =
        directory.resolve("profile-${System.nanoTime()}.json").apply {
            writeText(
                """{"schemaVersion":1,"trustStore":{"type":"PKCS12","path":"${jsonPath(trustStore)}","password":$password}}"""
            )
        }

    private fun createTrustStore(path: Path, password: String, withCertificate: Boolean): Path {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, password.toCharArray()) }
        if (withCertificate) {
            val bundled = KeyStore.getInstance(KeyStore.getDefaultType())
            Files.newInputStream(
                    Path.of(System.getProperty("java.home"), "lib", "security", "cacerts")
                )
                .use { bundled.load(it, "changeit".toCharArray()) }
            val alias = bundled.aliases().asSequence().first { bundled.getCertificate(it) != null }
            store.setCertificateEntry("test-root", bundled.getCertificate(alias))
        }
        Files.newOutputStream(path).use { store.store(it, password.toCharArray()) }
        return path
    }

    private fun jsonPath(path: Path): String = path.toString().replace("\\", "\\\\")
}
