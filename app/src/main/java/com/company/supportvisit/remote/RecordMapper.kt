package com.company.supportvisit.remote

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.JsonCodec
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.VisitPhoto
import com.company.supportvisit.data.VisitType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON shape of visits and clients on the Support Visit server.
 * Field names are part of the server API: add fields freely, never rename them.
 * Pure functions (no Android or network) so they can be unit tested.
 */
object RecordMapper {

    fun visitToJson(v: VisitEntity): JSONObject = JSONObject()
        .put("id", v.id)
        .put("clientId", v.clientId ?: JSONObject.NULL)
        .put("clientName", v.clientName)
        .put("clientEmail", v.clientEmail)
        .put("visitDate", v.visitDate)
        .put("startTime", v.startTime)
        .put("endTime", v.endTime)
        .put("visitType", v.type.key)
        .put("workDescription", v.workDescription)
        .put("technicianEmail", v.technicianEmail ?: JSONObject.NULL)
        .put("pdfUrl", v.pdfLink ?: JSONObject.NULL)
        .put("photoUrls", JSONArray(v.photos.mapNotNull { it.remoteUrl }))
        .put("parts", JSONArray(JsonCodec.partsToJson(v.parts)))
        .put("syncStatus", v.syncStatus.name)
        .put("createdAt", v.createdAt)
        .put("updatedAt", v.updatedAt)
        .put("breakMinutes", v.breakMinutes)
        .put("billableMinutesOverride", v.billableMinutesOverride ?: JSONObject.NULL)
        .put("hourlyRate", v.hourlyRate ?: JSONObject.NULL)

    /** Builds a visit pulled from the server; returns null if it has no id. */
    fun jsonToVisit(json: JSONObject): VisitEntity? {
        val id = json.optString("id").ifBlank { return null }
        val photoUrls = json.optJSONArray("photoUrls")
        return VisitEntity(
            id = id,
            clientName = json.optString("clientName"),
            clientEmail = json.optString("clientEmail"),
            visitDate = json.optString("visitDate"),
            startTime = json.optString("startTime"),
            endTime = json.optString("endTime"),
            workDescription = json.optString("workDescription"),
            technicianEmail = json.optStringOrNull("technicianEmail"),
            pdfLink = json.optStringOrNull("pdfUrl"),
            syncStatus = runCatching { SyncStatus.valueOf(json.optString("syncStatus")) }.getOrDefault(SyncStatus.EMAILED),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
            clientId = json.optStringOrNull("clientId"),
            visitType = VisitType.fromKey(json.optString("visitType")).key,
            photos = (0 until (photoUrls?.length() ?: 0)).map { VisitPhoto(remoteUrl = photoUrls!!.getString(it)) },
            parts = JsonCodec.partsFromJson(json.optJSONArray("parts")?.toString()),
            remoteDirty = false,
            ownedLocally = false,
            breakMinutes = json.optInt("breakMinutes", 0),
            billableMinutesOverride = json.optIntOrNull("billableMinutesOverride"),
            hourlyRate = json.optDoubleOrNull("hourlyRate")
        )
    }

    fun clientToJson(c: ClientEntity): JSONObject = JSONObject()
        .put("id", c.id)
        .put("name", c.name)
        .put("contactPerson", c.contactPerson)
        .put("email", c.email)
        .put("phone", c.phone)
        .put("address", c.address)
        .put("hourlyRate", c.hourlyRate ?: JSONObject.NULL)
        .put("notes", c.notes)
        .put("monthlyHoursAllowance", c.monthlyHoursAllowance ?: JSONObject.NULL)
        .put("updatedAt", c.updatedAt)

    fun jsonToClient(json: JSONObject): ClientEntity? {
        val id = json.optString("id").ifBlank { return null }
        return ClientEntity(
            id = id,
            name = json.optString("name"),
            contactPerson = json.optString("contactPerson"),
            email = json.optString("email"),
            phone = json.optString("phone"),
            address = json.optString("address"),
            hourlyRate = json.optDoubleOrNull("hourlyRate"),
            monthlyHoursAllowance = json.optDoubleOrNull("monthlyHoursAllowance"),
            notes = json.optString("notes"),
            remoteDirty = false,
            updatedAt = json.optLong("updatedAt", 0L)
        )
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeUnless { it.isNaN() }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }
}
