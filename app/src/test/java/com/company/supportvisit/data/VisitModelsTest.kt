package com.company.supportvisit.data

import com.company.supportvisit.utils.PhotoCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitModelsTest {

    @Test
    fun `parts total multiplies quantity by unit price`() {
        val parts = listOf(PartItem("Cable", 2.0, 15.5), PartItem("Switch", 1.0, 120.0), PartItem("Screws", 0.5, 10.0))
        assertEquals(156.0, parts.partsTotal(), 0.0001)
        assertEquals(0.0, emptyList<PartItem>().partsTotal(), 0.0)
    }

    @Test
    fun `parts and photos survive the database JSON columns`() {
        val parts = listOf(PartItem("Cable \"Cat6\"", 3.0, 9.9))
        assertEquals(parts, JsonCodec.partsFromJson(JsonCodec.partsToJson(parts)))

        val photos = listOf(VisitPhoto("/a.jpg", null), VisitPhoto(null, "https://x/b.jpg"))
        assertEquals(photos, JsonCodec.photosFromJson(JsonCodec.photosToJson(photos)))
    }

    @Test
    fun `broken JSON columns read as empty instead of crashing`() {
        assertTrue(JsonCodec.partsFromJson("{not json").isEmpty())
        assertTrue(JsonCodec.photosFromJson(null).isEmpty())
        assertTrue(JsonCodec.stringsFromJson("").isEmpty())
    }

    @Test
    fun `visit type keys are case insensitive with Other as fallback`() {
        assertEquals(VisitType.REMOTE, VisitType.fromKey("remote"))
        assertEquals(VisitType.OTHER, VisitType.fromKey(null))
        assertEquals(VisitType.OTHER, VisitType.fromKey("unknown"))
    }

    @Test
    fun `photo sample size keeps at least the target resolution`() {
        assertEquals(1, PhotoCompressor.sampleSize(1600, 1200, 1600))
        assertEquals(2, PhotoCompressor.sampleSize(4000, 3000, 1600))
        assertEquals(4, PhotoCompressor.sampleSize(8000, 6000, 1600))
    }
}
