package com.company.supportvisit.data

import androidx.room.ColumnInfo
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
    /** URL of the uploaded PDF (on the Support Visit server, or OneDrive if no server is set up). */
    val pdfLink: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    /** Status to resume from when a FAILED visit is retried. */
    val failedAtStatus: SyncStatus? = null,
    val syncError: SyncError? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // --- Added in database version 2 ---

    /** The client picked from the client list, if any (null when typed by hand). */
    val clientId: String? = null,
    @ColumnInfo(defaultValue = "Other")
    val visitType: String = VisitType.OTHER.key,
    /** Optional local PNG of the technician's signature. */
    val technicianSignaturePath: String? = null,
    @ColumnInfo(defaultValue = "[]")
    val photos: List<VisitPhoto> = emptyList(),
    @ColumnInfo(defaultValue = "[]")
    val parts: List<PartItem> = emptyList(),
    /** True when local changes still need to be sent to the server. */
    @ColumnInfo(defaultValue = "1")
    val remoteDirty: Boolean = true,
    /**
     * True for visits created on this device. Visits pulled from the server (other technicians)
     * are never uploaded or emailed from here.
     */
    @ColumnInfo(defaultValue = "1")
    val ownedLocally: Boolean = true
) {
    val type: VisitType get() = VisitType.fromKey(visitType)
}
