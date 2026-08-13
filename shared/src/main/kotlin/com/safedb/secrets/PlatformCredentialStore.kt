package com.safedb.secrets

import com.safedb.platform.DesktopPlatform

class PlatformCredentialStore
private constructor(private val delegate: CredentialStore, val label: String) :
    CredentialStore by delegate {
    companion object {
        fun createOrFallback(platform: DesktopPlatform): PlatformCredentialStore {
            val delegate =
                createJavaKeyringDelegateOrNull()
                    ?: run {
                        println(
                            "WARN: ${platform.credentialServiceName()} unavailable; using in-memory store"
                        )
                        DisabledMemoryStore()
                    }
            return PlatformCredentialStore(delegate, platform.credentialBackendLabel())
        }
    }
}

private fun DesktopPlatform.credentialServiceName(): String =
    when (this) {
        DesktopPlatform.MacOs -> "macOS keychain"
        DesktopPlatform.Windows -> "Windows Credential Manager"
    }

private fun DesktopPlatform.credentialBackendLabel(): String =
    when (this) {
        DesktopPlatform.MacOs -> "protected"
        DesktopPlatform.Windows -> "windows"
    }
