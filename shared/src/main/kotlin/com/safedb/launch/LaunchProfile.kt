package com.safedb.launch

import com.safedb.secrets.CredentialStore
import com.safedb.secrets.createStrictPlatformCredentialStoreOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val TRUST_STORE_CREDENTIAL_SERVICE = "com.safedb.app.trust-store"
// Internal bridge for pgjdbc, whose native SSL factory consumes PEM roots rather than the JSSE
// PKCS12 setting.
internal const val POSTGRES_LAUNCH_ROOT_CERT_PROPERTY = "com.safedb.launch.postgresSslRootCert"

private const val MAX_PASSWORD_BYTES = 4_096
private const val MAX_PROFILE_BYTES = 65_536
private const val PROFILE_OPTION = "--launch-profile"

@Serializable
private data class LaunchProfile(val schemaVersion: Int, val trustStore: TrustStoreProfile)

@Serializable
private data class TrustStoreProfile(
    val type: String,
    val path: String,
    val password: PasswordSource,
)

@Serializable
private data class PasswordSource(
    val source: String,
    val reference: String? = null,
    val path: String? = null,
)

class LaunchProfileException(message: String, cause: Throwable? = null) : Exception(message, cause)

object LaunchProfileBootstrap {
    private val json = Json { ignoreUnknownKeys = false }

    // False means no profile was selected and bundled trust must remain untouched.
    fun configure(args: Array<String>): Boolean =
        configure(
            args = args,
            credentialStoreFactory = ::createStrictPlatformCredentialStoreOrNull,
            propertySetter = System::setProperty,
        )

    internal fun configure(
        args: Array<String>,
        credentialStoreFactory: () -> CredentialStore?,
        propertySetter: (String, String) -> Unit,
    ): Boolean {
        if (args.isEmpty()) return false
        if (args.size != 2 || args[0] != PROFILE_OPTION) {
            throw LaunchProfileException(
                "Usage: safe-db $PROFILE_OPTION /absolute/path/to/profile.json"
            )
        }

        val profilePath = requireAbsoluteRegularFile(args[1], "Launch profile")
        val profileJson = readUtf8File(profilePath, MAX_PROFILE_BYTES, "Launch profile")
        val profile =
            try {
                json.decodeFromString<LaunchProfile>(profileJson)
            } catch (error: Exception) {
                throw LaunchProfileException(
                    "Launch profile is not valid schema-version 1 JSON",
                    error,
                )
            }
        if (profile.schemaVersion != 1) {
            throw LaunchProfileException(
                "Unsupported launch profile schemaVersion ${profile.schemaVersion}"
            )
        }
        if (profile.trustStore.type != "PKCS12") {
            throw LaunchProfileException("Launch profile trustStore.type must be PKCS12")
        }

        val trustStorePath =
            requireAbsoluteRegularFile(profile.trustStore.path, "PKCS12 trust store")
        val password = resolvePassword(profile.trustStore.password, credentialStoreFactory)
        val passwordChars = password.toCharArray()
        try {
            val postgresRootCert =
                writePostgresRootCert(loadTrustedCertificates(trustStorePath, passwordChars))
            try {
                propertySetter("javax.net.ssl.trustStore", trustStorePath.toString())
                propertySetter("javax.net.ssl.trustStoreType", "PKCS12")
                propertySetter("javax.net.ssl.trustStorePassword", password)
                propertySetter(POSTGRES_LAUNCH_ROOT_CERT_PROPERTY, postgresRootCert.toString())
                postgresRootCert.toFile().deleteOnExit()
            } catch (error: Exception) {
                runCatching { Files.deleteIfExists(postgresRootCert) }
                throw LaunchProfileException(
                    "Launch-profile trust configuration could not be applied",
                    error,
                )
            }
        } finally {
            passwordChars.fill('\u0000')
        }
        return true
    }

