package com.safedb.secrets

interface CredentialStore {
    fun setPassword(service: String, account: String, password: String)

    fun getPassword(service: String, account: String): String?

    fun deletePassword(service: String, account: String)

    fun vendor(): String
}

class DisabledMemoryStore : CredentialStore {
    private val data = mutableMapOf<Pair<String, String>, String>()

    override fun setPassword(service: String, account: String, password: String) {
        data[service to account] = password
    }

    override fun getPassword(service: String, account: String): String? = data[service to account]

    override fun deletePassword(service: String, account: String) {
        data.remove(service to account)
    }

    override fun vendor(): String = "safe-db disabled (in-memory) credential store"
}
