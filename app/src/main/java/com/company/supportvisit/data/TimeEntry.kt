package com.company.supportvisit.data

import java.util.Locale
import java.util.UUID

/**
 * Time someone spent on a task: started with the timer or entered by hand.
 * [end] is null while the timer is still running. Times are epoch milliseconds.
 * Only counts hours: it never creates or sends a visit report.
 */
data class TimeEntry(
    val id: String = UUID.randomUUID().toString(),
    val userId: String? = null,
    val userName: String? = null,
    val start: Long,
    val end: Long? = null
) {
    val isRunning: Boolean get() = end == null
}

/** Hours worked on tasks: totals, the running timer, and h:mm formatting. */
object TaskTime {

    /** Minutes in one entry; a running one counts up to [now]. Never negative. */
    fun minutes(entry: TimeEntry, now: Long = System.currentTimeMillis()): Long =
        (((entry.end ?: now) - entry.start) / 60_000L).coerceAtLeast(0L)

    fun totalMinutes(entries: List<TimeEntry>, now: Long = System.currentTimeMillis()): Long =
        entries.sumOf { minutes(it, now) }

    /** This person's running timer on these entries, if any. */
    fun running(entries: List<TimeEntry>, userId: String?): TimeEntry? =
        entries.firstOrNull { it.isRunning && it.userId == userId }

    /** 135 minutes -> "2:15". */
    fun format(minutes: Long): String = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

    /** Elapsed time with seconds for a running timer: "0:42:07". */
    fun formatElapsed(millis: Long): String {
        val seconds = (millis / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}
