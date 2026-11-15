package com.company.supportvisit.repository

import com.company.supportvisit.data.ClientDao
import com.company.supportvisit.data.ClientEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ClientRepository(private val dao: ClientDao) {

    fun observeClients(): Flow<List<ClientEntity>> = dao.observeAll()

    suspend fun getClients(): List<ClientEntity> = dao.getAll()

    suspend fun getClient(id: String): ClientEntity? = dao.getById(id)

    /** Saves a new or edited client and marks it for upload to the server. */
    suspend fun save(client: ClientEntity): ClientEntity {
        val saved = client.copy(
            id = client.id.ifBlank { UUID.randomUUID().toString() },
            name = client.name.trim(),
            email = client.email.trim(),
            remoteDirty = true,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(saved)
        return saved
    }

    suspend fun getRemoteDirty(): List<ClientEntity> = dao.getRemoteDirty()

    suspend fun markRemoteSynced(client: ClientEntity) {
        dao.markRemoteSynced(client.id, client.updatedAt)
    }

    /** Applies clients pulled from the server. */
    suspend fun mergeRemote(remote: List<ClientEntity>) {
        remote.forEach { r -> RemoteMerge.client(dao.getById(r.id), r)?.let { dao.upsert(it) } }
    }
}
