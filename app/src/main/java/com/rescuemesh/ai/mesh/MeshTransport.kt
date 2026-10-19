package com.rescuemesh.ai.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncMessageEnvelope
import com.rescuemesh.ai.model.SyncObservation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Core mesh transport layer wrapping Google Nearby Connections API.
 *
 * Uses P2P_CLUSTER strategy for multi-peer connections.
 * Each device simultaneously advertises AND discovers,
 * forming an ad-hoc mesh with no central coordinator.
 *
 * This class handles:
 * - Discovery and connection management
 * - Serialization/deserialization of sync messages
 * - Tracking connected peers
 * - Exposing incoming messages as a SharedFlow
 */
class MeshTransport(private val context: Context) {

    companion object {
        private const val TAG = "MeshTransport"
        private const val SERVICE_ID = "com.rescuemesh.ai.MESH"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- Public State ---

    private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

    private val _meshState = MutableStateFlow(MeshState.STOPPED)
    val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

    private var localDeviceName: String = "Device"
    private var localDeviceId: String = ""

    // Track endpoint→deviceId mapping (endpoint IDs change per session)
    private val endpointToDeviceId = mutableMapOf<String, String>()

    // Callbacks for peer connections that resolve after Hello exchange
    private var onPeerConnected: ((String, PeerInfo) -> Unit)? = null
    private var onPeerDisconnected: ((String) -> Unit)? = null

    // --- Lifecycle ---

    fun startMesh(deviceName: String, deviceId: String) {
        localDeviceName = deviceName
        localDeviceId = deviceId
        _meshState.value = MeshState.STARTING

        startAdvertising()
        startDiscovering()

        _meshState.value = MeshState.ACTIVE
        Log.i(TAG, "Mesh started: advertising and discovering as '$deviceName'")
    }

    fun stopMesh() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        _connectedPeers.value = emptyMap()
        endpointToDeviceId.clear()
        _meshState.value = MeshState.STOPPED

        Log.i(TAG, "Mesh stopped")
    }

    fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit) {
        onPeerConnected = callback
    }

    fun setOnPeerDisconnected(callback: (String) -> Unit) {
        onPeerDisconnected = callback
    }

    // --- Sending ---

    fun sendMessage(endpointId: String, message: SyncMessage): Boolean {
        return try {
            val payload = serializeMessage(message)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
            Log.d(TAG, "Sent ${message::class.simpleName} to $endpointId (${bytes.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to $endpointId", e)
            false
        }
    }

    fun broadcastMessage(message: SyncMessage, excludeEndpoint: String? = null) {
        val targets = _connectedPeers.value.values
            .filter { it.endpointId != excludeEndpoint }
            .map { it.endpointId }

        for (endpoint in targets) {
            sendMessage(endpoint, message)
        }
    }

    fun getEndpointForDevice(deviceId: String): String? {
        return _connectedPeers.value[deviceId]?.endpointId
    }

    fun getBytesSent(message: SyncMessage): Int {
        return serializeMessage(message).toByteArray(Charsets.UTF_8).size
    }

    // --- Advertising ---

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startAdvertising(
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

        connectionsClient.startDiscovery(
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

            // Only request connection if we don't already have one to this endpoint
            if (_connectedPeers.value.values.none { it.endpointId == endpointId }) {
                connectionsClient.requestConnection(
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
            // Auto-accept connections (for a prototype; production should verify auth token)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "Connected to $endpointId")
                    // Connection established — send Hello to identify ourselves
                    val hello = SyncMessage.Hello(
                        deviceId = localDeviceId,
                        deviceName = localDeviceName,
                        missionId = "" // Will be set by SyncCoordinator
                    )
                    sendMessage(endpointId, hello)
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
            val deviceId = endpointToDeviceId.remove(endpointId)

            if (deviceId != null) {
                _connectedPeers.value = _connectedPeers.value - deviceId
                onPeerDisconnected?.invoke(deviceId)
            } else {
                // Try to find by endpoint ID
                val entry = _connectedPeers.value.entries.find { it.value.endpointId == endpointId }
                if (entry != null) {
                    _connectedPeers.value = _connectedPeers.value - entry.key
                    onPeerDisconnected?.invoke(entry.key)
                }
            }
        }
    }

    // --- Payload (Data) Callback ---

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return

            val bytes = payload.asBytes() ?: return
            val rawJson = String(bytes, Charsets.UTF_8)

            try {
                val message = deserializeMessage(rawJson)

                // Handle Hello specially — register the peer
                if (message is SyncMessage.Hello) {
                    handleHello(endpointId, message)
                }

                _incomingMessages.tryEmit(endpointId to message)
                Log.d(TAG, "Received ${message::class.simpleName} from $endpointId (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize message from $endpointId", e)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // For BYTES payloads, transfer is instant
        }
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

    // --- Serialization ---

    private fun serializeMessage(message: SyncMessage): String {
        val payload = when (message) {
            is SyncMessage.Hello -> json.encodeToString(message)
            is SyncMessage.StateDigest -> json.encodeToString(message)
            is SyncMessage.EventRequest -> json.encodeToString(message)
            is SyncMessage.EventBatch -> json.encodeToString(message)
            is SyncMessage.EventAck -> json.encodeToString(message)
        }
        return json.encodeToString(SyncMessageEnvelope(type = message.type, payload = payload))
    }

    private fun deserializeMessage(rawJson: String): SyncMessage {
        val envelope = json.decodeFromString<SyncMessageEnvelope>(rawJson)
        return when (envelope.type) {
            "HELLO" -> json.decodeFromString<SyncMessage.Hello>(envelope.payload)
            "STATE_DIGEST" -> json.decodeFromString<SyncMessage.StateDigest>(envelope.payload)
            "EVENT_REQUEST" -> json.decodeFromString<SyncMessage.EventRequest>(envelope.payload)
            "EVENT_BATCH" -> json.decodeFromString<SyncMessage.EventBatch>(envelope.payload)
            "EVENT_ACK" -> json.decodeFromString<SyncMessage.EventAck>(envelope.payload)
            else -> throw IllegalArgumentException("Unknown message type: ${envelope.type}")
        }
    }
}

enum class MeshState {
    STOPPED,
    STARTING,
    ACTIVE,
    ERROR
}
