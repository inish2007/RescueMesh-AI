package com.rescuemesh.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.RescueMeshApp
import com.rescuemesh.ai.data.entity.*
import com.rescuemesh.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    missionId: String,
    deviceId: String,
    connectedPeersCount: Int,
    observations: List<ObservationEntity>,
    onNewObservationClick: () -> Unit,
    activeMissionName: String? = null,
    pairingCode: String? = null,
    onSwitchMission: ((String) -> Unit)? = null,
    onCreateMission: ((name: String, pairingCode: String, description: String) -> Unit)? = null,
    onBroadcastSos: (() -> Unit)? = null,
    onObservationClick: ((String) -> Unit)? = null,
    userLocation: com.rescuemesh.ai.location.LocationResult? = null,
    onNavigateToChat: (() -> Unit)? = null,
    onShareLocation: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    onNavigateToSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as? RescueMeshApp
    val currentActiveMission by (app?.activeMission ?: MutableStateFlow(null)).collectAsState()

    val localNav = com.rescuemesh.ai.ui.navigation.LocalNavController.current
    val effectiveNavigateToSettings: () -> Unit = onNavigateToSettings ?: {
        localNav?.navigate(com.rescuemesh.ai.ui.navigation.Screen.Settings.route)
    }

    val effectiveMissionId = currentActiveMission?.missionId ?: missionId
    val effectiveMissionName = activeMissionName ?: currentActiveMission?.name ?: "Disaster Alpha"
    val effectivePairingCode = pairingCode ?: currentActiveMission?.pairingCode ?: "RC-01"
    val coroutineScope = rememberCoroutineScope()

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSosConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "RescueMesh AI",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryAmber
                        )
                        Text(
                            text = "Mission: $effectiveMissionName [Code: $effectivePairingCode]",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    Surface(
                        color = if (connectedPeersCount > 0) StatusClear.copy(alpha = 0.2f) else TextTertiary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (connectedPeersCount > 0) StatusClear else TextTertiary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val peerText = if (connectedPeersCount == 1) "1 Peer" else "$connectedPeersCount Peers"
                            Text(
                                text = peerText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (connectedPeersCount > 0) StatusClear else TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = { effectiveNavigateToSettings() },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings & Diagnostics",
                            tint = PrimaryAmber
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BackgroundDark)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Tactical Mission Card & Selector
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PrimaryAmber.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Flag,
                                    contentDescription = null,
                                    tint = PrimaryAmber,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Active Mission Sector",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                            }
                            Surface(
                                color = PrimaryAmber.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "CODE: $effectivePairingCode",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryAmber,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = effectiveMissionName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Text(
                            text = "Mission: $effectiveMissionName [Code: $effectivePairingCode] • ID: $effectiveMissionId",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { showSwitchDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = PrimaryAmber
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SwapHoriz,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Switch", style = MaterialTheme.typography.labelMedium)
                            }

                            Button(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryAmber,
                                    contentColor = BackgroundDark
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New / Join", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            // Gate 3 Prominent Circular SOS Trigger Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF241517)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Outer Pulsing Ring around SOS Button (>= 48dp touch target)
                        Box(
                            modifier = Modifier
                                .size(108.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE53935).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE53935).copy(alpha = 0.28f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { showSosConfirmDialog = true },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFE53935),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(76.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = "Emergency SOS",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = "SOS",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "EMERGENCY SOS: BROADCAST POSITION",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Saved locally • Queued for mesh transfer (P >= 0.80). Does NOT guarantee emergency responder dispatch.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B949E),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Gate 3: 4 Quick Action Chips (Touch targets >= 48dp)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Create Report
                    OutlinedButton(
                        onClick = {
                            onNewObservationClick()
                            localNav?.navigate(com.rescuemesh.ai.ui.navigation.Screen.Reports.route)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2196F3)),
                        border = BorderStroke(1.dp, Color(0xFF2196F3).copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.EditNote, contentDescription = "Create Report", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Report", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    // 2. Share Location
                    OutlinedButton(
                        onClick = { onShareLocation?.invoke() ?: onBroadcastSos?.invoke() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00BFA5)),
                        border = BorderStroke(1.dp, Color(0xFF00BFA5).copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.MyLocation, contentDescription = "Share Location", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Location", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    // 3. Take Photo
                    OutlinedButton(
                        onClick = {
                            onTakePhoto?.invoke() ?: run {
                                onNewObservationClick()
                                localNav?.navigate(com.rescuemesh.ai.ui.navigation.Screen.Reports.route)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB300)),
                        border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = "Take Photo", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Photo", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    // 4. Send Message
                    OutlinedButton(
                        onClick = { onNavigateToChat?.invoke() ?: localNav?.navigate(com.rescuemesh.ai.ui.navigation.Screen.Chat.route) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE6EDF3)),
                        border = BorderStroke(1.dp, Color(0xFF30363D)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Chat, contentDescription = "Send Message", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Message", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Gate 3: Location Quality Telemetry Card (Live/Stale/Pending GPS fix with uncertainty readout)
            item {
                val isGpsAvailable = userLocation?.latitude != null && userLocation?.longitude != null
                val locationAgeMs = if (userLocation?.timestamp != null && userLocation.timestamp > 0) {
                    System.currentTimeMillis() - userLocation.timestamp
                } else null

                val isStale = locationAgeMs != null && locationAgeMs >= 15 * 60 * 1000L // >=15m is STALE (ASTRA-P0-04)

                val gpsStatusLabel = when {
                    !isGpsAvailable -> "LOCATION UNAVAILABLE"
                    isStale -> "STALE FIX (>=15m)"
                    locationAgeMs != null && locationAgeMs < 120_000L -> "LIVE GPS FIX"
                    else -> "RECENT FIX (<15m)"
                }

                val gpsStatusColor = when {
                    !isGpsAvailable -> Color(0xFF8B949E)
                    isStale -> Color(0xFFFFB300)
                    locationAgeMs != null && locationAgeMs < 120_000L -> Color(0xFF00BFA5)
                    else -> Color(0xFF2196F3)
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.GpsFixed,
                                    contentDescription = null,
                                    tint = gpsStatusColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Location Telemetry & Quality",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE6EDF3)
                                )
                            }

                            Surface(
                                color = gpsStatusColor.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = gpsStatusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = gpsStatusColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isGpsAvailable) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.5f°N, %.5f°E", userLocation?.latitude ?: 0.0, userLocation?.longitude ?: 0.0),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE6EDF3)
                                )
                                Text(
                                    text = userLocation?.accuracy?.let { "±${it.toInt()}m uncertainty" } ?: "Accuracy unknown",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF2196F3),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            val ageSeconds = (locationAgeMs ?: 0L) / 1000L
                            val ageText = if (ageSeconds < 60) "${ageSeconds}s ago" else "${ageSeconds / 60}m ago"
                            Text(
                                text = "Acquisition: $ageText via ${userLocation?.provider ?: "GPS"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8B949E)
                            )
                        } else {
                            Text(
                                text = "Location unavailable • Searching for GPS satellites or operating indoors without positioning lock.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8B949E)
                            )
                        }
                    }
                }
            }

            // Gate 3: Incident Highlights Card
            item {
                val urgentIncidents = remember(observations) {
                    observations
                        .filter { it.eventType == "SOS" || it.eventType == "HAZARD" || it.reportedStatus == "BLOCKED" }
                        .sortedByDescending { it.observedAtDevice }
                        .take(3)
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB300),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Incident Highlights",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE6EDF3)
                                )
                            }
                            Surface(
                                color = Color(0xFF2196F3).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "${urgentIncidents.size} Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2196F3),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (urgentIncidents.isEmpty()) {
                            Text(
                                text = "No urgent hazards or SOS incidents reported in this sector.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8B949E)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                urgentIncidents.forEach { inc ->
                                    val isSos = inc.eventType == "SOS"
                                    Surface(
                                        color = if (isSos) Color(0xFF281417) else Color(0xFF1F242C),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (isSos) Color(0xFFE53935).copy(alpha = 0.5f) else Color(0xFF30363D)),
                                        onClick = { onObservationClick?.invoke(inc.eventId) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Surface(
                                                        color = if (isSos) Color(0xFFE53935) else Color(0xFFFFB300),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = if (isSos) "SOS" else inc.eventType,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = inc.title,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFE6EDF3)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "Status: ${inc.reportedStatus ?: "UNKNOWN"} • Author: ${inc.authorDeviceId.take(8)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF8B949E)
                                                )
                                            }

                                            Icon(
                                                imageVector = Icons.Filled.ChevronRight,
                                                contentDescription = "Inspect",
                                                tint = Color(0xFF8B949E),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Status Banner
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Mesh Network Status",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Icon(
                                imageVector = Icons.Filled.WifiTethering,
                                contentDescription = null,
                                tint = PrimaryAmber
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Zero-infrastructure P2P active. Observations recorded locally and exchanged automatically when peers are in radio range.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Local Device: ${deviceId.take(8)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryAmber
                        )
                    }
                }
            }

            // Section Header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = PrimaryAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live Observation Feed (${observations.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            if (observations.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Inbox,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No observations recorded yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap the + button to record field conditions",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                    }
                }
            } else {
                items(observations, key = { it.eventId }) { obs ->
                    ObservationFeedCard(
                        obs = obs,
                        onClick = onObservationClick?.let { { it(obs.eventId) } }
                    )
                }
            }
        }
    }

    if (showSwitchDialog) {
        var availableMissions by remember { mutableStateOf<List<MissionEntity>>(emptyList()) }
        var joinPairingCode by remember { mutableStateOf("") }
        var joinError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            if (app != null) {
                availableMissions = app.getAllMissions()
            }
        }

        AlertDialog(
            onDismissRequest = { showSwitchDialog = false },
            title = {
                Text(
                    text = "Switch Mission Sector",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Select an existing local mission or enter a pairing code to join a team sector.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = joinPairingCode,
                        onValueChange = {
                            joinPairingCode = it.uppercase()
                            joinError = null
                        },
                        label = { Text("Join via Pairing Code") },
                        placeholder = { Text("e.g. RC-01") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = joinError != null
                    )

                    if (joinError != null) {
                        Text(
                            text = joinError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = {
                            val code = joinPairingCode.trim().uppercase()
                            if (code.isBlank()) {
                                joinError = "Please enter a pairing code"
                                return@Button
                            }
                            coroutineScope.launch {
                                val target = app?.switchMissionByPairingCode(code)
                                if (target != null) {
                                    onSwitchMission?.invoke(target.missionId)
                                    showSwitchDialog = false
                                } else {
                                    joinError = "No mission found with code: $code"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryAmber,
                            contentColor = BackgroundDark
                        )
                    ) {
                        Text("Join Mission by Code", fontWeight = FontWeight.Bold)
                    }

                    Divider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Available Local Missions (${availableMissions.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryAmber
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(availableMissions, key = { it.missionId }) { m ->
                            val isSelected = m.missionId == effectiveMissionId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) PrimaryAmber.copy(alpha = 0.2f) else SurfaceVariant.copy(alpha = 0.5f),
                                border = if (isSelected) BorderStroke(1.dp, PrimaryAmber) else null,
                                onClick = {
                                    if (onSwitchMission != null) {
                                        onSwitchMission(m.missionId)
                                    } else {
                                        coroutineScope.launch {
                                            app?.switchMission(m.missionId)
                                        }
                                    }
                                    showSwitchDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = m.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "Code: ${m.pairingCode} • ID: ${m.missionId}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "Active",
                                            tint = PrimaryAmber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSwitchDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    if (showCreateDialog) {
        var newMissionName by remember { mutableStateOf("") }
        var newPairingCode by remember { mutableStateOf("") }
        var newDescription by remember { mutableStateOf("") }
        var inputError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text(
                    text = "Create New Mission",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Establish an operational disaster response sector. Share the pairing code with mesh peers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = newMissionName,
                        onValueChange = {
                            newMissionName = it
                            inputError = null
                        },
                        label = { Text("Mission Name") },
                        placeholder = { Text("e.g. Sector North Flood") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newPairingCode,
                        onValueChange = {
                            newPairingCode = it.uppercase()
                            inputError = null
                        },
                        label = { Text("Pairing Code") },
                        placeholder = { Text("e.g. SN-01") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it },
                        label = { Text("Description (Optional)") },
                        placeholder = { Text("e.g. Search & Rescue Grid 4") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (inputError != null) {
                        Text(
                            text = inputError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newMissionName.trim()
                        val code = newPairingCode.trim().uppercase()
                        if (name.isBlank() || code.isBlank()) {
                            inputError = "Mission name and pairing code are required"
                            return@Button
                        }
                        if (onCreateMission != null) {
                            onCreateMission(name, code, newDescription.trim())
                        } else {
                            coroutineScope.launch {
                                app?.createMission(
                                    name = name,
                                    pairingCode = code,
                                    description = newDescription.trim(),
                                    setAsActive = true
                                )
                            }
                        }
                        showCreateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryAmber,
                        contentColor = BackgroundDark
                    )
                ) {
                    Text("Create & Activate", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    if (showSosConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSosConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFE53935),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Confirm Emergency SOS Broadcast",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Your emergency broadcast will be saved locally on this device and transmitted with highest priority (P >= 0.80) to all Nearby mesh peers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE6EDF3)
                    )

                    Surface(
                        color = Color(0xFF281417),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "TRUTHFUL EMERGENCY NOTICE:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE53935)
                            )
                            Text(
                                text = "Mesh delivery does NOT dispatch municipal rescue or emergency services. Only nearby peers running RescueMesh AI will receive your broadcast.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC9D1D9)
                            )
                        }
                    }

                    if (userLocation?.latitude != null && userLocation.longitude != null) {
                        Text(
                            text = String.format(Locale.US, "GPS: %.5f°N, %.5f°E (±%dm accuracy)", userLocation.latitude, userLocation.longitude, userLocation.accuracy?.toInt() ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00BFA5),
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Location: Unavailable (broadcasting without verified coordinates)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFB300),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSosConfirmDialog = false
                        onBroadcastSos?.invoke() ?: onNewObservationClick()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("BROADCAST SOS NOW", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSosConfirmDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = Color(0xFF8B949E))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }
}

@Composable
fun ObservationFeedCard(
    obs: ObservationEntity,
    onClick: (() -> Unit)? = null
) {
    val isSos = obs.eventType == "SOS"
    val statusColor = if (isSos) StatusBlocked else when (obs.reportedStatus) {
        "OPEN", "CLEAR" -> StatusClear
        "BLOCKED", "COLLAPSED" -> StatusBlocked
        "HAZARD", "DANGER" -> StatusHazard
        "CONFLICTED" -> StatusConflicted
        else -> TextSecondary
    }

    val typeIcon = when (obs.eventType) {
        "SOS", "HAZARD" -> Icons.Filled.Warning
        "ROUTE_STATUS" -> Icons.Filled.AltRoute
        "PHOTO" -> Icons.Filled.CameraAlt
        else -> Icons.Filled.Note
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSos) Color(0xFF281215) else SurfaceDark
        ),
        shape = RoundedCornerShape(10.dp),
        border = if (isSos) BorderStroke(1.dp, StatusBlocked.copy(alpha = 0.5f)) else BorderStroke(1.dp, SurfaceBorder.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = obs.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
                val badgeText = if (isSos) "EMERGENCY SOS" else obs.reportedStatus
                badgeText?.let { text ->
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (isSos) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color(0xFF381417),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(
                            text = if (obs.isLocal) "Status: Saved locally on this phone • Queued for mesh transfer"
                            else "Status: Received from mesh peer ${obs.authorDeviceId.take(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Mesh delivery does not guarantee responder dispatch.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }

            if (obs.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = obs.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            if (obs.hasEvidence) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "Photo Evidence",
                        tint = if (obs.verificationStatus == "VERIFIED") StatusClear else PrimaryAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Photo Evidence: ${obs.verificationStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (obs.verificationStatus == "VERIFIED") StatusClear else PrimaryAmber,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = if (obs.latitude != null) PrimaryAmber else TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (obs.latitude != null && obs.longitude != null) {
                    val accText = obs.accuracy?.let { " (±${it.toInt()}m)" } ?: ""
                    Text(
                        text = "GPS: ${String.format(Locale.US, "%.5f, %.5f", obs.latitude, obs.longitude)}$accText",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        text = "UNKNOWN Location (Indoor / GPS Pending)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }

            if (onClick != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "View details & evidence ➔",
                        style = MaterialTheme.typography.labelSmall,
                        color = PrimaryAmber,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = SurfaceVariant, thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "By ${obs.authorDeviceId.take(8)} (seq #${obs.authorSequence})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(obs.observedAtDevice))
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        }
    }
}
