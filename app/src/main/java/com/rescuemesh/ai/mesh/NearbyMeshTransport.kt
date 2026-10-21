package com.rescuemesh.ai.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncMessageCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Concrete mesh transport layer wrapping Google Nearby Connections API.
 *
 * Uses P2P_CLUSTER strategy for multi-peer connections.
 * Each device simultaneously advertises AND discovers,
 * forming an ad-hoc mesh with no central coordinator.
 *
 * Enforces dynamic mission authentication:
 * - Peers exchange [SyncMessage.Hello] carrying the active mission ID.
 * - Mismatched, empty, or corrupted mission IDs are immediately rejected and disconnected.
 * - Unauthenticated endpoints are barred from sending, receiving, or relaying observations.
 */
class NearbyMeshTransport(
    private val context: Context? = null,
    val missionIdProvider: () -> String = { "" },
    nearbyConnectionsOverride: ConnectionsClient? = null
) : MeshTransport {

    // Secondary constructors for backward compatibility
    constructor(context: Context) : this(context, { "" }, null)
    constructor(context: Context, missionId: String) : this(context, { missionId }, null)
    constructor(context: Context, missionIdProvider: () -> String) : this(context, missionIdProvider, null)

    companion object {
        private const val TAG = "NearbyMeshTransport"
        private const val SERVICE_ID = "com.rescuemesh.ai.MESH"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    val activeMissionId: String
        get() = missionIdProvider()

    internal val nearbyConnections: ConnectionsClient =
        nearbyConnectionsOverride ?: (context?.let { Nearby.getConnectionsClient(it) }
            ?: throw IllegalStateException("Context required when nearbyConnectionsOverride is null"))
    private val totalBytesSent = AtomicLong(0L)

    // Track endpoints that have successfully completed the mission authentication handshake
    val connectedEndpoints: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // --- Public State ---

    private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

    private val _meshState = MutableStateFlow(MeshState.STOPPED)
    override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

    private var localDeviceName: String = "Device"
    private var localDeviceId: String = ""

    // Track endpoint→deviceId mapping (endpoint IDs change per session)
    private val endpointToDeviceId = ConcurrentHashMap<String, String>()

    // Callbacks for peer connections that resolve after Hello exchange
    private var onPeerConnected: ((String, PeerInfo) -> Unit)? = null
    private var onPeerDisconnected: ((String) -> Unit)? = null

    // --- Lifecycle ---

    override fun startAdvertisingAndDiscovery(deviceName: String, role: String) {
        localDeviceName = deviceName
        localDeviceId = role
        _meshState.value = MeshState.STARTING

        startAdvertising()
        startDiscovering()

        _meshState.value = MeshState.ACTIVE
        Log.i(TAG, "Mesh started: advertising and discovering as '$deviceName' ($role) for mission '$activeMissionId'")
    }

    override fun stop() {
        nearbyConnections.stopAdvertising()
        nearbyConnections.stopDiscovery()
        nearbyConnections.stopAllEndpoints()

        connectedEndpoints.clear()
        _connectedPeers.value = emptyMap()
        endpointToDeviceId.clear()
        _meshState.value = MeshState.STOPPED

        Log.i(TAG, "Mesh stopped")
    }

    override fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit) {
        onPeerConnected = callback
    }

    override fun setOnPeerDisconnected(callback: (String) -> Unit) {
        onPeerDisconnected = callback
    }

    // --- Sending ---

    private fun sendPayloadDirectly(endpointId: String, message: SyncMessage): Boolean {
        return try {
            val bytes = SyncMessageCodec.serialize(message)
            totalBytesSent.addAndGet(bytes.size.toLong())
            nearbyConnections.sendPayload(endpointId, Payload.fromBytes(bytes))
            Log.d(TAG, "Sent ${message::class.simpleName} to $endpointId (${bytes.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to $endpointId", e)
            false
        }
    }

    override suspend fun sendMessage(endpointId: String, message: SyncMessage): Boolean {
        if (message !is SyncMessage.Hello && !connectedEndpoints.contains(endpointId)) {
            Log.w(TAG, "Cannot send ${message::class.simpleName} to unauthenticated endpoint $endpointId")
            return false
        }
        return sendPayloadDirectly(endpointId, message)
    }

    override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
        val targets = _connectedPeers.value.values
            .filter { it.endpointId != excludeEndpoint && connectedEndpoints.contains(it.endpointId) }
            .map { it.endpointId }

        var successCount = 0
        for (endpoint in targets) {
            if (sendPayloadDirectly(endpoint, message)) {
                successCount++
            }
        }
        return successCount
    }

    override fun getEndpointForDevice(deviceId: String): String? {
        return _connectedPeers.value[deviceId]?.endpointId
    }

    override fun getBytesSent(): Long = totalBytesSent.get()

    override fun getBytesSent(message: SyncMessage): Int {
        return SyncMessageCodec.calculatePayloadBytes(message)
    }

    // --- Advertising ---

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        nearbyConnections.startAdvertising(
            localDeviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.i(TAG, "Advertising started")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start advertising", e)
            _meshState.value = MeshState.ERROR
        }
    }

    // --- Discovery ---

    private fun startDiscovering() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        nearbyConnections.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.i(TAG, "Discovery started")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    // --- Discovery Callback ---

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Endpoint found: $endpointId (${info.endpointName})")

            if (_connectedPeers.value.values.none { it.endpointId == endpointId }) {
                nearbyConnections.requestConnection(
                    localDeviceName,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnSuccessListener {
                    Log.d(TAG, "Connection requested to $endpointId")
                }.addOnFailureListener { e ->
                    Log.w(TAG, "Failed to request connection to $endpointId", e)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
        }
    }

    // --- Connection Lifecycle ---

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated with $endpointId (${info.endpointName})")
            nearbyConnections.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "Connected to $endpointId")
                    val hello = SyncMessage.Hello(
                        deviceId = localDeviceId,
                        deviceName = localDeviceName,
                        missionId = activeMissionId
                    )
                    sendPayloadDirectly(endpointId, hello)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by $endpointId")
                }
                else -> {
                    Log.e(TAG, "Connection failed with $endpointId: ${result.status}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Disconnected from $endpointId")
            connectedEndpoints.remove(endpointId)
            val deviceId = endpointToDeviceId.remove(endpointId)

            if (deviceId != null) {
                _connectedPeers.value = _connectedPeers.value - deviceId
                onPeerDisconnected?.invoke(deviceId)
            } else {
                val entry = _connectedPeers.value.entries.find { it.value.endpointId == endpointId }
                if (entry != null) {
                    _connectedPeers.value = _connectedPeers.value - entry.key
                    onPeerDisconnected?.invoke(entry.key)
                }
            }
        }
    }

    // --- Payload Callback & Incoming Processing ---

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return

            val bytes = payload.asBytes() ?: return

            try {
                val message = SyncMessageCodec.deserialize(bytes)
                handleIncomingMessage(endpointId, message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize message from $endpointId", e)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Instant for BYTES payloads
        }
    }

    /**
     * Handles an incoming message with mission isolation and handshake authentication guards.
     * Unauthenticated endpoints must never receive or relay observations.
     */
    fun handleIncomingMessage(endpointId: String, message: SyncMessage) {
        if (message is SyncMessage.Hello) {
            val hello = message
            if (hello.missionId.isBlank() || activeMissionId.isBlank() || hello.missionId != activeMissionId) {
                Log.w(TAG, "Rejected peer ${hello.deviceId}: foreign mission ${hello.missionId} != active $activeMissionId")
                nearbyConnections.disconnectFromEndpoint(endpointId)
                connectedEndpoints.remove(endpointId)
                endpointToDeviceId.remove(endpointId)
                _connectedPeers.value = _connectedPeers.value - hello.deviceId
                return
            }

            connectedEndpoints.add(endpointId)
            handleHello(endpointId, hello)
            _incomingMessages.tryEmit(endpointId to hello)
            Log.d(TAG, "Authenticated peer ${hello.deviceId} at $endpointId for mission $activeMissionId")
            return
        }

        // Handshake Mission Isolation & Authentication Guard:
        // Unauthenticated endpoints must never receive or relay observations or other sync messages
        if (!connectedEndpoints.contains(endpointId)) {
            Log.w(TAG, "Rejected message ${message::class.simpleName} from unauthenticated endpoint $endpointId")
            return
        }

        _incomingMessages.tryEmit(endpointId to message)
        Log.d(TAG, "Received ${message::class.simpleName} from $endpointId")
    }

    private fun handleHello(endpointId: String, hello: SyncMessage.Hello) {
        val peerInfo = PeerInfo(
            deviceId = hello.deviceId,
            endpointId = endpointId,
            displayName = hello.deviceName
        )

        endpointToDeviceId[endpointId] = hello.deviceId
        _connectedPeers.value = _connectedPeers.value + (hello.deviceId to peerInfo)

        onPeerConnected?.invoke(hello.deviceId, peerInfo)
        Log.i(TAG, "Peer registered: ${hello.deviceName} (${hello.deviceId}) at endpoint $endpointId")
    }
}
