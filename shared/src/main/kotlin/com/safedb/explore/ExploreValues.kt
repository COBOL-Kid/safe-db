package com.safedb.explore

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Accept the date spellings JDBC drivers commonly put in a result cell. */
internal fun parseExploreDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text.replaceFirst(' ', 'T')).toLocalDate() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toLocalDate() }.getOrNull()
