package com.rescuemesh.ai.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rescuemesh.ai.RescueMeshApp
import com.rescuemesh.ai.data.entity.MissionEntity
import com.rescuemesh.ai.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Settings & System Diagnostics Screen for RescueMesh AI.
 *
 * Implements:
 * 1. Tactical Dark Mode configuration toggle.
 * 2. Active Mission Sector management (sector name, pairing code, switch/create actions).
 * 3. Navigation to Advanced Diagnostics (PeersScreen & MetricsScreen).
 * 4. Genuine storage accounting (evidence cache & offline map packages).
 * 5. Architectural safety & offline invariants verification badges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToPeers: () -> Unit,
    onNavigateToMetrics: () -> Unit,
    activeMissionName: String? = null,
    pairingCode: String? = null,
    onSwitchMission: ((String) -> Unit)? = null,
    onCreateMission: ((name: String, pairingCode: String, description: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as? RescueMeshApp
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("rescuemesh_settings", Context.MODE_PRIVATE) }

    val currentActiveMission by (app?.activeMission ?: MutableStateFlow(null)).collectAsState()
    val effectiveMissionName = activeMissionName ?: currentActiveMission?.name ?: "Disaster Alpha"
    val effectivePairingCode = pairingCode ?: currentActiveMission?.pairingCode ?: "RC-01"
    val effectiveMissionId = currentActiveMission?.missionId ?: "default-mission"

    var isDarkTacticalMode by remember {
        mutableStateOf(prefs.getBoolean("dark_mode_enabled", true))
    }

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var cacheClearSuccessMessage by remember { mutableStateOf<String?>(null) }

    // Compute genuine storage usage
    var evidenceSizeBytes by remember { mutableLongStateOf(0L) }
    var evidenceFileCount by remember { mutableIntStateOf(0) }
    var mapStorageSizeBytes by remember { mutableLongStateOf(0L) }

    fun refreshStorageCounters() {
        val evidenceDir = File(context.filesDir, "evidence")
        if (evidenceDir.exists() && evidenceDir.isDirectory) {
            val files = evidenceDir.listFiles() ?: emptyArray()
            evidenceFileCount = files.size
            evidenceSizeBytes = files.sumOf { it.length() }
        } else {
            evidenceFileCount = 0
            evidenceSizeBytes = 0L
        }

        val mapDir = File(context.filesDir, "offline_maps")
        if (mapDir.exists() && mapDir.isDirectory) {
            mapStorageSizeBytes = (mapDir.listFiles() ?: emptyArray()).sumOf { it.length() }
        } else {
            mapStorageSizeBytes = 0L
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageCounters()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Diagnostics",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryAmber
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Navigate Back",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BackgroundDark)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Appearance & Tactical Mode
            Text(
                text = "APPEARANCE & DISPLAY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = PrimaryAmber
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DarkMode,
                            contentDescription = null,
                            tint = PrimaryAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Dark Tactical Mode",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "High-contrast dark palette (#101820) for night & disaster operations",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Switch(
                        checked = isDarkTacticalMode,
                        onCheckedChange = { checked ->
                            isDarkTacticalMode = checked
                            prefs.edit().putBoolean("dark_mode_enabled", checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PrimaryAmber,
                            checkedTrackColor = PrimaryAmber.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = SurfaceVariant
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }

            // Section 2: Mission Sector Management
            Text(
                text = "OPERATIONAL MISSION SECTOR",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = PrimaryAmber
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = effectiveMissionName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Pairing Code: $effectivePairingCode",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryAmber
                            )
                        }

                        Surface(
                            color = StatusClear.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, StatusClear.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "ACTIVE SECTOR",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusClear,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sector UUID: $effectiveMissionId",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSwitchDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryAmber),
                            border = BorderStroke(1.dp, PrimaryAmber.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Switch Sector", fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF56C5B5)),
                            border = BorderStroke(1.dp, Color(0xFF56C5B5).copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Sector", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Section 3: Hardware Diagnostics & Telemetry
            Text(
                text = "ADVANCED HARDWARE & PROTOCOL DIAGNOSTICS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = PrimaryAmber
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Peers Screen Navigation Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToPeers)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WifiTethering,
                                contentDescription = null,
                                tint = Color(0xFF53B1FD),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Mesh Peer Topology & Radios",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Inspect Nearby Connections, peer signal RSSI, and radio states",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Open Peers",
                            tint = TextTertiary
                        )
                    }

                    HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)

                    // Metrics Screen Navigation Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToMetrics)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BarChart,
                                contentDescription = null,
                                tint = Color(0xFFF79009),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Protocol Telemetry & Research Metrics",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "VoI scheduler efficiency, throughput stats, and delivery convergence",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Open Metrics",
                            tint = TextTertiary
                        )
                    }
                }
            }

            // Section 4: Local Storage Accounting
            Text(
                text = "OFFLINE STORAGE & CACHE QUOTAS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = PrimaryAmber
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = PrimaryAmber, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Evidence Photo Storage", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                        Text(
                            text = "${formatBytes(evidenceSizeBytes)} ($evidenceFileCount files)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Map, contentDescription = null, tint = Color(0xFF56C5B5), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Offline Vector Maps", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                        Text(
                            text = formatBytes(mapStorageSizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF56C5B5)
                        )
                    }

                    if (cacheClearSuccessMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = cacheClearSuccessMessage!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusClear
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showClearCacheConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF97066)),
                        border = BorderStroke(1.dp, Color(0xFFF97066).copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Temporary Cache", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Section 5: Architectural Safety Invariants
            Text(
                text = "SYSTEM ARCHITECTURE INVARIANTS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = PrimaryAmber
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InvariantBadge(
                        title = "Strictly Offline-First",
                        subtitle = "Zero external cloud dependencies or telemetry endpoints. Pure P2P radio mesh."
                    )
                    InvariantBadge(
                        title = "Durable Local Persistence",
                        subtitle = "Observations committed to local SQLite Room database before mesh transmission."
                    )
                    InvariantBadge(
                        title = "Conflict-Preserving Graph Fusion",
                        subtitle = "Contradicting reports never overwrite earlier evidence; flagged CONFLICTED."
                    )
                    InvariantBadge(
                        title = "Safety-Guaranteed Route Guidance",
                        subtitle = "Routes NEVER described as safe. Blocked edges excluded; VoI priority floor P >= 0.80."
                    )
                }
            }

            // Build Info
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "RescueMesh AI • Version 1.0.0 (Gate 3 Release)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Text(
                        text = "Device ID: ${(app?.deviceId ?: "unknown").take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiary
                    )
                }
            }
        }
    }

    // Switch Mission Dialog
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
                    color = PrimaryAmber,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Select a known mission sector or enter a pairing code:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    availableMissions.forEach { m ->
                        val isCurrent = m.missionId == effectiveMissionId
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCurrent) SurfaceVariant else SurfaceDark,
                            border = BorderStroke(
                                1.dp,
                                if (isCurrent) PrimaryAmber else SurfaceBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    if (onSwitchMission != null) {
                                        onSwitchMission(m.missionId)
                                    } else if (app != null) {
                                        coroutineScope.launch {
                                            app.switchMission(m.missionId)
                                        }
                                    }
                                    showSwitchDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = m.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) PrimaryAmber else TextPrimary
                                    )
                                    Text(
                                        text = "Code: ${m.pairingCode} • ${m.description}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                if (isCurrent) {
                                    Icon(Icons.Filled.Check, contentDescription = "Active", tint = PrimaryAmber)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Or join by Pairing Code:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    OutlinedTextField(
                        value = joinPairingCode,
                        onValueChange = {
                            joinPairingCode = it.uppercase()
                            joinError = null
                        },
                        label = { Text("Pairing Code (e.g. RC-01)") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = joinError != null,
                        singleLine = true
                    )
                    if (joinError != null) {
                        Text(joinError!!, style = MaterialTheme.typography.labelSmall, color = Color(0xFFF97066))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val code = joinPairingCode.trim().uppercase()
                        if (code.isNotBlank()) {
                            coroutineScope.launch {
                                val target = app?.switchMissionByPairingCode(code)
                                if (target != null) {
                                    onSwitchMission?.invoke(target.missionId)
                                    showSwitchDialog = false
                                } else {
                                    joinError = "No local mission found with code '$code'."
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAmber, contentColor = BackgroundDark),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Join Code")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSwitchDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Create Mission Dialog
    if (showCreateDialog) {
        var missionName by remember { mutableStateOf("") }
        var pairingCodeInput by remember { mutableStateOf("") }
        var missionDesc by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text(
                    text = "Create Mission Sector",
                    style = MaterialTheme.typography.titleLarge,
                    color = PrimaryAmber,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = missionName,
                        onValueChange = { missionName = it },
                        label = { Text("Sector Name (e.g. Flood Zone 4)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pairingCodeInput,
                        onValueChange = { pairingCodeInput = it.uppercase() },
                        label = { Text("Pairing Code (e.g. FZ-04)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = missionDesc,
                        onValueChange = { missionDesc = it },
                        label = { Text("Operational Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (missionName.isNotBlank() && pairingCodeInput.isNotBlank()) {
                            if (onCreateMission != null) {
                                onCreateMission(missionName.trim(), pairingCodeInput.trim(), missionDesc.trim())
                            } else if (app != null) {
                                coroutineScope.launch {
                                    app.createMission(missionName.trim(), pairingCodeInput.trim(), missionDesc.trim(), setAsActive = true)
                                }
                            }
                            showCreateDialog = false
                        }
                    },
                    enabled = missionName.isNotBlank() && pairingCodeInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAmber, contentColor = BackgroundDark),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Create & Activate")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Clear Cache Confirmation Dialog
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear Temporary Cache", color = PrimaryAmber, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "This will remove temporary cache files. Active database records, mission definitions, and verified photo evidence are strictly preserved.",
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val cacheDir = context.cacheDir
                            val files = cacheDir.listFiles() ?: emptyArray()
                            files.forEach { it.deleteRecursively() }
                            refreshStorageCounters()
                            cacheClearSuccessMessage = "Temporary cache cleared successfully."
                        } catch (e: Exception) {
                            cacheClearSuccessMessage = "Cache clear error: ${e.message}"
                        }
                        showClearCacheConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97066), contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Confirm Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearCacheConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun InvariantBadge(title: String, subtitle: String) {
    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF56C5B5))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
