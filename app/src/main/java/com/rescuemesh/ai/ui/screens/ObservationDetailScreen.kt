package com.rescuemesh.ai.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rescuemesh.ai.data.entity.*
import com.rescuemesh.ai.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 5 discrete evidence states for photo verification and mesh transfer.
 * Explicitly distinguishes verified photos from pending, transferring, corrupted, or absent evidence.
 * NEVER fabricates placeholders or dummy content as verified evidence.
 */
sealed class EvidenceUiState {
    data object NoPhoto : EvidenceUiState()
    data class PhotoPending(val expectedHash: String) : EvidenceUiState()
    data class Transferring(val expectedHash: String, val progress: Float? = null) : EvidenceUiState()
    data class Verified(
        val filePath: String,
        val hash: String,
        val fileSizeBytes: Long
    ) : EvidenceUiState()
    data class FailedOrCorrupted(
        val hash: String?,
        val reason: String
    ) : EvidenceUiState()
}

/**
 * Freshness assessment for GNSS / survey fixes (ASTRA-P0-04).
 * Stale fixes (> 30 min) must never be presented as live position tracking.
 */
data class LocationFreshnessInfo(
    val ageMs: Long,
    val ageDisplay: String,
    val isLiveRecent: Boolean,
    val isStale: Boolean,
    val statusLabel: String,
    val statusColor: Color,
    val warningMessage: String?
)

/**
 * Resolves the concrete [EvidenceUiState] from an [ObservationEntity] and optional in-flight transfer state.
 */
fun resolveEvidenceState(
    observation: ObservationEntity,
    isTransferring: Boolean = false,
    transferProgress: Float? = null
): EvidenceUiState {
    val hash = observation.evidenceHash
    if (hash.isNullOrBlank()) {
        return EvidenceUiState.NoPhoto
    }

    val status = observation.verificationStatus.trim().uppercase()
    if (status in listOf("FAILED", "CORRUPTED", "VERIFICATION_FAILED")) {
        return EvidenceUiState.FailedOrCorrupted(
            hash = hash,
            reason = "Integrity check failed: cryptographic SHA-256 hash mismatch or payload bytes corrupted."
        )
    }

    if (isTransferring || status == "TRANSFERRING") {
        return EvidenceUiState.Transferring(expectedHash = hash, progress = transferProgress)
    }

    val path = observation.evidencePath
    if (!path.isNullOrBlank()) {
        val file = File(path)
        if (file.exists() && file.isFile && file.length() > 0L) {
            return EvidenceUiState.Verified(
                filePath = file.absolutePath,
                hash = hash,
                fileSizeBytes = file.length()
            )
        } else {
            // Path specified but file missing or empty on local disk
            return if (status in listOf("PENDING", "PHOTO_PENDING") || !observation.isLocal) {
                EvidenceUiState.PhotoPending(expectedHash = hash)
            } else {
                EvidenceUiState.FailedOrCorrupted(
                    hash = hash,
                    reason = "Evidence file missing or zero-byte on local disk."
                )
            }
        }
    }

    // Has evidence hash but no local file path yet
    return if (status in listOf("PENDING", "PHOTO_PENDING", "UNVERIFIED")) {
        EvidenceUiState.PhotoPending(expectedHash = hash)
    } else {
        EvidenceUiState.PhotoPending(expectedHash = hash)
    }
}

/**
 * Evaluates location fix acquisition freshness (ASTRA-P0-04).
 */
fun formatLocationFreshness(
    observedAtDevice: Long,
    currentTimeMs: Long = System.currentTimeMillis()
): LocationFreshnessInfo {
    val ageMs = (currentTimeMs - observedAtDevice).coerceAtLeast(0L)
    val ageSeconds = ageMs / 1000L
    val ageMinutes = ageSeconds / 60L
    val ageHours = ageMinutes / 60L

    val ageDisplay = when {
        ageSeconds < 60 -> "${ageSeconds}s ago"
        ageMinutes < 60 -> "${ageMinutes}m ago"
        else -> "${ageHours}h ${ageMinutes % 60}m ago"
    }

    return when {
        ageMinutes < 5 -> LocationFreshnessInfo(
            ageMs = ageMs,
            ageDisplay = ageDisplay,
            isLiveRecent = true,
            isStale = false,
            statusLabel = "RECENT FIX (< 5m)",
            statusColor = StatusClear,
            warningMessage = null
        )
        ageMinutes < 30 -> LocationFreshnessInfo(
            ageMs = ageMs,
            ageDisplay = ageDisplay,
            isLiveRecent = false,
            isStale = false,
            statusLabel = "HISTORICAL FIX ($ageDisplay)",
            statusColor = PrimaryAmber,
            warningMessage = null
        )
        else -> LocationFreshnessInfo(
            ageMs = ageMs,
            ageDisplay = ageDisplay,
            isLiveRecent = false,
            isStale = true,
            statusLabel = "STALE FIX ($ageDisplay)",
            statusColor = StatusHazard,
            warningMessage = "Fix is over 30 minutes old. Field conditions or team location may have changed. Do not present or interpret as live tracking."
        )
    }
}

