package com.company.supportvisit.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A customer company, shared with the team through the Support Visit server. */
@Entity(tableName = "clients", indices = [Index("name")])
data class ClientEntity(
    @PrimaryKey val id: String,
    val name: String,
    val contactPerson: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    /** Hourly rate in the company currency, if agreed. */
    val hourlyRate: Double? = null,
    val notes: String = "",
    val remoteDirty: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
