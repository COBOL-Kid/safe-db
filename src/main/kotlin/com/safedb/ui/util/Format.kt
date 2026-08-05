package com.safedb.ui.util

import com.safedb.model.HistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formatTime(timestamp: String, nowMs: Long = System.currentTimeMillis()): String {
    val seconds = timestamp.toLongOrNull() ?: return ""
    val diff = nowMs - seconds * 1000
    val mins = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val formatter =
                DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
            formatter.format(Instant.ofEpochSecond(seconds))
        }
    }
}

fun summarizeSpec(entry: HistoryEntry): String {
    val tables = entry.spec.tables.joinToString(", ") { it.name }
    val cols = entry.spec.columns.size
    val joins = entry.spec.joins.size
    val parts = buildList {
        add(tables)
        if (cols > 0) add("$cols col${if (cols == 1) "" else "s"}")
        if (joins > 0) add("$joins join${if (joins == 1) "" else "s"}")
        add("limit ${entry.spec.limit}")
    }
    return parts.joinToString(" · ")
}
