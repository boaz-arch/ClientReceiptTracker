package com.company.supportvisit.remote

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.PartItem
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.data.VisitPhoto
import com.company.supportvisit.data.VisitType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecordMapperTest {

    private val visit = VisitEntity(
        id = "v-1",
        clientId = "c-1",
        clientName = "לקוח \"בדיקה\"",
        clientEmail = "it@acme.test",
        visitDate = "2026-11-15",
        startTime = "09:00",
        endTime = "10:30",
        visitType = VisitType.REPAIR.key,
        workDescription = "Line 1\nLine 2",
        technicianEmail = "tech@company.test",
        pdfLink = "https://srv/api/v1/visits/v-1/files/report.pdf",
        photos = listOf(VisitPhoto("/local/a.jpg", "https://srv/a.jpg"), VisitPhoto("/local/b.jpg", null)),
        parts = listOf(PartItem("Cable", 2.0, 15.5)),
        syncStatus = SyncStatus.EMAILED,
        createdAt = 1000L,
        updatedAt = 2000L,
        // Local-only fields that must not leak to the server:
        signaturePath = "/local/sig.png",
        pdfPath = "/local/report.pdf"
    )

    @Test
    fun `visit survives a round trip through the server JSON`() {
        val json = JSONObject(RecordMapper.visitToJson(visit).toString())
        val back = RecordMapper.jsonToVisit(json)!!

        assertEquals(visit.id, back.id)
        assertEquals(visit.clientName, back.clientName)
        assertEquals(visit.workDescription, back.workDescription)
        assertEquals(VisitType.REPAIR, back.type)
        assertEquals(visit.parts, back.parts)
        assertEquals(listOf(VisitPhoto(remoteUrl = "https://srv/a.jpg")), back.photos)
        assertEquals(SyncStatus.EMAILED, back.syncStatus)
        assertFalse("pulled visits belong to someone else", back.ownedLocally)
        assertFalse(back.remoteDirty)
        assertNull("local paths are never sent", back.signaturePath)
        assertFalse(json.toString().contains("/local/"))
    }

    @Test
    fun `json without an id is ignored`() {
        assertNull(RecordMapper.jsonToVisit(JSONObject().put("clientName", "x")))
        assertNull(RecordMapper.jsonToClient(JSONObject().put("name", "x")))
    }

    @Test
    fun `unknown visit type and status fall back safely`() {
        val v = RecordMapper.jsonToVisit(JSONObject().put("id", "x").put("visitType", "Gardening").put("syncStatus", "???"))!!
        assertEquals(VisitType.OTHER, v.type)
        assertEquals(SyncStatus.EMAILED, v.syncStatus)
    }

    @Test
    fun `client round trip keeps a missing hourly rate as null`() {
        val client = ClientEntity(id = "c-1", name = "Acme", email = "a@acme.test", hourlyRate = null, updatedAt = 5L)
        val back = RecordMapper.jsonToClient(JSONObject(RecordMapper.clientToJson(client).toString()))!!
        assertNull(back.hourlyRate)
        assertEquals("Acme", back.name)

        val withRate = RecordMapper.jsonToClient(JSONObject(RecordMapper.clientToJson(client.copy(hourlyRate = 250.0)).toString()))!!
        assertEquals(250.0, withRate.hourlyRate!!, 0.0)
    }
}
