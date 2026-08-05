package com.safedb.secrets

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

const val SESSION_IDLE_TIMEOUT_MS = 15 * 60 * 1000L

private data class SessionCredential(var password: String, var lastUsed: Instant)

object CredentialSession {
    private val session = ConcurrentHashMap<String, SessionCredential>()

    fun get(connectionId: String): String? {
        val entry = session[connectionId] ?: return null
        if (Duration.between(entry.lastUsed, Instant.now()).toMillis() >= SESSION_IDLE_TIMEOUT_MS) {
            session.remove(connectionId)
            return null
        }
        entry.lastUsed = Instant.now()
        return entry.password
    }

    fun put(connectionId: String, password: String) {
        session[connectionId] = SessionCredential(password, Instant.now())
    }

    fun invalidate(connectionId: String) {
        session.remove(connectionId)
        val prefix = "$connectionId:"
        session.keys.removeIf { it.startsWith(prefix) }
    }

    fun lockCredentials() {
        session.clear()
    }

    fun containsForTest(connectionId: String): Boolean = session.containsKey(connectionId)
}
