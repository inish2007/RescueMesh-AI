package com.rescuemesh.ai.simulation

import com.rescuemesh.ai.mesh.MeshState
import com.rescuemesh.ai.mesh.MeshTransport
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory implementation of [MeshTransport] for headless multi-node simulation.
 * Routes serialized messages through [VirtualMeshEnvironment].
 *
 * Adheres strictly to:
 * - Invariant 1: Strictly offline-first (zero external network/cloud calls).
 * - Invariant 2: Durable local-first persistence before transmission.
 */
class VirtualMeshTransport(
    val nodeId: String,
    val deviceName: String = nodeId,
    var environment: VirtualMeshEnvironment? = null
) : MeshTransport {

    private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(
        extraBufferCapacity = 2048
    )
    override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

    private val _meshState = MutableStateFlow(MeshState.STOPPED)
    override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

    private val peerConnectedCallbacks = CopyOnWriteArrayList<(String, PeerInfo) -> Unit>()
    private val peerDisconnectedCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    private val totalBytesSent = AtomicLong(0L)
    private val endpointToPeerMap = ConcurrentHashMap<String, String>()

    init {
        environment?.registerTransport(nodeId, this)
    }

    override fun startAdvertisingAndDiscovery(deviceName: String, role: String) {
        _meshState.value = MeshState.ACTIVE
    }

    override fun stop() {
        _meshState.value = MeshState.STOPPED
        _connectedPeers.value = emptyMap()
        endpointToPeerMap.clear()
    }

    override fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit) {
        peerConnectedCallbacks.add(callback)
    }

    override fun setOnPeerDisconnected(callback: (String) -> Unit) {
        peerDisconnectedCallbacks.add(callback)
    }

    override suspend fun sendMessage(endpointId: String, message: SyncMessage): Boolean {
        val bytes = getBytesSent(message)
        totalBytesSent.addAndGet(bytes.toLong())

        val env = environment ?: return false
        val targetNodeId = resolveEndpointToNodeId(endpointId)
        return env.deliverMessage(fromNodeId = nodeId, toNodeId = targetNodeId, message = message)
    }

    override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
        val bytes = getBytesSent(message)
        totalBytesSent.addAndGet(bytes.toLong())

        val env = environment ?: return 0
        return env.broadcastMessage(senderNodeId = nodeId, message = message, excludeEndpoint = excludeEndpoint)
    }

    override fun getEndpointForDevice(deviceId: String): String? {
        val peer = _connectedPeers.value[deviceId]
        return peer?.endpointId ?: "endpoint-$deviceId"
    }

    override fun getBytesSent(): Long = totalBytesSent.get()

    override fun getBytesSent(message: SyncMessage): Int {
        return SyncMessageCodec.calculatePayloadBytes(message)
    }

    fun onPeerConnected(peerDeviceId: String, peerInfo: PeerInfo) {
        val current = _connectedPeers.value.toMutableMap()
        current[peerDeviceId] = peerInfo
        endpointToPeerMap[peerInfo.endpointId] = peerDeviceId
        _connectedPeers.value = current
        for (cb in peerConnectedCallbacks) {
            cb(peerDeviceId, peerInfo)
        }
    }

    fun onPeerDisconnected(peerDeviceId: String) {
        val current = _connectedPeers.value.toMutableMap()
        val info = current.remove(peerDeviceId)
        if (info != null) {
            endpointToPeerMap.remove(info.endpointId)
        }
        _connectedPeers.value = current
        for (cb in peerDisconnectedCallbacks) {
            cb(peerDeviceId)
        }
    }

    suspend fun deliverIncoming(fromEndpointId: String, message: SyncMessage) {
        _incomingMessages.emit(fromEndpointId to message)
    }

    fun resolveEndpointToNodeId(endpointId: String): String {
        return endpointToPeerMap[endpointId]
            ?: if (endpointId.startsWith("endpoint-")) endpointId.removePrefix("endpoint-") else endpointId
    }
}
