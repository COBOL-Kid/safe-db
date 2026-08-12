package com.safedb.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

// Runs a viewmodel operation, clearing the error flow first and reporting any failure through it.
// Cancellation must be rethrown: CancellationException is a RuntimeException, so catching it here
// would turn scope teardown into a user-visible error string. Returns whether the block completed.
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
