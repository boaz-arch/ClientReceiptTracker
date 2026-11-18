package com.company.supportvisit.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * Who may change the hours logged on a task. A task's creator and assignee may both save the
 * task, but each person's time entries are their own: without a manager/admin role you may only
 * add, change or remove entries that carry your user id, and every other entry must come back
 * exactly as it was stored.
 */
object TaskTimeRules {

    fun allowed(stored: JSONArray?, incoming: JSONArray?, userId: String): Boolean {
        val before = byId(stored)
        val after = byId(incoming) ?: return false
        val old = before.orEmpty()
        // Other people's entries: unchanged and not removed.
        for ((id, entry) in old) {
            if (entry.optString("userId") == userId) continue
            val now = after[id] ?: return false
            if (!now.similar(entry)) return false
        }
        // New or changed entries must be the caller's own.
        for ((id, entry) in after) {
            val previous = old[id]
            if (previous != null && previous.similar(entry)) continue
            if (entry.optString("userId") != userId) return false
            if (previous != null && previous.optString("userId") != userId) return false
        }
        return true
    }

    /** Entries by id; null if two entries share an id (which would hide a change). */
    private fun byId(array: JSONArray?): Map<String, JSONObject>? {
        val map = LinkedHashMap<String, JSONObject>()
        if (array == null) return map
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (map.put(entry.optString("id"), entry) != null) return null
        }
        return map
    }
}
