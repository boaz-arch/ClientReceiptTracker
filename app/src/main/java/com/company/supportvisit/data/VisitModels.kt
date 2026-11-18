package com.company.supportvisit.data

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

/** Kind of visit. [key] is what is stored locally and on the server; labels are localized in the UI. */
enum class VisitType(val key: String) {
    INSTALLATION("Installation"),
    REPAIR("Repair"),
    MAINTENANCE("Maintenance"),
    REMOTE("Remote"),
    OTHER("Other");

    companion object {
        fun fromKey(key: String?): VisitType = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OTHER
    }
}

/** A part or material used during a visit. */
data class PartItem(
    val description: String,
    val quantity: Double,
    val unitPrice: Double
) {
    val total: Double get() = quantity * unitPrice
}

fun List<PartItem>.partsTotal(): Double = sumOf { it.total }

/**
 * A photo attached to a visit. [localPath] exists until the visit is fully sent;
 * [remoteUrl] is set once the photo is uploaded next to the PDF.
 */
data class VisitPhoto(
    val localPath: String? = null,
    val remoteUrl: String? = null
)

object JsonCodec {

    fun partsToJson(parts: List<PartItem>): String = JSONArray().apply {
        parts.forEach {
            put(JSONObject().put("description", it.description).put("quantity", it.quantity).put("unitPrice", it.unitPrice))
        }
    }.toString()

    fun partsFromJson(json: String?): List<PartItem> = parseArray(json) { o ->
        PartItem(o.optString("description"), o.optDouble("quantity", 0.0), o.optDouble("unitPrice", 0.0))
    }

    fun photosToJson(photos: List<VisitPhoto>): String = JSONArray().apply {
        photos.forEach { p ->
            put(JSONObject().apply {
                p.localPath?.let { put("localPath", it) }
                p.remoteUrl?.let { put("remoteUrl", it) }
            })
        }
    }.toString()

    fun photosFromJson(json: String?): List<VisitPhoto> = parseArray(json) { o ->
        VisitPhoto(o.optString("localPath").ifBlank { null }, o.optString("remoteUrl").ifBlank { null })
    }

    fun timeEntriesToJsonArray(entries: List<TimeEntry>): JSONArray = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id)
                put("start", e.start)
                put("end", e.end ?: JSONObject.NULL)
                e.userId?.let { put("userId", it) }
                e.userName?.let { put("userName", it) }
            })
        }
    }

    fun timeEntriesToJson(entries: List<TimeEntry>): String = timeEntriesToJsonArray(entries).toString()

    fun timeEntriesFromJson(json: String?): List<TimeEntry> = parseArray(json) { o -> timeEntryFrom(o) }.filterNotNull()

    fun timeEntriesFromJsonArray(array: JSONArray?): List<TimeEntry> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::timeEntryFrom) }

    private fun timeEntryFrom(o: JSONObject): TimeEntry? {
        val start = o.optLong("start", 0L).takeIf { it > 0L } ?: return null
        return TimeEntry(
            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            userId = o.optString("userId").ifBlank { null },
            userName = o.optString("userName").ifBlank { null },
            start = start,
            end = if (!o.has("end") || o.isNull("end")) null else o.optLong("end")
        )
    }

    fun stringsToJson(values: List<String>): String = JSONArray(values).toString()

    fun stringsFromJson(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    private fun <T> parseArray(json: String?, map: (JSONObject) -> T): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(map) }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }
}

class Converters {
    @TypeConverter fun partsToString(value: List<PartItem>): String = JsonCodec.partsToJson(value)
    @TypeConverter fun stringToParts(value: String?): List<PartItem> = JsonCodec.partsFromJson(value)
    @TypeConverter fun photosToString(value: List<VisitPhoto>): String = JsonCodec.photosToJson(value)
    @TypeConverter fun stringToPhotos(value: String?): List<VisitPhoto> = JsonCodec.photosFromJson(value)
    @TypeConverter fun stringListToString(value: List<String>): String = JsonCodec.stringsToJson(value)
    @TypeConverter fun stringToStringList(value: String?): List<String> = JsonCodec.stringsFromJson(value)
    @TypeConverter fun timeEntriesToString(value: List<TimeEntry>): String = JsonCodec.timeEntriesToJson(value)
    @TypeConverter fun stringToTimeEntries(value: String?): List<TimeEntry> = JsonCodec.timeEntriesFromJson(value)
}
