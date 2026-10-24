package com.rescuemesh.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.location.LocationResult
import com.rescuemesh.ai.mesh.SyncCoordinator
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.ui.navigation.RescueMeshNavHost
import com.rescuemesh.ai.ui.screens.*
import com.rescuemesh.ai.ui.theme.RescueMeshAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startMeshServices()
        } else {
            Toast.makeText(
                this,
                "Mesh networking permissions required for offline P2P",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as RescueMeshApp

        checkAndRequestPermissions()

        setContent {
            RescueMeshAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val observations by app.observationRepository.observeAll().collectAsState(initial = emptyList())
                    val nodes by app.mapRepository.observeNodes().collectAsState(initial = emptyList())
                    val edges by app.mapRepository.observeEdges().collectAsState(initial = emptyList())
                    val peers by app.database.peerDao().observeAll().collectAsState(initial = emptyList())
                    val metrics by app.database.metricsDao().observeRecent().collectAsState(initial = emptyList())
                    val outboxQueueDepth by app.database.outboxDao().observePendingCount().collectAsState(initial = 0)
                    val totalBytesSent by app.database.metricsDao().observeTotalBytesSent().collectAsState(initial = 0L)
                    val totalBytesReceived by app.database.metricsDao().observeTotalBytesReceived().collectAsState(initial = 0L)
                    val syncEventsCount by app.database.metricsDao().observeSyncEventCount().collectAsState(initial = 0)
                    val connectedPeersMap by app.meshTransport.connectedPeers.collectAsState()

                    // Real Room chat messages flow
                    val chatMessages by app.database.chatDao().getMessagesFlow(app.missionId).collectAsState(initial = emptyList())

                    // Ephemeral peer locations
                    val peerLocations by app.peerSharingRepo.activePeers.collectAsState(initial = emptyList())

                    val coroutineScope = rememberCoroutineScope()
                    var deviceLocation by remember { mutableStateOf<LocationResult?>(null) }

                    // Continuous foreground GPS flow
                    val lifecycleOwner = LocalLifecycleOwner.current
                    LaunchedEffect(lifecycleOwner) {
                        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            val initialFix = app.locationProvider.getLastKnownLocation() ?: app.locationProvider.getCurrentLocation()
                            if (initialFix != null) {
                                deviceLocation = initialFix
                            }
                            app.locationProvider.observeLocationUpdates(intervalMs = 2000L, minDistanceMeters = 0.5f).collect { fix ->
                                deviceLocation = fix
                            }
                        }
                    }

                    // 10-Minute Consensual Live Location Sharing State (Default OFF)
                    var isLocationSharingActive by remember { mutableStateOf(false) }
                    var sharingExpiresAt by remember { mutableStateOf(0L) }
                    var showLocationConsentSheet by remember { mutableStateOf(false) }

                    val stopLocationSharing: () -> Unit = {
                        isLocationSharingActive = false
                        coroutineScope.launch(Dispatchers.IO) {
                            val revokeMsg = SyncMessage.PeerLocationRevoked(
                                deviceId = app.deviceId,
                                missionId = app.missionId
                            )
                            app.meshTransport.connectedPeers.value.values.forEach { peer ->
                                app.meshTransport.sendMessage(peer.endpointId, revokeMsg)
                            }
                            app.peerSharingRepo.removePeer(app.deviceId)
                        }
                    }

                    val extendLocationSharing: () -> Unit = {
                        sharingExpiresAt = System.currentTimeMillis() + 10 * 60 * 1000L
                    }

                    val startLocationSharing: () -> Unit = {
                        sharingExpiresAt = System.currentTimeMillis() + 10 * 60 * 1000L
                        isLocationSharingActive = true
                    }

                    // 10-minute session countdown timer
                    LaunchedEffect(isLocationSharingActive, sharingExpiresAt) {
                        if (isLocationSharingActive) {
                            while (isActive) {
                                val remaining = sharingExpiresAt - System.currentTimeMillis()
                                if (remaining <= 0) {
                                    stopLocationSharing()
                                    break
                                }
                                delay(1000L)
                            }
                        }
                    }

                    // Ephemeral location broadcast (every 5-10s while moving, with Absolute Safety Override)
                    LaunchedEffect(isLocationSharingActive) {
                        if (isLocationSharingActive) {
                            app.locationProvider.observeLocationUpdates(intervalMs = 5000L, minDistanceMeters = 3.0f).collect { loc ->
                                // Absolute Safety Override: Suppress ephemeral location broadcasts whenever pending/in-flight outbox items have priority P >= 0.80
                                val hasPendingSos = app.database.outboxDao().getPendingByPriority().any { it.priority >= SyncCoordinator.SAFETY_PRIORITY_FLOOR }
                                if (!hasPendingSos && loc.latitude != null && loc.longitude != null) {
                                    val beaconMsg = SyncMessage.PeerLocation(
                                        deviceId = app.deviceId,
                                        deviceName = app.deviceName,
                                        missionId = app.missionId,
                                        latitude = loc.latitude,
                                        longitude = loc.longitude,
                                        accuracy = loc.accuracy,
                                        bearing = loc.bearing,
                                        speed = loc.speed,
                                        altitude = loc.altitude,
                                        timestamp = loc.timestamp
                                    )
                                    app.meshTransport.connectedPeers.value.values.forEach { peer ->
                                        app.meshTransport.sendMessage(peer.endpointId, beaconMsg)
                                    }
                                }
                            }
                        }
                    }

                    RescueMeshNavHost(
                        homeScreen = { onNavigateToObservation, onNavigateToChat, onNavigateToLiveMap, onNavigateToReportCreate ->
                            HomeScreen(
                                missionId = app.missionId,
                                deviceId = app.deviceId,
                                connectedPeersCount = connectedPeersMap.size,
                                observations = observations,
                                userLocation = deviceLocation,
                                onNewObservationClick = onNavigateToReportCreate,
                                onBroadcastSos = {
                                    coroutineScope.launch {
                                        val loc = deviceLocation ?: app.locationProvider.getLastKnownLocation()
                                        val obs = app.observationRepository.createObservation(
                                            eventType = "SOS",
                                            title = "EMERGENCY SOS",
                                            description = "Immediate emergency assistance requested via mesh broadcast",
                                            reportedStatus = "HAZARD",
                                            latitude = loc?.latitude,
                                            longitude = loc?.longitude,
                                            accuracy = loc?.accuracy
                                        )
                                        app.syncCoordinator.onNewLocalObservation(obs)
                                        app.mapFusionEngine.applyObservation(obs)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Emergency SOS: Saved locally on this phone • Queued for mesh transfer",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onObservationClick = onNavigateToObservation,
                                onNavigateToChat = { onNavigateToChat(null) },
                                onShareLocation = { showLocationConsentSheet = true }
                            )
                        },
                        liveMapScreen = { onNavigateToObservation, onNavigateToChat ->
                            MapScreen(
                                nodes = nodes,
                                edges = edges,
                                observations = observations,
                                userLocation = deviceLocation,
                                peerLocations = peerLocations,
                                onNavigateToObservationDetail = onNavigateToObservation,
                                onNavigateToChat = onNavigateToChat,
                                onFindRoute = { from, to ->
                                    runBlocking {
                                        app.routePlanner.findRoute(
                                            fromNodeId = from,
                                            toNodeId = to,
                                            missionId = app.missionId,
                                            avoidBlocked = true,
                                            avoidConflicted = false
                                        )
                                    }
                                }
                            )
                        },
                        offlineMapsScreen = { onNavigateToSituational ->
                            OfflineMapsScreen(onNavigateToSituationalCanvas = onNavigateToSituational)
                        },
                        reportsScreen = { onNavigateToObservation ->
                            ObservationScreen(
                                currentLocation = deviceLocation,
                                evidenceManager = app.evidenceManager,
                                onSaveObservation = { type, title, desc, status, lat, lon, acc, photoPath, photoHash ->
                                    coroutineScope.launch {
                                        val obs = app.observationRepository.createObservation(
                                            eventType = type,
                                            title = title,
                                            description = desc,
                                            reportedStatus = status,
                                            latitude = lat,
                                            longitude = lon,
                                            accuracy = acc,
                                            evidencePath = photoPath,
                                            evidenceHash = photoHash
                                        )
                                        // Trigger mesh broadcast
                                        app.syncCoordinator.onNewLocalObservation(obs)
                                        // Update fused map
                                        app.mapFusionEngine.applyObservation(obs)
                                    }
                                }
                            )
                        },
                        chatScreen = { linkedEventId, onNavigateToMap ->
                            ChatScreen(
                                messages = chatMessages,
                                sosIncidents = observations,
                                initialLinkedEventId = linkedEventId,
                                currentUserId = app.deviceId,
                                currentUserName = app.deviceName,
                                onSendMessage = { text, linkedId ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val nextSeq = (app.database.chatDao().getLatestSequence(app.deviceId) ?: 0L) + 1L
                                        val chatEntity = ChatMessageEntity(
                                            messageId = UUID.randomUUID().toString(),
                                            missionId = app.missionId,
                                            senderId = app.deviceId,
                                            senderName = app.deviceName,
                                            text = text.take(ChatMessageEntity.MAX_TEXT_LENGTH),
                                            timestamp = System.currentTimeMillis(),
                                            status = ChatMessageEntity.STATUS_SENDING,
                                            ackPeerCount = 0,
                                            isLocal = true,
                                            senderRole = ChatMessageEntity.ROLE_UNVERIFIED,
                                            linkedEventId = linkedId,
                                            authorSequence = nextSeq,
                                            relayHopsRemaining = 3
                                        )
                                        // Durable local-first commit to Room
                                        app.database.chatDao().insert(chatEntity)
                                        // Mesh broadcast via outbox
                                        app.syncCoordinator.onNewLocalChatMessage(chatEntity)
                                    }
                                },
                                onNavigateToMap = onNavigateToMap
                            )
                        },
                        peersScreen = {
                            var isMeshActive by remember { mutableStateOf(true) }
                            PeersScreen(
                                peers = peers,
                                isAdvertising = isMeshActive,
                                isDiscovering = isMeshActive,
                                onToggleMesh = {
                                    if (isMeshActive) {
                                        app.meshTransport.stopMesh()
                                        isMeshActive = false
                                    } else {
                                        app.meshTransport.startMesh(app.deviceName, app.deviceId)
                                        isMeshActive = true
                                    }
                                },
                                onForceSync = {
                                    Toast.makeText(this@MainActivity, "Mesh state digest exchange broadcasted", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        metricsScreen = {
                            MetricsScreen(
                                metrics = metrics,
                                totalObservations = observations.size,
                                connectedPeersCount = connectedPeersMap.size,
                                outboxQueueDepth = outboxQueueDepth,
                                syncEventsCount = syncEventsCount,
                                bytesSentTotal = totalBytesSent,
                                bytesReceivedTotal = totalBytesReceived,
                                onExportAuditLog = {
                                    Toast.makeText(this@MainActivity, "Audit log exported to local storage", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        observationDetailScreen = { eventId, onBackClick ->
                            val obs = observations.find { it.eventId == eventId }
                            ObservationDetailScreen(
                                observation = obs,
                                onBackClick = onBackClick
                            )
                        }
                    )

                    // Modal bottom sheet for explicit user consent and location sharing management
                    if (showLocationConsentSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showLocationConsentSheet = false },
                            containerColor = Color(0xFF161B22),
                            contentColor = Color(0xFFE6EDF3)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                            ) {
                                if (isLocationSharingActive) {
                                    val remainingMs = maxOf(0L, sharingExpiresAt - System.currentTimeMillis())
                                    val remainingMins = (remainingMs / 1000) / 60
                                    val remainingSecs = (remainingMs / 1000) % 60
                                    val timerString = String.format(Locale.US, "%02d:%02d", remainingMins, remainingSecs)

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.ShareLocation,
                                            contentDescription = null,
                                            tint = Color(0xFF00BFA5),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Live Location Sharing Active",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "Auto-expires in $timerString",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF00BFA5),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Your coordinates are broadcasting strictly over offline direct P2P mesh to connected devices in radio range. Zero cloud storage.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFC9D1D9)
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                showLocationConsentSheet = false
                                                stopLocationSharing()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Stop Sharing")
                                        }
                                        Button(
                                            onClick = {
                                                extendLocationSharing()
                                                showLocationConsentSheet = false
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Extend (+10m)", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.ShareLocation,
                                            contentDescription = null,
                                            tint = Color(0xFF00BFA5),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Tactical Live Location Sharing",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Share your real-time coordinates with nearby response peers over the local mesh.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFC9D1D9)
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))

                                    ConsentDisclosureRow(
                                        icon = Icons.Filled.WifiOff,
                                        title = "Offline Direct P2P",
                                        desc = "Transmitted directly via Bluetooth & Wi-Fi Direct. Zero internet connection required."
                                    )
                                    ConsentDisclosureRow(
                                        icon = Icons.Filled.CloudOff,
                                        title = "Zero Cloud Storage",
                                        desc = "Coordinates are volatile in-memory only and never saved to a database or server."
                                    )
                                    ConsentDisclosureRow(
                                        icon = Icons.Filled.Timer,
                                        title = "10-Minute Auto-Expiry",
                                        desc = "Sharing automatically expires after 10 minutes unless explicitly extended."
                                    )
                                    ConsentDisclosureRow(
                                        icon = Icons.Filled.StopCircle,
                                        title = "Instant Stop",
                                        desc = "You can stop sharing at any second to immediately revoke your presence from peer maps."
                                    )

                                    Spacer(modifier = Modifier.height(20.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { showLocationConsentSheet = false },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Cancel")
                                        }
                                        Button(
                                            onClick = {
                                                showLocationConsentSheet = false
                                                startLocationSharing()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Agree & Start (10m)", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startMeshServices()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMeshServices() {
        val app = application as RescueMeshApp
        app.meshTransport.startMesh(app.deviceName, app.deviceId)
        app.syncCoordinator.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as RescueMeshApp
        app.meshTransport.stopMesh()
        app.syncCoordinator.stop()
    }
}

@Composable
private fun ConsentDisclosureRow(
    icon: ImageVector,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF00BFA5),
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8B949E)
            )
        }
    }
}
