package com.safedb.buildlogic

import org.gradle.api.GradleException

/**
 * Splits the value of a `-Pseed<Dialect>Args=...` property into seeder arguments the way a POSIX
 * shell would: unquoted whitespace separates arguments, single and double quotes group them, and a
 * backslash escapes the next character. The `scripts/seed_*.sh` wrappers produce this encoding.
 */
fun splitSeedTaskArgs(raw: String, propertyName: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaping = false
    for (char in raw) {
        when {
            escaping -> {
                current.append(char)
                escaping = false
            }
            char == '\\' -> escaping = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '\'' || char == '"') -> quote = char
            quote == null && char.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    args.add(current.toString())
                    current.clear()
                }
            }
            else -> current.append(char)
        }
    }
    if (escaping) current.append('\\')
    if (quote != null) throw GradleException("Unclosed quote in $propertyName")
    if (current.isNotEmpty()) args.add(current.toString())
    return args
}
