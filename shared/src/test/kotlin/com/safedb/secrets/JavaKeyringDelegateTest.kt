package com.safedb.secrets

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaKeyringDelegateTest {
    @Test
    fun missingCredentialErrorRecognizesPlatformMessages() {
        assertTrue(isMissingCredentialError("Error code 1168"))
        assertTrue(isMissingCredentialError("Password not Found"))
        assertTrue(
            isMissingCredentialError("No stored credentials match SafeDb account: missing-id")
        )
    }

    @Test
    fun missingCredentialErrorRejectsOtherAccessFailures() {
        assertFalse(isMissingCredentialError(null))
        assertFalse(isMissingCredentialError(""))
        assertFalse(isMissingCredentialError("Failed to get credential. keychain locked"))
        assertFalse(isMissingCredentialError("Error code 5"))
    }
}
