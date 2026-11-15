package com.company.supportvisit.data

import androidx.room.ColumnInfo
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
    val updatedAt: Long = System.currentTimeMillis(),

    // --- Added in database version 2 ---
    val clientId: String? = null,
    @ColumnInfo(defaultValue = "Other")
    val visitType: String = VisitType.OTHER.key,
    val technicianSignaturePath: String? = null,
    /** Local paths of compressed photos. */
    @ColumnInfo(defaultValue = "[]")
    val photoPaths: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "[]")
    val parts: List<PartItem> = emptyList(),

    // --- Added in database version 3 ---
    @ColumnInfo(defaultValue = "0")
    val breakMinutes: Int = 0,
    val billableMinutesOverride: Int? = null
) {
    fun isEmpty(): Boolean =
        clientName.isBlank() && clientEmail.isBlank() && startTime.isBlank() &&
            endTime.isBlank() && workDescription.isBlank() && signaturePath == null &&
            technicianSignaturePath == null && photoPaths.isEmpty() && parts.isEmpty() &&
            breakMinutes == 0 && billableMinutesOverride == null

    companion object {
        const val SINGLE_DRAFT_ID = 1
    }
}