/**
 * Human-readable byte formatting.
 */
fun formatEvidenceBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB (%d bytes)", kb, bytes)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB (%d bytes)", mb, bytes)
}

/**
 * Tactical Detail Screen displaying full observation metadata, 5 verified evidence states,
 * GNSS fix freshness, and full-screen image inspection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservationDetailScreen(
    observation: ObservationEntity?,
    onBackClick: () -> Unit,
    isTransferringEvidence: Boolean = false,
    transferProgress: Float? = null,
    currentTimeMs: Long = System.currentTimeMillis()
) {
    if (observation == null) {
        // Standby / Not Found State
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Observation Details", style = MaterialTheme.typography.titleLarge, color = TextPrimary) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Navigate Back", tint = PrimaryAmber)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(BackgroundDark)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Observation Not Found", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "The requested event may still be syncing across the mesh or was removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onBackClick,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryAmber, contentColor = BackgroundDark),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Return to Overview", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    val isSos = observation.eventType == "SOS"
    val evidenceState = remember(observation, isTransferringEvidence, transferProgress) {
        resolveEvidenceState(observation, isTransferringEvidence, transferProgress)
    }

    var showPhotoViewerDialog by remember { mutableStateOf(false) }

    val categoryColor = when (observation.eventType) {
        "SOS" -> StatusBlocked
        "HAZARD" -> StatusHazard
        "ROUTE_STATUS" -> if (observation.reportedStatus == "OPEN") StatusClear else StatusBlocked
        "PHOTO" -> BlueInfo
        else -> PrimaryAmber
    }

    val categoryIcon = when (observation.eventType) {
        "SOS", "HAZARD" -> Icons.Filled.Warning
        "ROUTE_STATUS" -> Icons.Filled.AltRoute
        "PHOTO" -> Icons.Filled.CameraAlt
        "SURVIVOR" -> Icons.Filled.PersonSearch
        else -> Icons.Filled.Note
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Observation Details",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryAmber
                        )
                        Text(
                            text = "Event ID: ${observation.eventId.take(13)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Navigate Back",
                            tint = PrimaryAmber
                        )
                    }
                },
                actions = {
                    Surface(
                        color = categoryColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.6f)),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = categoryIcon,
                                contentDescription = null,
                                tint = categoryColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = observation.eventType,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = categoryColor
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
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
            // ASTRA-P0-01: SOS Delivery Status Truthfulness Banner
            if (isSos) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1417)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, StatusBlocked),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = StatusBlocked, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "EMERGENCY SOS ALERT",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (observation.isLocal) {
                                "Delivery Status: Saved locally on this phone • Queued for mesh transfer (P >= 0.80 priority floor)."
                            } else {
                                "Delivery Status: Received from mesh peer ${observation.authorDeviceId.take(8)}."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryAmber
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SAFETY NOTICE: Mesh transmission does not guarantee first-responder arrival or dispatch. Personnel must maintain local survival protocols.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Primary Header Card: Title, Status, Origin
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = observation.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        observation.reportedStatus?.let { status ->
                            val statusBg = when (status) {
                                "OPEN", "CLEAR" -> StatusClear
                                "BLOCKED", "COLLAPSED" -> StatusBlocked
                                "HAZARD", "DANGER" -> StatusHazard
                                "CONFLICTED" -> StatusConflicted
                                else -> TextSecondary
                            }
                            Surface(
                                color = statusBg.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, statusBg.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "STATUS: $status",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = statusBg,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Surface(
                            color = if (observation.isLocal) PrimaryAmber.copy(alpha = 0.15f) else BlueInfo.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (observation.isLocal) "ORIGIN: LOCAL PHONE" else "ORIGIN: PEER RELAY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (observation.isLocal) PrimaryAmber else BlueInfo,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (observation.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Divider(color = SurfaceVariant, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Field Notes / Description",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = observation.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }
            }

            // ASTRA-P0-04: Location & Freshness Section
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 1. Clean Title Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = PrimaryAmber, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Geographic Fix & Quality",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. Full-Width Freshness Banner (Never wraps awkwardly)
                    val freshness = formatLocationFreshness(observation.observedAtDevice, currentTimeMs)
                    val freshnessText = if (freshness.isLiveRecent) {
                        "Location updated recently (< 5m)"
                    } else {
                        freshness.statusLabel
                    }

                    Surface(
                        color = freshness.statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, freshness.statusColor.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (freshness.isLiveRecent) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = freshness.statusColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = freshnessText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = freshness.statusColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (observation.latitude != null && observation.longitude != null) {
                        val latStr = String.format(Locale.US, "%.6f°", observation.latitude)
                        val lonStr = String.format(Locale.US, "%.6f°", observation.longitude)
                        val accStr = observation.accuracy?.let { "±${it.toInt()} meters" } ?: "Uncertain / Unknown"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Latitude", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(latStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Uncertainty", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(accStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = PrimaryAmber)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Longitude", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(lonStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Fix Monotonic Age", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(freshness.ageDisplay, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = TextSecondary)
                            }
                        }

                        if (freshness.warningMessage != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = StatusHazard.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = StatusHazard, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = freshness.warningMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusHazard
                                    )
                                }
                            }
                        }
                    } else {
                        // Indoor / Unlocated Fix
                        Surface(
                            color = SurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.ShareLocation, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("UNKNOWN / INDOOR LOCATION", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextSecondary)
                                    Text("Coordinates pending or device operated subterranean/indoor without GPS lock.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                                }
                            }
                        }
                    }

                    if (observation.mapNodeId != null || observation.mapEdgeId != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            observation.mapNodeId?.let {
                                Text("Linked Node: $it", style = MaterialTheme.typography.labelSmall, color = PrimaryAmber, fontFamily = FontFamily.Monospace)
                            }
                            observation.mapEdgeId?.let {
                                Text("Linked Edge: $it", style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // 5-State Photographic Evidence Section
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
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = PrimaryAmber, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Photographic Evidence",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }

                        // State Badge
                        when (evidenceState) {
                            is EvidenceUiState.NoPhoto -> {
                                Surface(color = SurfaceVariant, shape = RoundedCornerShape(6.dp)) {
                                    Text("NO PHOTO", style = MaterialTheme.typography.labelSmall, color = TextTertiary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                            is EvidenceUiState.PhotoPending -> {
                                Surface(color = PrimaryAmber.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                                    Text("PENDING TRANSFER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PrimaryAmber, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                            is EvidenceUiState.Transferring -> {
                                Surface(color = BlueInfo.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                                    Text("TRANSFERRING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = BlueInfo, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                            is EvidenceUiState.Verified -> {
                                Surface(color = StatusClear.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, StatusClear)) {
                                    Text("RECEIVED & VERIFIED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = StatusClear, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                            is EvidenceUiState.FailedOrCorrupted -> {
                                Surface(color = StatusBlocked.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, StatusBlocked)) {
                                    Text("VERIFICATION FAILED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = StatusBlocked, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    when (evidenceState) {
                        is EvidenceUiState.NoPhoto -> {
                            Surface(
                                color = SurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ImageNotSupported,
                                        contentDescription = null,
                                        tint = TextTertiary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "No Photo Attached",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = "This observation contains telemetry and field notes only. No photographic evidence was attached at recording.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextTertiary
                                        )
                                    }
                                }
                            }
                        }

                        is EvidenceUiState.PhotoPending -> {
                            Surface(
                                color = SurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, PrimaryAmber.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = PrimaryAmber, modifier = Modifier.size(28.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text("Photo Evidence Pending Transfer", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = PrimaryAmber)
                                            Text("Awaiting radio proximity or Stage 2 mesh transfer window.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Expected SHA-256 Digest:", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                    Text(
                                        text = evidenceState.expectedHash,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        is EvidenceUiState.Transferring -> {
                            Surface(
                                color = SurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BlueInfo.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = BlueInfo, strokeWidth = 2.5.dp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text("Transferring Photo Payload...", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = BlueInfo)
                                            Text("Receiving verified file bytes over Nearby Connections mesh transport.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    if (evidenceState.progress != null) {
                                        LinearProgressIndicator(
                                            progress = evidenceState.progress,
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                            color = BlueInfo
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "${(evidenceState.progress * 100).toInt()}% completed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                            color = BlueInfo
                                        )
                                    }
                                }
                            }
                        }

                        is EvidenceUiState.Verified -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Bounded Thumbnail Preview
                                val bitmap = remember(evidenceState.filePath) {
                                    try {
                                        BitmapFactory.decodeFile(evidenceState.filePath)
                                    } catch (t: Throwable) {
                                        null
                                    }
                                }

                                if (bitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 220.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SurfaceVariant)
                                            .clickable { showPhotoViewerDialog = true }
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Verified photo evidence",
                                            modifier = Modifier.fillMaxWidth().height(200.dp),
                                            contentScale = ContentScale.Crop
                                        )

                                        Surface(
                                            color = BackgroundDark.copy(alpha = 0.75f),
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = PrimaryAmber, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Tap to enlarge", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                                            }
                                        }
                                    }
                                } else {
                                    // Bitmap decode failed on local file
                                    Surface(
                                        color = SurfaceVariant,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = StatusHazard, modifier = Modifier.size(24.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text("Evidence file present but bitmap preview could not be decoded.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                    }
                                }

                                // Cryptographic Verification Metadata
                                Surface(
                                    color = SurfaceElevated,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("File Size", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                            Text(formatEvidenceBytes(evidenceState.fileSizeBytes), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("SHA-256 Digest (Verified):", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                        Text(
                                            text = evidenceState.hash,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusClear,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Prominent Full-Screen Viewer Button (>= 48dp touch target)
                                Button(
                                    onClick = { showPhotoViewerDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryAmber,
                                        contentColor = BackgroundDark
                                    )
                                ) {
                                    Icon(Icons.Filled.Fullscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("OPEN FULL-SCREEN VIEWER", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        is EvidenceUiState.FailedOrCorrupted -> {
                            Surface(
                                color = SurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, StatusBlocked.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Error, contentDescription = null, tint = StatusBlocked, modifier = Modifier.size(28.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text("Evidence Verification Failure", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = StatusBlocked)
                                            Text(evidenceState.reason, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                    }
                                    if (!evidenceState.hash.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text("Claimed SHA-256 Digest:", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                                        Text(
                                            text = evidenceState.hash,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusBlocked,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Collapsible Technical Diagnostics Accordion
            var isDiagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDiagnosticsExpanded = !isDiagnosticsExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.BugReport,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Technical Diagnostics",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        Icon(
                            imageVector = if (isDiagnosticsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (isDiagnosticsExpanded) "Collapse Diagnostics" else "Expand Diagnostics",
                            tint = PrimaryAmber
                        )
                    }

                    if (!isDiagnosticsExpanded) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to view cryptographic digests, event UUIDs, and relay hops.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }

                    AnimatedVisibility(visible = isDiagnosticsExpanded) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            HorizontalDivider(color = SurfaceVariant, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(8.dp))

                            val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
                            val observedDateStr = timeFormatter.format(Date(observation.observedAtDevice))
                            val receivedDateStr = timeFormatter.format(Date(observation.receivedAt))

                            MetadataRow("Event UUID", observation.eventId, isMonospace = true)
                            MetadataRow("Mission ID", observation.missionId, isMonospace = true)
                            MetadataRow("Author Device", observation.authorDeviceId, isMonospace = true)
                            MetadataRow("Author Sequence", "#${observation.authorSequence}")
                            MetadataRow("Observed At", observedDateStr)
                            MetadataRow("Received At", receivedDateStr)
                            MetadataRow("Relay Hops Remaining", "${observation.relayHopsRemaining} hops")
                            MetadataRow("Verification Status", observation.verificationStatus)

                            if (!observation.evidenceHash.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Evidence SHA-256 Digest:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    text = observation.evidenceHash!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = StatusClear
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-Screen Tactical Photo Viewer Dialog
    if (showPhotoViewerDialog && evidenceState is EvidenceUiState.Verified) {
        TacticalPhotoViewerDialog(
            filePath = evidenceState.filePath,
            sha256 = evidenceState.hash,
            title = observation.title,
            fileSizeBytes = evidenceState.fileSizeBytes,
            onDismiss = { showPhotoViewerDialog = false }
        )
    }
}

@Composable
private fun MetadataRow(label: String, value: String, isMonospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Full-screen tactical photo viewer with native pan/zoom gestures, SHA-256 header,
 * and high-contrast >= 48dp touch targets.
 */
