package com.safedb.secrets

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class LinuxCredentialStore private constructor(
    private val delegate: CredentialStore?,
    private val fileStore: Path?,
) : CredentialStore {
    val usesFileFallback: Boolean = delegate == null

    override fun setPassword(service: String, account: String, password: String) {
        delegate?.setPassword(service, account, password) ?: writeFile(service, account, password)
    }

    override fun getPassword(service: String, account: String): String? =
        delegate?.getPassword(service, account) ?: readFile(service, account)

    override fun deletePassword(service: String, account: String) {
        delegate?.deletePassword(service, account)
        fileStore?.let { Files.deleteIfExists(it.resolve(keyName(service, account))) }
    }

    override fun vendor(): String =
        delegate?.vendor() ?: "safe-db linux file fallback credential store"

    private fun writeFile(service: String, account: String, password: String) {
        val path = fileStore ?: error("file store unavailable")
        Files.createDirectories(path)
        val encoded = Base64.getEncoder().encodeToString(password.toByteArray())
        Files.writeString(path.resolve(keyName(service, account)), encoded)
    }

    private fun readFile(service: String, account: String): String? {
        val path = fileStore?.resolve(keyName(service, account)) ?: return null
        if (!Files.exists(path)) return null
        val encoded = Files.readString(path)
        return String(Base64.getDecoder().decode(encoded))
    }

    companion object {
        fun createOrFallback(): LinuxCredentialStore {
            val keyringStore = createJavaKeyringDelegateOrNull()
            if (keyringStore != null) return LinuxCredentialStore(keyringStore, null)
            val fallbackDir = Path.of(System.getProperty("user.home"), ".safe-db", "credentials")
            println("WARN: Linux Secret Service unavailable; using file fallback at $fallbackDir (permissions 0700)")
            return LinuxCredentialStore(null, fallbackDir)
        }
    }
}

private fun keyName(service: String, account: String): String =
    "${service.replace(':', '_')}__${account.replace(':', '_')}.cred"
