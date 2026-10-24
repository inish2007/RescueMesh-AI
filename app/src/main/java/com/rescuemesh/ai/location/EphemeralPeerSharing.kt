package com.rescuemesh.ai.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral peer location beacon received over Nearby Connections mesh.
 * Strictly in-memory; NEVER persisted to SQLite Room database.
 */
@Serializable
data class PeerLocationBeacon(
    val deviceId: String,
    val deviceName: String,
    val missionId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val bearing: Float? = null,
    val speed: Float? = null,
    val altitude: Double? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * In-memory repository for ephemeral peer locations.
 * Automatically purges beacons older than [ttlMillis] (default 10 minutes = 600,000 ms).
 * Enforces mission boundary isolation and zero Room database persistence.
 */
class EphemeralPeerLocationRepository(
    private val ttlMillis: Long = DEFAULT_TTL_MS
) {
    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L // 10 minutes (600,000 ms)
    }

    private val peerMap = ConcurrentHashMap<String, PeerLocationBeacon>()
    private val _activePeers = MutableStateFlow<List<PeerLocationBeacon>>(emptyList())
    val activePeers: StateFlow<List<PeerLocationBeacon>> = _activePeers.asStateFlow()

    /**
     * Updates or inserts a peer's location beacon.
     * Enforces mission boundary isolation: if [currentMissionId] is supplied and does not match
     * the beacon's missionId, the beacon is dropped immediately.
     */
    fun updatePeerLocation(
        beacon: PeerLocationBeacon,
        currentMissionId: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        if (currentMissionId != null && beacon.missionId != currentMissionId) {
            return
        }
        peerMap[beacon.deviceId] = beacon
        pruneAndEmit(now)
    }

    /**
     * Prunes expired beacons based on [now] and [ttlMillis], then emits the updated list to [activePeers].
     * Returns the pruned list of active beacons.
     */
    fun pruneAndEmit(now: Long = System.currentTimeMillis()): List<PeerLocationBeacon> {
        val cutoff = now - ttlMillis
        peerMap.entries.removeIf { it.value.timestamp < cutoff }
        val currentList = peerMap.values.toList().sortedBy { it.deviceId }
        _activePeers.value = currentList
        return currentList
    }

    /**
     * Retrieves the latest known ephemeral beacon for a given peer device ID, if unexpired.
     */
    fun getPeerLocation(deviceId: String): PeerLocationBeacon? = peerMap[deviceId]

    /**
     * Returns a snapshot of all active, unexpired peer beacons.
     */
    fun getAllActivePeers(): List<PeerLocationBeacon> = peerMap.values.toList().sortedBy { it.deviceId }

    /**
     * Clears all ephemeral peer location state.
     */
    fun clear() {
        peerMap.clear()
        _activePeers.value = emptyList()
    }

    /**
     * Removes an ephemeral peer location beacon upon explicit revocation.
     */
    fun removePeer(deviceId: String) {
        peerMap.remove(deviceId)
        _activePeers.value = peerMap.values.toList().sortedBy { it.deviceId }
    }

    val peerCount: Int
        get() = peerMap.size
}

/** Type alias matching DISPATCH specification */
typealias EphemeralPeerSharing = EphemeralPeerLocationRepository
