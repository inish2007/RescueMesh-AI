package com.rescuemesh.ai.fakes

import com.rescuemesh.ai.data.dao.PeerDao
import com.rescuemesh.ai.data.entity.PeerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory thread-safe fake DAO for tracking known mesh peers and session states.
 * All mutations utilize atomic operations ([computeIfPresent]) on [ConcurrentHashMap]
 * to prevent race conditions during concurrent connection events and stats accumulation.
 */
class FakePeerDao : PeerDao {
    private val peers = ConcurrentHashMap<String, PeerEntity>()

    override fun observeAll(): Flow<List<PeerEntity>> {
        return flowOf(peers.values.sortedByDescending { it.lastSeenAt })
    }

    override fun observeConnected(): Flow<List<PeerEntity>> {
        return flowOf(peers.values.filter { it.isConnected })
    }

    override suspend fun getConnected(): List<PeerEntity> {
        return peers.values.filter { it.isConnected }
    }

    override suspend fun getById(deviceId: String): PeerEntity? {
        return peers[deviceId]
    }

    override suspend fun getByEndpointId(endpointId: String): PeerEntity? {
        return peers.values.firstOrNull { it.endpointId == endpointId }
    }

    override suspend fun upsert(peer: PeerEntity) {
        peers[peer.deviceId] = peer
    }

    override suspend fun markDisconnected(endpointId: String) {
        for ((id, peer) in peers) {
            if (peer.endpointId == endpointId) {
                peers.computeIfPresent(id) { _, current ->
                    if (current.endpointId == endpointId) current.copy(isConnected = false) else current
                }
            }
        }
    }

    override suspend fun markAllDisconnected() {
        for (id in peers.keys) {
            peers.computeIfPresent(id) { _, current ->
                current.copy(isConnected = false)
            }
        }
    }

    override suspend fun updateSyncState(deviceId: String, syncAt: Long, sequences: String) {
        peers.computeIfPresent(deviceId) { _, current ->
            current.copy(
                lastSyncAt = syncAt,
                latestKnownSequences = sequences
            )
        }
    }

    override suspend fun addSentStats(deviceId: String, bytes: Long, count: Int) {
        peers.computeIfPresent(deviceId) { _, current ->
            current.copy(
                bytesSent = current.bytesSent + bytes,
                messagesSent = current.messagesSent + count
            )
        }
    }

    override suspend fun addReceivedStats(deviceId: String, bytes: Long, count: Int) {
        peers.computeIfPresent(deviceId) { _, current ->
            current.copy(
                bytesReceived = current.bytesReceived + bytes,
                messagesReceived = current.messagesReceived + count
            )
        }
    }

    override fun observeConnectedCount(): Flow<Int> {
        return flowOf(peers.values.count { it.isConnected })
    }

    fun clear() {
        peers.clear()
    }
}
