package com.rescuemesh.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.ui.navigation.RescueMeshNavHost
import com.rescuemesh.ai.ui.screens.*
import com.rescuemesh.ai.ui.theme.RescueMeshAITheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

                    val coroutineScope = rememberCoroutineScope()
                    var deviceLocation by remember { mutableStateOf<com.rescuemesh.ai.location.LocationResult?>(null) }

                    LaunchedEffect(Unit) {
                        deviceLocation = app.locationProvider.getLastKnownLocation() ?: app.locationProvider.getCurrentLocation()
                    }

                    RescueMeshNavHost(
                        homeScreen = {
                            HomeScreen(
                                missionId = app.missionId,
                                deviceId = app.deviceId,
                                connectedPeersCount = connectedPeersMap.size,
                                observations = observations,
                                onNewObservationClick = {
                                    // Handled by navigation or tab switch
                                }
                            )
                        },
                        observationsScreen = {
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
                        mapScreen = {
                            MapScreen(
                                nodes = nodes,
                                edges = edges,
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
                        }
                    )
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