    private fun resolvePassword(
        source: PasswordSource,
        credentialStoreFactory: () -> CredentialStore?,
    ): String =
        when (source.source) {
            "credentialStore" -> {
                if (source.path != null || source.reference.isNullOrBlank()) {
                    throw LaunchProfileException(
                        "credentialStore passwords require a non-blank reference and do not allow path"
                    )
                }
                val store =
                    credentialStoreFactory()
                        ?: throw LaunchProfileException("Platform credential store is unavailable")
                try {
                    store.getPassword(TRUST_STORE_CREDENTIAL_SERVICE, source.reference)
                        ?: throw LaunchProfileException(
                            "Trust-store credential '${source.reference}' was not found in the platform credential store"
                        )
                } catch (error: LaunchProfileException) {
                    throw error
                } catch (error: Exception) {
                    throw LaunchProfileException("Could not read the trust-store credential", error)
                }
            }
            "file" -> {
                if (source.reference != null || source.path.isNullOrBlank()) {
                    throw LaunchProfileException(
                        "file passwords require path and do not allow reference"
                    )
                }
                readPasswordFile(
                    requireAbsoluteRegularFile(source.path, "Trust-store password file")
                )
            }
            else ->
                throw LaunchProfileException(
                    "Unsupported trust-store password source '${source.source}'"
                )
        }

    private fun readPasswordFile(path: Path): String {
        val decoded = readUtf8File(path, MAX_PASSWORD_BYTES + 2, "Trust-store password file")
        val password =
            when {
                decoded.endsWith("\r\n") -> decoded.dropLast(2)
                decoded.endsWith("\n") -> decoded.dropLast(1)
                else -> decoded
            }
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val passwordTooLarge =
            try {
                passwordBytes.size > MAX_PASSWORD_BYTES
            } finally {
                passwordBytes.fill(0)
            }
        if (passwordTooLarge) {
            throw LaunchProfileException("Trust-store password file is too large")
        }
        if (password.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            throw LaunchProfileException("Trust-store password file must contain exactly one line")
        }
        return password
    }

    private fun readUtf8File(path: Path, maxBytes: Int, label: String): String {
        val bytes =
            try {
                Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
            } catch (error: Exception) {
                throw LaunchProfileException("$label could not be read", error)
            }
        if (bytes.size > maxBytes) {
            bytes.fill(0)
            throw LaunchProfileException("$label is too large")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw LaunchProfileException("$label must be valid UTF-8", error)
        } finally {
            bytes.fill(0)
        }
    }

    private fun loadTrustedCertificates(path: Path, password: CharArray): List<Certificate> {
        val keyStore = KeyStore.getInstance("PKCS12")
        try {
            Files.newInputStream(path).use { keyStore.load(it, password) }
        } catch (error: Exception) {
            throw LaunchProfileException("PKCS12 trust store could not be opened", error)
        }
        val trustedCertificates =
            keyStore
                .aliases()
                .asSequence()
                .filter(keyStore::isCertificateEntry)
                .mapNotNull(keyStore::getCertificate)
                .toList()
        if (trustedCertificates.isEmpty()) {
            throw LaunchProfileException("PKCS12 trust store contains no trusted certificates")
        }
        return trustedCertificates
    }

    private fun writePostgresRootCert(certificates: List<Certificate>): Path {
        val pem =
            try {
                certificates.joinToString(separator = "\n", postfix = "\n") { certificate ->
                    val encoded =
                        Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                            .encodeToString(certificate.encoded)
                    "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----"
                }
            } catch (error: Exception) {
                throw LaunchProfileException(
                    "PKCS12 trusted certificates could not be prepared for PostgreSQL",
                    error,
                )
            }
        val path =
            try {
                Files.createTempFile("safedb-launch-roots", ".pem")
            } catch (error: Exception) {
                throw LaunchProfileException(
                    "PostgreSQL launch-profile root certificate file could not be created",
                    error,
                )
            }
        try {
            Files.writeString(path, pem, StandardCharsets.US_ASCII)
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(path) }
            throw LaunchProfileException(
                "PostgreSQL launch-profile root certificate file could not be written",
                error,
            )
        }
        return path
    }

    private fun requireAbsoluteRegularFile(raw: String, label: String): Path {
        val path =
            try {
                Path.of(raw)
            } catch (error: Exception) {
                throw LaunchProfileException("$label path is invalid", error)
            }
        if (!path.isAbsolute) throw LaunchProfileException("$label path must be absolute")
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw LaunchProfileException("$label must be a readable regular file")
        }
        return path.normalize()
    }
}
