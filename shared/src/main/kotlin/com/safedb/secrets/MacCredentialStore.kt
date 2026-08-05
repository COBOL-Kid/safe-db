package com.safedb.secrets

class MacCredentialStore private constructor(private val delegate: CredentialStore) :
    CredentialStore by delegate {
    companion object {
        fun createOrFallback(): MacCredentialStore {
            val delegate =
                createJavaKeyringDelegateOrNull()
                    ?: run {
                        println("WARN: macOS keychain unavailable; using in-memory store")
                        DisabledMemoryStore()
                    }
            return MacCredentialStore(delegate)
        }
    }
}
