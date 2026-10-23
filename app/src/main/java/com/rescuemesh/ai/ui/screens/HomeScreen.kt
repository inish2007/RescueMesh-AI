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
    onObservationClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as? RescueMeshApp
    val currentActiveMission by (app?.activeMission ?: MutableStateFlow(null)).collectAsState()

    val effectiveMissionId = currentActiveMission?.missionId ?: missionId
    val effectiveMissionName = activeMissionName ?: currentActiveMission?.name ?: "Disaster Alpha"
    val effectivePairingCode = pairingCode ?: currentActiveMission?.pairingCode ?: "RC-01"
    val coroutineScope = rememberCoroutineScope()

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

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
                        modifier = Modifier.padding(end = 12.dp)
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
                            Text(
                                text = "$connectedPeersCount Peers",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (connectedPeersCount > 0) StatusClear else TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewObservationClick,
                containerColor = PrimaryAmber,
                contentColor = BackgroundDark
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Observation")
            }
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
            // Tactical Emergency SOS Quick-Broadcast Action Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1518)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StatusBlocked.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(StatusBlocked.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = "Emergency SOS",
                                    tint = StatusBlocked,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "EMERGENCY SOS",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Saved locally on this phone • Queued for mesh transfer (P >= 0.80). Does not guarantee rescue dispatch.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Button(
                            onClick = { onBroadcastSos?.invoke() ?: onNewObservationClick() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StatusBlocked,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("TRANSMIT SOS", fontWeight = FontWeight.Bold)
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
