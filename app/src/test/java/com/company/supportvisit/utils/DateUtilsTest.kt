package com.company.supportvisit.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DateUtilsTest {

    @Test
    fun `legacy dd-MM-yyyy dates convert to ISO`() {
        assertEquals("2026-07-28", DateUtils.legacyToIso("28/07/2026"))
        assertEquals("2026-01-05", DateUtils.legacyToIso(" 05/01/2026 "))
    }

    @Test
    fun `ISO or unparsable dates are returned unchanged`() {
        assertEquals("2026-07-28", DateUtils.legacyToIso("2026-07-28"))
        assertEquals("", DateUtils.legacyToIso(""))
        assertEquals("garbage", DateUtils.legacyToIso("garbage"))
    }

    @Test
    fun `display format follows the locale`() {
        assertEquals("Jul 28, 2026", DateUtils.formatForDisplay("2026-07-28", Locale.US))
    }

    @Test
    fun `display of an invalid date falls back to the raw value`() {
        assertEquals("28/07/2026", DateUtils.formatForDisplay("28/07/2026", Locale.US))
    }
}
