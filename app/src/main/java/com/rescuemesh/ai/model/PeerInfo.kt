package com.rescuemesh.ai.model

/**
 * Runtime representation of a connected peer.
 * Not persisted — derived from PeerEntity + live connection state.
 */
data class PeerInfo(
    val deviceId: String,
    val endpointId: String,
    val displayName: String,
    val isConnected: Boolean = true,
    val connectedSince: Long = System.currentTimeMillis(),
    val lastSyncAt: Long? = null,
    val pendingOutboxCount: Int = 0
)
