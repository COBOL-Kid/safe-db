package com.safedb.secrets

class WindowsCredentialStore private constructor(private val delegate: CredentialStore) :
    CredentialStore by delegate {
    companion object {
        fun createOrFallback(): WindowsCredentialStore {
            val delegate =
                createJavaKeyringDelegateOrNull()
                    ?: run {
                        println(
                            "WARN: Windows Credential Manager unavailable; using in-memory store"
                        )
                        DisabledMemoryStore()
                    }
            return WindowsCredentialStore(delegate)
        }
    }
}
