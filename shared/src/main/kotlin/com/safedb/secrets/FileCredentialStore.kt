package com.safedb.secrets

import com.safedb.persist.hasGroupOrOtherPermissions
import com.safedb.persist.writePrivateFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FileCredentialStore(private val dir: Path) : CredentialStore {
    private val lock = ReentrantLock()

    override fun setPassword(service: String, account: String, password: String) {
        val path = fileFor(account)
        lock.withLock { writePrivateFile(path, password) }
    }

    override fun getPassword(service: String, account: String): String? = lock.withLock {
        val path = fileFor(account)
        if (!Files.isRegularFile(path)) return@withLock null
        if (hasGroupOrOtherPermissions(path)) {
            throw IllegalStateException("Credential file for this connection is not owner-only")
        }
        Files.readString(path)
    }

    override fun deletePassword(service: String, account: String) {
        lock.withLock { Files.deleteIfExists(fileFor(account)) }
    }

    override fun vendor(): String = "file"

    private fun fileFor(account: String): Path {
        require(ACCOUNT_NAME.matches(account)) { "Invalid credential account name" }
        val path = dir.resolve(account).normalize()
        require(path.parent == dir.normalize()) { "Invalid credential account name" }
        return path
    }

    companion object {
        private val ACCOUNT_NAME = Regex("[A-Za-z0-9._-]+")
    }
}
