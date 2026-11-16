package com.company.supportvisit.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A to-do: personal, or assigned to a teammate. Synced through the server. */
@Entity(tableName = "tasks", indices = [Index("done"), Index("assigneeId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String = "",
    /** ISO yyyy-MM-dd, optional. */
    val dueDate: String? = null,
    val clientId: String? = null,
    /** Microsoft user id of the person who should do it (null = the creator). */
    val assigneeId: String? = null,
    val assigneeName: String? = null,
    val createdById: String? = null,
    val createdByName: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val remoteDirty: Boolean = true
)