@Composable
fun TacticalPhotoViewerDialog(
    filePath: String,
    sha256: String,
    title: String,
    fileSizeBytes: Long,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val bitmap = remember(filePath) {
            try {
                BitmapFactory.decodeFile(filePath)
            } catch (t: Throwable) {
                null
            }
        }

        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Scaffold(
            topBar = {
                Surface(
                    color = SurfaceDark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "TACTICAL EVIDENCE VIEWER",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryAmber
                                )
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Close Button with >= 48dp touch target
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close Full Screen Viewer",
                                    tint = PrimaryAmber
                                )
                            }
                        }

                        // Cryptographic Monospace SHA-256 Header
                        Surface(
                            color = BackgroundDark,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = StatusClear, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SHA-256: $sha256",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = PrimaryAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Surface(
                    color = SurfaceDark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Size: ${formatEvidenceBytes(fileSizeBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )

                        // Reset Zoom Button (>= 48dp touch target)
                        OutlinedButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryAmber)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Zoom")
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(BackgroundDark)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offset = if (scale > 1f) {
                                Offset(
                                    x = offset.x + pan.x,
                                    y = offset.y + pan.y
                                )
                            } else {
                                Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Full Screen Evidence Photo",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = StatusBlocked, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Image could not be rendered", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
