package com.company.supportvisit.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "visits",
    indices = [Index("syncStatus"), Index("visitDate")]
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val clientName: String,
    val clientEmail: String,
    /** ISO yyyy-MM-dd. Formatted for the user's locale only when displayed. */
    val visitDate: String,
    /** HH:mm */
    val startTime: String,
    /** HH:mm */
    val endTime: String,
    val workDescription: String,
    /** Email of the signed-in technician at submit time; also gets a copy of the report. */
    val technicianEmail: String? = null,
    /** Local PNG of the client signature, kept until the visit is fully synced. */
    val signaturePath: String? = null,
    /** Local PDF, kept until the visit is fully synced. */
    val pdfPath: String? = null,
    /** OneDrive web URL once uploaded. */
    val pdfLink: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    /** Status to resume from when a FAILED visit is retried. */
    val failedAtStatus: SyncStatus? = null,
    val syncError: SyncError? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
