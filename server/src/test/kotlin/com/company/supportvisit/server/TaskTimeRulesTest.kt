package com.company.supportvisit.server

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTimeRulesTest {

    private fun entry(id: String, user: String, start: Long, end: Long? = start + 3_600_000) =
        JSONObject().put("id", id).put("userId", user).put("start", start).put("end", end ?: JSONObject.NULL)

    private fun list(vararg e: JSONObject) = JSONArray(e.toList())

    private val dana = entry("a", "dana", 1_000)
    private val omer = entry("b", "omer", 5_000)

    @Test
    fun `you may add, change and remove your own entries`() {
        assertTrue(TaskTimeRules.allowed(list(dana, omer), list(dana, omer, entry("c", "dana", 9_000)), "dana"))
        assertTrue(TaskTimeRules.allowed(list(dana, omer), list(entry("a", "dana", 1_000, 99_000), omer), "dana"))
        assertTrue(TaskTimeRules.allowed(list(dana, omer), list(omer), "dana"))
        assertTrue(TaskTimeRules.allowed(null, list(dana), "dana"), "first save of a new task")
    }

    @Test
    fun `other people's entries can't be changed, removed or forged`() {
        assertFalse(TaskTimeRules.allowed(list(dana, omer), list(dana), "dana"), "removed omer's")
        assertFalse(TaskTimeRules.allowed(list(dana, omer), list(dana, entry("b", "omer", 5_000, 999_999)), "dana"), "changed omer's")
        assertFalse(TaskTimeRules.allowed(list(dana, omer), list(dana, omer, entry("c", "omer", 9_000)), "dana"), "added one as omer")
        assertFalse(TaskTimeRules.allowed(list(dana, omer), list(dana, entry("b", "dana", 5_000)), "dana"), "took over omer's")
        assertFalse(TaskTimeRules.allowed(list(dana, omer), list(dana, omer, entry("b", "dana", 1)), "dana"), "duplicate id")
    }

    @Test
    fun `saving a task without touching the hours is always fine`() {
        assertTrue(TaskTimeRules.allowed(list(dana, omer), list(dana, omer), "someone-else"))
        assertTrue(TaskTimeRules.allowed(null, null, "dana"))
    }
}
