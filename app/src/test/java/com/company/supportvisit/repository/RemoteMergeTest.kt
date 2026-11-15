package com.company.supportvisit.repository

import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMergeTest {

    private fun visit(
        status: SyncStatus = SyncStatus.EMAILED,
        dirty: Boolean = false,
        owned: Boolean = true,
        description: String = "local"
    ) = VisitEntity(
        id = "v1", clientName = "Acme", clientEmail = "a@b.c", visitDate = "2026-11-15",
        startTime = "09:00", endTime = "10:00", workDescription = description,
        syncStatus = status, remoteDirty = dirty, ownedLocally = owned, createdAt = 10L
    )

    private val remote = visit(description = "remote", owned = false).copy(createdAt = 20L)

    @Test
    fun `new remote visit is inserted`() {
        assertEquals(remote, RemoteMerge.visit(null, remote))
    }

    @Test
    fun `local visit still being sent is not overwritten`() {
        assertNull(RemoteMerge.visit(visit(status = SyncStatus.PENDING), remote))
        assertNull(RemoteMerge.visit(visit(status = SyncStatus.FAILED), remote))
    }

    @Test
    fun `local visit with unsent changes is not overwritten`() {
        assertNull(RemoteMerge.visit(visit(dirty = true), remote))
    }

    @Test
    fun `finished local visit takes the server copy but stays owned locally`() {
        val merged = RemoteMerge.visit(visit(), remote)!!
        assertEquals("remote", merged.workDescription)
        assertEquals(true, merged.ownedLocally)
    }

    @Test
    fun `identical visit needs no write`() {
        val local = remote.copy(ownedLocally = false)
        assertNull(RemoteMerge.visit(local, remote))
    }

    @Test
    fun `client with local edits wins, otherwise server wins`() {
        val local = ClientEntity(id = "c1", name = "Local", remoteDirty = true, updatedAt = 1L)
        val server = ClientEntity(id = "c1", name = "Server", remoteDirty = false, updatedAt = 2L)
        assertNull(RemoteMerge.client(local, server))
        assertEquals(server, RemoteMerge.client(local.copy(remoteDirty = false), server))
        assertEquals(server, RemoteMerge.client(null, server))
    }
}
