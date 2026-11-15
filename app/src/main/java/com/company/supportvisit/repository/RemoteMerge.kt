package com.company.supportvisit.repository

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity

/**
 * Rules for merging what the server returns with what's on the phone (matched by id).
 * Each function returns the row to save, or null to keep the local row unchanged.
 */
object RemoteMerge {

    fun visit(local: VisitEntity?, remote: VisitEntity): VisitEntity? {
        if (local == null) return remote

        // This device is still working on the visit (sending it, or has changes not yet
        // uploaded): the local copy wins.
        if (local.ownedLocally && (local.remoteDirty || local.syncStatus != SyncStatus.EMAILED)) return null

        val merged = remote.copy(
            ownedLocally = local.ownedLocally,
            createdAt = if (remote.createdAt > 0) remote.createdAt else local.createdAt,
            // Keep anything still on disk (normally nothing once a visit is sent).
            signaturePath = local.signaturePath,
            technicianSignaturePath = local.technicianSignaturePath,
            pdfPath = local.pdfPath
        )
        return if (merged == local) null else merged
    }

    fun client(local: ClientEntity?, remote: ClientEntity): ClientEntity? {
        if (local == null) return remote
        if (local.remoteDirty) return null
        return if (remote == local) null else remote
    }
}
