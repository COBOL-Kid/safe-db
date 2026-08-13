package com.safedb.explore

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale

fun formatExploreNumber(value: BigDecimal, format: PivotNumberFormat): String {
    if (format.kind == NumberFormatKind.Auto) return value.stripTrailingZeros().toPlainString()
    val decimals = format.decimals.coerceIn(0, 8)
    val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
    val pattern = buildString {
        append(if (format.thousandsSeparator) "#,##0" else "0")
        if (decimals > 0) append('.').append("0".repeat(decimals))
    }
    return when (format.kind) {
        NumberFormatKind.Number -> DecimalFormat(pattern, symbols).format(value)
        NumberFormatKind.Percent -> DecimalFormat("$pattern%", symbols).format(value)
        NumberFormatKind.Currency ->
            DecimalFormat("¤$pattern", symbols)
                .apply {
                    runCatching { Currency.getInstance(format.currencyCode) }
                        .getOrNull()
                        ?.let { currency = it }
                }
                .format(value)
        NumberFormatKind.Scientific ->
            DecimalFormat("0.${"0".repeat(decimals)}E0", symbols).format(value)
    }
}
