package com.company.supportvisit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationUtilsTest {

    @Test
    fun `end after start is valid`() {
        assertTrue(ValidationUtils.isEndAfterStart("09:00", "09:01"))
    }

    @Test
    fun `end equal to or before start is invalid`() {
        assertFalse(ValidationUtils.isEndAfterStart("10:00", "10:00"))
        assertFalse(ValidationUtils.isEndAfterStart("10:00", "09:59"))
    }

    @Test
    fun `malformed times are invalid`() {
        assertFalse(ValidationUtils.isEndAfterStart("", "10:00"))
        assertFalse(ValidationUtils.isEndAfterStart("9am", "10:00"))
        assertFalse(ValidationUtils.isEndAfterStart("09:00", "25:00"))
    }

    @Test
    fun `email validation`() {
        assertTrue(ValidationUtils.isValidEmail("tech.support+visits@example.co.il"))
        assertTrue(ValidationUtils.isValidEmail("  padded@example.com  "))
        assertFalse(ValidationUtils.isValidEmail("no-at-sign.example.com"))
        assertFalse(ValidationUtils.isValidEmail("missing@tld"))
        assertFalse(ValidationUtils.isValidEmail(""))
    }

    @Test
    fun `validateVisit reports the first problem`() {
        fun validate(
            client: String = "Acme",
            email: String = "a@b.com",
            start: String = "09:00",
            end: String = "10:00",
            signed: Boolean = true
        ) = ValidationUtils.validateVisit(client, email, start, end, signed)

        assertEquals(VisitValidation.VALID, validate())
        assertEquals(VisitValidation.MISSING_FIELDS, validate(client = " "))
        assertEquals(VisitValidation.MISSING_FIELDS, validate(end = ""))
        assertEquals(VisitValidation.INVALID_EMAIL, validate(email = "nope"))
        assertEquals(VisitValidation.END_BEFORE_START, validate(start = "11:00"))
        assertEquals(VisitValidation.MISSING_SIGNATURE, validate(signed = false))
    }
}
