package com.safedb.secrets

import com.safedb.adapter.SERVICE_NAME
import com.safedb.model.ConnectionDef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

const val ENV_BACKEND = "SAFEDB_KEYCHAIN_BACKEND"

enum class RequestedBackend { Auto, Disabled, Protected }

@Serializable
private data class BoundCredential(
    val version: Int,
    val fingerprint: String,
    val password: String,
)

object SecretsManager {
    private val json = Json { ignoreUnknownKeys = true }
    private var activeStore: CredentialStore = DisabledMemoryStore()
    private var activeLabel: String = "disabled"
    @Volatile
    var storeReadCountForTest: Int = 0
        private set

    fun resetStoreReadCountForTest() {
        storeReadCountForTest = 0
    }

    fun activeBackendLabel(): String = activeLabel

    fun parseRequestedBackendFrom(raw: String?): RequestedBackend {
        if (raw.isNullOrBlank()) return RequestedBackend.Auto
        return when (raw.lowercase()) {
            "disabled" -> RequestedBackend.Disabled
            "protected" -> if (isMacOs()) RequestedBackend.Protected else RequestedBackend.Auto
            "auto", "" -> RequestedBackend.Auto
            "keychain", "legacy" -> RequestedBackend.Auto
            else -> RequestedBackend.Auto
        }
    }

    fun initStore(envValue: String? = System.getenv(ENV_BACKEND)) {
        storeReadCountForTest = 0
        activeStore = when (parseRequestedBackendFrom(envValue)) {
            RequestedBackend.Disabled -> {
                println("WARN: $ENV_BACKEND=disabled: credentials are held in process memory only")
                DisabledMemoryStore()
            }
            RequestedBackend.Protected -> MacCredentialStore.createOrFallback()
            RequestedBackend.Auto -> selectAutoStore()
        }
        activeLabel = when (activeStore) {
            is DisabledMemoryStore -> "disabled"
            is MacCredentialStore -> "protected"
            is WindowsCredentialStore -> "windows"
            is LinuxCredentialStore -> if ((activeStore as LinuxCredentialStore).usesFileFallback) "linux-file" else "linux-keyutils"
            else -> "unknown"
        }
    }

    private fun selectAutoStore(): CredentialStore = when {
        isMacOs() -> MacCredentialStore.createOrFallback()
        isWindows() -> WindowsCredentialStore.createOrFallback()
        isLinux() -> LinuxCredentialStore.createOrFallback()
        else -> {
            println("WARN: unknown OS; using disabled in-memory credential store")
            DisabledMemoryStore()
        }
    }

    fun passwordForConnection(connectionId: String): Result<String> {
        CredentialSession.get(connectionId)?.let { return Result.success(it) }
        return when (val password = readFromStore(connectionId)) {
            null -> Result.failure(
                IllegalStateException(
                    "Password not found for this connection. Open Connections, enter the password, and save the connection again.",
                ),
            )
            else -> {
                CredentialSession.put(connectionId, password)
                Result.success(password)
            }
        }
    }

    fun passwordForDefinition(def: ConnectionDef): Result<String> {
        val cacheKey = "${def.id}:${def.credentialFingerprint()}"
        CredentialSession.get(cacheKey)?.let { return Result.success(it) }
        val raw = readFromStore(def.id) ?: return Result.failure(
            IllegalStateException(
                "Password not found for this connection. Open Connections, enter the password, and save the connection again.",
            ),
        )
        val record = runCatching { json.decodeFromString<BoundCredential>(raw) }.getOrElse {
            return Result.failure(
                IllegalStateException(
                    "This credential predates endpoint binding. Re-enter the password and save the connection before use.",
                ),
            )
        }
        if (record.version != 1 || record.fingerprint != def.credentialFingerprint()) {
            return Result.failure(
                IllegalStateException(
                    "Stored credentials do not match this connection endpoint or transport configuration. Re-enter the password and save the connection.",
                ),
            )
        }
        CredentialSession.put(cacheKey, record.password)
        return Result.success(record.password)
    }

    fun savePasswordForDefinition(def: ConnectionDef, password: String): Result<Unit> = runCatching {
        val record = BoundCredential(1, def.credentialFingerprint(), password)
        writeToStore(def.id, json.encodeToString(BoundCredential.serializer(), record))
        CredentialSession.invalidate(def.id)
        CredentialSession.put("${def.id}:${def.credentialFingerprint()}", password)
    }.mapError(::formatSaveCredentialError)

    fun savePassword(connectionId: String, password: String): Result<Unit> = runCatching {
        writeToStore(connectionId, password)
        CredentialSession.put(connectionId, password)
    }.mapError(::formatSaveCredentialError)

    fun getPassword(connectionId: String): Result<String?> {
        CredentialSession.get(connectionId)?.let { return Result.success(it) }
        return Result.success(readFromStore(connectionId)?.also { CredentialSession.put(connectionId, it) })
    }

    fun deletePassword(connectionId: String): Result<Unit> = runCatching {
        CredentialSession.invalidate(connectionId)
        activeStore.deletePassword(SERVICE_NAME, connectionId)
    }

    fun lockCredentials() = CredentialSession.lockCredentials()

    fun formatSaveCredentialError(err: Throwable): String {
        val message = err.message ?: err.toString()
        return if (isMissingEntitlementError(message)) {
            "Could not store credentials: the app is not signed with keychain entitlements. For local development, set $ENV_BACKEND=disabled or run a signed release bundle."
        } else {
            "Could not store credentials ($message)."
        }
    }

    fun isMissingEntitlementError(message: String): Boolean {
        val lower = message.lowercase()
        return "entitlement" in lower || "platform failure" in lower
    }

    private fun readFromStore(connectionId: String): String? {
        storeReadCountForTest++
        return activeStore.getPassword(SERVICE_NAME, connectionId)
    }

    private fun writeToStore(connectionId: String, value: String) {
        activeStore.setPassword(SERVICE_NAME, connectionId, value)
    }

    internal fun useStoreForTest(store: CredentialStore) {
        activeStore = store
        activeLabel = "test"
    }

    private fun isMacOs(): Boolean = System.getProperty("os.name").lowercase().contains("mac")
    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
    private fun isLinux(): Boolean = System.getProperty("os.name").lowercase().contains("linux")
}

private fun <T> Result<T>.mapError(transform: (Throwable) -> String): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(IllegalStateException(transform(it))) })
