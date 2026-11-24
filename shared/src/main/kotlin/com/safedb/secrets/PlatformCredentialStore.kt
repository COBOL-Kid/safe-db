package com.safedb.secrets

import com.github.javakeyring.Keyring
import com.safedb.platform.DesktopPlatform
import com.safedb.platform.DesktopStoreUnavailableException

class PlatformCredentialStore
private constructor(private val delegate: CredentialStore, val label: String) :
    CredentialStore by delegate {
    companion object {
        fun createOrFallback(platform: DesktopPlatform): PlatformCredentialStore {
            val (serviceName, label) =
                when (platform) {
                    DesktopPlatform.MacOs -> "macOS keychain" to "protected"
                    DesktopPlatform.Windows -> "Windows Credential Manager" to "windows"
                    DesktopPlatform.Linux ->
                        throw DesktopStoreUnavailableException(
                            "OS credential store is not available on Linux",
                        )
                }
            val delegate =
                createJavaKeyringDelegateOrNull()
                    ?: run {
                        println("WARN: $serviceName unavailable; using in-memory store")
                        DisabledMemoryStore()
                    }
            return PlatformCredentialStore(delegate, label)
        }
    }
}

internal fun createJavaKeyringDelegateOrNull(): CredentialStore? = runCatching {
    JavaKeyringDelegate(Keyring.create())
}
    .getOrNull()

// Operational startup secrets must not use the fallback allowed for connection credentials.
internal fun createStrictPlatformCredentialStoreOrNull(
    platform: DesktopPlatform = DesktopPlatform.current(),
): CredentialStore? =
    when (platform) {
        DesktopPlatform.MacOs,
        DesktopPlatform.Windows -> createJavaKeyringDelegateOrNull()
        DesktopPlatform.Linux -> null
    }
