package com.company.supportvisit.data

import com.company.supportvisit.remote.RecordMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskTimeTest {

    private val hour = 60L * 60 * 1000
    private val t0 = 1_800_000_000_000L

    @Test
    fun `totals finished entries and a running one up to now`() {
        val entries = listOf(
            TimeEntry(id = "a", userId = "u1", start = t0, end = t0 + 2 * hour + 15 * 60_000),
            TimeEntry(id = "b", userId = "u2", start = t0 + 3 * hour) // running
        )
        assertEquals(135L + 60L, TaskTime.totalMinutes(entries, now = t0 + 4 * hour))
    }

    @Test
    fun `an end before the start counts as zero, never negative`() {
        assertEquals(0L, TaskTime.minutes(TimeEntry(start = t0, end = t0 - hour)))
    }

    @Test
    fun `finds only this person's running timer`() {
        val entries = listOf(
            TimeEntry(id = "a", userId = "u1", start = t0, end = t0 + hour),
            TimeEntry(id = "b", userId = "u2", start = t0)
        )
        assertNull(TaskTime.running(entries, "u1"))
        assertEquals("b", TaskTime.running(entries, "u2")?.id)
        assertNull(TaskTime.running(entries, null))
    }

    @Test
    fun `formats hours and minutes, and a running clock with seconds`() {
        assertEquals("2:15", TaskTime.format(135))
        assertEquals("0:05", TaskTime.format(5))
        assertEquals("12:00", TaskTime.format(720))
        assertEquals("1:02:03", TaskTime.formatElapsed(3_723_000))
    }

    @Test
    fun `time entries survive the database column and the server round trip`() {
        val entries = listOf(
            TimeEntry(id = "a", userId = "u1", userName = "dana", start = t0, end = t0 + hour),
            TimeEntry(id = "b", userId = null, start = t0 + 2 * hour)
        )
        assertEquals(entries, JsonCodec.timeEntriesFromJson(JsonCodec.timeEntriesToJson(entries)))

        val task = TaskEntity(id = "t1", title = "Fix printer", timeEntries = entries, remoteDirty = false)
        val back = RecordMapper.jsonToTask(JSONObject(RecordMapper.taskToJson(task).toString()))
        assertEquals(entries, back?.timeEntries)
    }

    @Test
    fun `tasks from older app versions have no time entries`() {
        val back = RecordMapper.jsonToTask(JSONObject().put("id", "t1").put("title", "Old"))
        assertEquals(emptyList<TimeEntry>(), back?.timeEntries)
        assertEquals(emptyList<TimeEntry>(), JsonCodec.timeEntriesFromJson(null))
    }
}
