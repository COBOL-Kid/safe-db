package com.safedb.explore

import java.math.BigDecimal
import java.math.MathContext

data class FormulaResult(
    val value: BigDecimal?,
    val error: String? = null,
)

fun evaluatePivotFormula(
    formula: String,
    values: Map<String, BigDecimal?>,
): FormulaResult = runCatching {
    PivotFormulaParser(formula, values).parse()
}.fold(
    onSuccess = { FormulaResult(it) },
    onFailure = { FormulaResult(null, it.message ?: "Invalid formula") },
)

private class PivotFormulaParser(
    private val source: String,
    private val values: Map<String, BigDecimal?>,
) {
    private var offset = 0

    fun parse(): BigDecimal? {
        val value = parseExpression()
        skipWhitespace()
        require(offset == source.length) { "Unexpected '${source[offset]}' at position ${offset + 1}" }
        return value
    }

    private fun parseExpression(): BigDecimal? {
        var value = parseTerm()
        while (true) {
            skipWhitespace()
            value = when {
                consume('+') -> combine(value, parseTerm(), BigDecimal::add)
                consume('-') -> combine(value, parseTerm(), BigDecimal::subtract)
                else -> return value
            }
        }
    }

    private fun parseTerm(): BigDecimal? {
        var value = parseFactor()
        while (true) {
            skipWhitespace()
            value = when {
                consume('*') -> combine(value, parseFactor(), BigDecimal::multiply)
                consume('/') -> {
                    val right = parseFactor()
                    if (right != null && right.compareTo(BigDecimal.ZERO) == 0) {
                        throw IllegalArgumentException("Division by zero")
                    }
                    if (value == null || right == null) null else value.divide(right, MathContext.DECIMAL128)
                }
                else -> return value
            }
        }
    }

    private fun parseFactor(): BigDecimal? {
        skipWhitespace()
        if (consume('+')) return parseFactor()
        if (consume('-')) return parseFactor()?.negate()
        if (consume('(')) {
            val value = parseExpression()
            skipWhitespace()
            require(consume(')')) { "Missing ')'" }
            return value
        }
        if (peek() == '[') return parseReference()
        return parseNumber()
    }

    private fun parseReference(): BigDecimal? {
        consume('[')
        val end = source.indexOf(']', startIndex = offset)
        require(end >= 0) { "Missing ']' in measure reference" }
        val alias = source.substring(offset, end).trim()
        require(alias.isNotEmpty()) { "Measure reference cannot be empty" }
        offset = end + 1
        require(values.containsKey(alias)) { "Unknown measure '$alias'" }
        return values[alias]
    }

    private fun parseNumber(): BigDecimal {
        val start = offset
        var seenDot = false
        while (offset < source.length) {
            val char = source[offset]
            if (char.isDigit()) {
                offset++
            } else if (char == '.' && !seenDot) {
                seenDot = true
                offset++
            } else {
                break
            }
        }
        require(offset > start) { "Expected a number, measure reference, or '(' at position ${offset + 1}" }
        return source.substring(start, offset).toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Invalid number at position ${start + 1}")
    }

    private fun combine(
        left: BigDecimal?,
        right: BigDecimal?,
        operation: (BigDecimal, BigDecimal) -> BigDecimal,
    ): BigDecimal? = if (left == null || right == null) null else operation(left, right)

    private fun skipWhitespace() {
        while (offset < source.length && source[offset].isWhitespace()) offset++
    }

    private fun consume(expected: Char): Boolean {
        if (offset >= source.length || source[offset] != expected) return false
        offset++
        return true
    }

    private fun peek(): Char? = source.getOrNull(offset)
}
