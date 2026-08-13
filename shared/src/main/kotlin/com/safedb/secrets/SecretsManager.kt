package com.safedb.secrets

import com.safedb.adapter.SERVICE_NAME
import com.safedb.model.ConnectionDef
import com.safedb.platform.DesktopPlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val ENV_BACKEND = "SAFEDB_KEYCHAIN_BACKEND"

enum class RequestedBackend {
    Auto,
    Disabled,
    Protected,
}

// Bind credentials to endpoint and transport settings so edited profiles cannot reuse old secrets.
@Serializable
private data class BoundCredential(val version: Int, val fingerprint: String, val password: String)

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
            "protected" -> RequestedBackend.Protected
            "auto",
            "" -> RequestedBackend.Auto
            "keychain",
            "legacy" -> RequestedBackend.Auto
            else -> RequestedBackend.Auto
        }
    }

    fun initStore(
        envValue: String? = System.getenv(ENV_BACKEND),
        platform: DesktopPlatform? = null,
    ) {
        initStore(envValue) { platform ?: DesktopPlatform.current() }
    }

    internal fun initStoreForOsName(envValue: String?, osName: String) {
        initStore(envValue) { DesktopPlatform.resolve(osName) }
    }

    private fun initStore(envValue: String?, platformResolver: () -> DesktopPlatform) {
        storeReadCountForTest = 0
        activeStore =
            when (parseRequestedBackendFrom(envValue)) {
                RequestedBackend.Disabled -> {
                    println(
                        "WARN: $ENV_BACKEND=disabled: credentials are held in process memory only"
                    )
                    DisabledMemoryStore()
                }
                RequestedBackend.Protected,
                RequestedBackend.Auto ->
                    PlatformCredentialStore.createOrFallback(platformResolver())
            }
        activeLabel =
            when (val store = activeStore) {
                is DisabledMemoryStore -> "disabled"
                is PlatformCredentialStore -> store.label
                else -> "unknown"
            }
    }

    fun passwordForDefinition(def: ConnectionDef): Result<String> {
        val cacheKey = "${def.id}:${def.credentialFingerprint()}"
        CredentialSession.get(cacheKey)?.let {
            return Result.success(it)
        }
        val raw =
            readFromStore(def.id)
                ?: return Result.failure(
                    IllegalStateException(
                        "Password not found for this connection. Delete and add the connection again to store the password."
                    )
                )
        val record = runCatching {
            json.decodeFromString<BoundCredential>(raw)
        }
            .getOrElse {
                return Result.failure(
                    IllegalStateException(
                        "This credential predates endpoint binding. Delete and add the connection again before use."
                    )
                )
            }
        if (record.version != 1 || record.fingerprint != def.credentialFingerprint()) {
            return Result.failure(
                IllegalStateException(
                    "Stored credentials do not match this connection endpoint or transport configuration. Delete and add the connection again."
                )
            )
        }
        CredentialSession.put(cacheKey, record.password)
        return Result.success(record.password)
    }

    fun savePasswordForDefinition(def: ConnectionDef, password: String): Result<Unit> =
        runCatching {
            val record = BoundCredential(1, def.credentialFingerprint(), password)
            writeToStore(def.id, json.encodeToString(BoundCredential.serializer(), record))
            CredentialSession.invalidate(def.id)
            CredentialSession.put("${def.id}:${def.credentialFingerprint()}", password)
        }
        .mapError(::formatSaveCredentialError)

    fun deletePassword(connectionId: String): Result<Unit> = runCatching {
        CredentialSession.invalidate(connectionId)
        activeStore.deletePassword(SERVICE_NAME, connectionId)
    }

    fun lockCredentials() = CredentialSession.lockCredentials()

    fun formatSaveCredentialError(err: Throwable): String {
        val message = err.message ?: err.toString()
        return if (isMissingEntitlementError(message)) {
            "Could not store credentials in the platform credential store. For local development, set $ENV_BACKEND=disabled; otherwise check that the OS credential service is available."
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
}

private fun <T> Result<T>.mapError(transform: (Throwable) -> String): Result<T> =
    fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(IllegalStateException(transform(it))) },
    )
