package com.company.supportvisit.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The single in-progress New Visit form, auto-saved so an interrupted technician loses nothing. */
@Entity(tableName = "draft")
data class DraftEntity(
    @PrimaryKey val id: Int = SINGLE_DRAFT_ID,
    val clientName: String = "",
    val clientEmail: String = "",
    val visitDate: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val workDescription: String = "",
    val signaturePath: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isEmpty(): Boolean =
        clientName.isBlank() && clientEmail.isBlank() && startTime.isBlank() &&
            endTime.isBlank() && workDescription.isBlank() && signaturePath == null

    companion object {
        const val SINGLE_DRAFT_ID = 1
    }
}
