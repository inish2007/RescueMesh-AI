package com.rescuemesh.ai.mesh

import android.content.Context
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State of the local mesh radio and discovery subsystem.
 */
enum class MeshState {
    STOPPED,
    STARTING,
    ACTIVE,
    ERROR
}

/**
 * Core mesh transport abstraction for RescueMesh AI.
 *
 * Defines the contract for ad-hoc peer-to-peer communication, peer discovery,
 * and state synchronization across devices without central infrastructure.
 *
 * Implemented by:
 * - [NearbyMeshTransport] for physical Android Nearby Connections (P2P_CLUSTER)
 * - [VirtualMeshTransport] for headless multi-node in-memory simulation
 */
interface MeshTransport {

    /**
     * Map of currently connected peer device IDs to their [PeerInfo].
     */
    val connectedPeers: StateFlow<Map<String, PeerInfo>>

    /**
     * Shared flow of received (endpointId, SyncMessage) pairs.
     */
    val incomingMessages: SharedFlow<Pair<String, SyncMessage>>

    /**
     * Observable state of the mesh radio transport lifecycle.
     */
    val meshState: StateFlow<MeshState>

    /**
     * Starts advertising this node's presence and discovering nearby peers.
     *
     * @param deviceName Human-readable device display name.
     * @param role Operational role or unique identifier for the node.
     */
    fun startAdvertisingAndDiscovery(deviceName: String, role: String)

    /**
     * Backward-compatible lifecycle starter used by Android activities.
     * Delegates to [startAdvertisingAndDiscovery] by default.
     */
    fun startMesh(deviceName: String, deviceId: String) =
        startAdvertisingAndDiscovery(deviceName, deviceId)

    /**
     * Stops advertising, discovery, and disconnects all active peer sessions.
     */
    fun stop()

    /**
     * Backward-compatible lifecycle stopper used by Android activities.
     * Delegates to [stop] by default.
     */
    fun stopMesh() = stop()

    /**
     * Registers a callback invoked when a new peer connection is established.
     *
     * @param callback Lambda receiving the peer's device ID and [PeerInfo].
     */
    fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit)

    /**
     * Overload accepting single-parameter [PeerInfo] listener.
     */
    fun setOnPeerConnected(listener: (PeerInfo) -> Unit) =
        setOnPeerConnected { _, peerInfo -> listener(peerInfo) }

    /**
     * Registers a callback invoked when an existing peer disconnects.
     *
     * @param callback Lambda receiving the disconnected peer's device ID.
     */
    fun setOnPeerDisconnected(callback: (String) -> Unit)

    /**
     * Transmits a [SyncMessage] to a specific peer endpoint.
     *
     * @param endpointId Target peer endpoint identifier.
     * @param message Serialized protocol message to transmit.
     * @return True if message was successfully transmitted, false otherwise.
     */
    suspend fun sendMessage(endpointId: String, message: SyncMessage): Boolean

    /**
     * Broadcasts a [SyncMessage] to all connected peers, optionally excluding a source endpoint.
     *
     * @param message Message to broadcast.
     * @param excludeEndpoint Optional endpoint ID to exclude from broadcast (e.g. relay source).
     * @return Number of peers to which the message was successfully dispatched.
     */
    suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String? = null): Int

    /**
     * Resolves a persistent device ID to its current ephemeral session endpoint ID.
     *
     * @param deviceId Unique hardware/semi-stable device ID.
     * @return Current session endpoint ID, or null if peer is not connected.
     */
    fun getEndpointForDevice(deviceId: String): String?

    /**
     * Returns the cumulative total payload bytes transmitted by this transport instance.
     */
    fun getBytesSent(): Long

    /**
     * Computes the wire payload byte size of a specific [SyncMessage].
     * Retained for backward compatibility with [SyncCoordinator].
     */
    fun getBytesSent(message: SyncMessage): Int

    companion object {
        /**
         * Factory operator allowing `MeshTransport(context)` instantiation
         * for zero-breakage backward compatibility with existing DI setups.
         */
        operator fun invoke(context: Context): MeshTransport = NearbyMeshTransport(context)

        operator fun invoke(context: Context, missionIdProvider: () -> String): MeshTransport =
            NearbyMeshTransport(context, missionIdProvider)
    }
}
