package com.company.supportvisit.data

import com.company.supportvisit.utils.DateUtils
import org.json.JSONArray
import java.util.UUID

/**
 * Converts the old visits.json format (written by the removed VisitStorage) into Room entities.
 * Legacy visits were only stored after a successful upload and email, so they are EMAILED.
 */
object LegacyVisitsMigrator {

    const val LEGACY_FILE_NAME = "visits.json"

    fun parse(json: String, now: Long = System.currentTimeMillis()): List<VisitEntity> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val pdfLink = item.optString("pdfLink", "")
            VisitEntity(
                id = UUID.randomUUID().toString(),
                clientName = item.optString("clientName", ""),
                clientEmail = item.optString("clientEmail", ""),
                visitDate = DateUtils.legacyToIso(item.optString("visitDate", "")),
                startTime = item.optString("startTime", ""),
                endTime = item.optString("endTime", ""),
                workDescription = item.optString("workDescription", ""),
                pdfLink = pdfLink.ifBlank { null },
                syncStatus = SyncStatus.EMAILED,
                createdAt = now - i, // keep the original order stable
                updatedAt = now
            )
        }
    }
}
