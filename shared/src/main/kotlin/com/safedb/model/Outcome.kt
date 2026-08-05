package com.safedb.model

sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()

    data class Err(val message: String) : Outcome<Nothing>()

    fun unwrap(): T =
        when (this) {
            is Ok -> value
            is Err -> throw IllegalStateException(message)
        }

    fun unwrapErr(): String =
        when (this) {
            is Err -> message
            is Ok -> throw IllegalStateException("expected Err")
        }

    companion object {
        fun <T> ok(value: T): Outcome<T> = Ok(value)

        fun err(message: String): Outcome<Nothing> = Err(message)
    }
}

fun <T> Outcome<T>.isOk(): Boolean = this is Outcome.Ok

fun <T> Outcome<T>.isErr(): Boolean = this is Outcome.Err
