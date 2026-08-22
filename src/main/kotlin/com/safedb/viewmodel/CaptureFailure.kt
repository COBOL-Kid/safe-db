package com.safedb.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

// CancellationException is a RuntimeException; catching it here would turn scope teardown into a
// user-visible error string.
internal suspend fun capturingFailure(
    error: MutableStateFlow<String?>,
    loading: MutableStateFlow<Boolean>? = null,
    block: suspend () -> Unit,
): Boolean {
    loading?.value = true
    error.value = null
    return try {
        block()
        true
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        error.value = failure.message ?: failure.toString()
        false
    } finally {
        loading?.value = false
    }
}
