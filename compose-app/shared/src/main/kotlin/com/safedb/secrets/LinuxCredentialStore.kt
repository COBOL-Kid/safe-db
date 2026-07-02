package com.safedb.secrets

class LinuxCredentialStore private constructor(
    private val delegate: CredentialStore?,
) : CredentialStore {
    val usesMemoryFallback: Boolean = delegate is DisabledMemoryStore

    override fun setPassword(service: String, account: String, password: String) {
        delegate?.setPassword(service, account, password)
    }

    override fun getPassword(service: String, account: String): String? =
        delegate?.getPassword(service, account)

    override fun deletePassword(service: String, account: String) {
        delegate?.deletePassword(service, account)
    }

    override fun vendor(): String =
        delegate?.vendor() ?: "safe-db linux credential store"

    companion object {
        fun createOrFallback(): LinuxCredentialStore {
            val keyringStore = createJavaKeyringDelegateOrNull()
            if (keyringStore != null) return LinuxCredentialStore(keyringStore)
            println("WARN: Linux Secret Service unavailable; using in-memory credential store")
            return LinuxCredentialStore(DisabledMemoryStore())
        }
    }
}
