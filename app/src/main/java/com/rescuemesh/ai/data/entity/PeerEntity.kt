package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks a known peer device and its connection/sync state.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,            // Unique device identifier
    val endpointId: String? = null,              // Nearby Connections endpoint ID (changes per session)
    val displayName: String,
    val isConnected: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val latestKnownSequences: String? = null     // JSON: {"deviceId": lastSeq, ...}
)
