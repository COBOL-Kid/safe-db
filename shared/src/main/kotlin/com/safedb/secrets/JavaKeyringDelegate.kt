package com.safedb.secrets

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import com.safedb.platform.DesktopPlatform

internal class JavaKeyringDelegate(private val keyring: Keyring) : CredentialStore {
    override fun setPassword(service: String, account: String, password: String) {
        keyring.setPassword(service, account, password)
    }

    override fun getPassword(service: String, account: String): String? =
        try {
            keyring.getPassword(service, account)
        } catch (error: PasswordAccessException) {
            // java-keyring throws for a missing item instead of returning null.
            if (!isMissingCredentialError(error.message)) {
                throw error
            }
            null
        }

    override fun deletePassword(service: String, account: String) {
        try {
            keyring.deletePassword(service, account)
        } catch (error: PasswordAccessException) {
            if (!isMissingCredentialError(error.message)) {
                throw error
            }
        }
    }

    override fun vendor(): String = "java-keyring"
}

internal fun isMissingCredentialError(message: String?): Boolean {
    val lower = message.orEmpty().lowercase()
    return "failed to get credential" !in lower &&
        (hasWindowsMissingItemCode(lower) ||
            "password not found" in lower ||
            "no stored credentials match" in lower ||
            "item could not be found" in lower)
}

private fun hasWindowsMissingItemCode(lower: String): Boolean {
    val marker = "error code 1168"
    val index = lower.indexOf(marker)
    if (index < 0) return false
    val after = index + marker.length
    return after == lower.length || !lower[after].isDigit()
}

internal fun createJavaKeyringDelegateOrNull(): CredentialStore? = runCatching {
    JavaKeyringDelegate(Keyring.create())
}
    .getOrNull()

// Operational startup secrets must not use the fallback allowed for connection credentials.
internal fun createStrictPlatformCredentialStoreOrNull(
    platform: DesktopPlatform = DesktopPlatform.current()
): CredentialStore? =
    when (platform) {
        DesktopPlatform.MacOs,
        DesktopPlatform.Windows -> createJavaKeyringDelegateOrNull()
        DesktopPlatform.Linux -> null
    }
