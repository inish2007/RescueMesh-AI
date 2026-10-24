package com.rescuemesh.ai.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.rescuemesh.ai.ui.theme.*
import java.util.Locale

/**
 * Download state for tactical offline vector map regions.
 */
enum class RegionDownloadStatus {
    AVAILABLE,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

/**
 * Model representing an operational map sector package.
 */
data class OfflineRegion(
    val id: String,
    val name: String,
    val description: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val sizeBytes: Long,
    val status: RegionDownloadStatus,
    val downloadProgress: Float = 0f,
    val isMissionActive: Boolean = false
) {
    val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
    val boundsString: String get() = String.format(
        Locale.US,
        "%.2f°N - %.2f°N, %.2f°E - %.2f°E",
        minLat, maxLat, minLon, maxLon
    )
}

val defaultOfflineRegions = listOf(
    OfflineRegion(
        id = "region-alpha-metro",
        name = "Sector Alpha — Metro Core",
        description = "Urban search, structural corridors, and civilian rally points",
        minLat = 12.92,
        maxLat = 13.04,
        minLon = 77.52,
        maxLon = 77.68,
        sizeBytes = 47_448_064L, // ~45.2 MB
        status = RegionDownloadStatus.DOWNLOADED,
        downloadProgress = 1.0f,
        isMissionActive = true
    ),
    OfflineRegion(
        id = "region-bravo-basin",
        name = "Sector Bravo — North River Basin",
        description = "Flood plain lowlands, drainage canals, and levee roads",
        minLat = 13.04,
        maxLat = 13.18,
        minLon = 77.48,
        maxLon = 77.65,
        sizeBytes = 71_413_760L, // ~68.1 MB
        status = RegionDownloadStatus.AVAILABLE,
        downloadProgress = 0.0f,
        isMissionActive = false
    ),
    OfflineRegion(
        id = "region-charlie-industrial",
        name = "Sector Charlie — Industrial Rail Hub",
        description = "Hazardous material buffer, rail freight depot, and port access",
        minLat = 12.80,
        maxLat = 12.95,
        minLon = 77.62,
        maxLon = 77.78,
        sizeBytes = 54_743_040L, // ~52.2 MB
        status = RegionDownloadStatus.AVAILABLE,
        downloadProgress = 0.0f,
        isMissionActive = false
    )
)

/**
 * Offline Maps Management Screen.
 *
 * Implements Gate 3 Requirement:
 * 1. Storage quota meter (allocated vs used).
 * 2. Unmetered Wi-Fi download toggle (preserves P2P mesh radio bandwidth).
 * 3. WorkManager download triggers for regional vector packages.
 * 4. Region status (Downloaded, Downloading, Available, Failed).
 * 5. Automatic fallback indicator to GPS-only Situational Canvas.
 * 6. Minimum 48dp touch targets on all actionable buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    regions: List<OfflineRegion> = defaultOfflineRegions,
    quotaTotalBytes: Long = 1024L * 1024L * 1024L, // 1 GB quota
    onNavigateToSituationalCanvas: () -> Unit = {},
    onDownloadRegion: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteRegion: (String) -> Unit = {},
    onUpdateRegion: (String) -> Unit = {}
) {
    var regionList by remember(regions) { mutableStateOf(regions) }
    var unmeteredOnly by remember { mutableStateOf(true) }

    val totalUsedBytes = remember(regionList) {
        regionList.filter { it.status == RegionDownloadStatus.DOWNLOADED }
            .sumOf { it.sizeBytes }
    }

    val hasActiveDownloadedRegion = remember(regionList) {
        regionList.any { it.isMissionActive && it.status == RegionDownloadStatus.DOWNLOADED }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Offline Maps & Storage",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2196F3) // Primary Blue
                        )
                        Text(
                            text = if (hasActiveDownloadedRegion) "Offline Vector Coverage: Active"
                            else "Fallback Active: GPS Situational Canvas",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasActiveDownloadedRegion) Color(0xFF00BFA5) else Color(0xFFFFB300)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161B22) // Dark Surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0D1117)) // Dark Navy Background
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Mandatory Safety Disclaimer Banner
            item {
                Surface(
                    color = Color(0xFF1F242C),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Safety Notice",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Downloaded streets provide geographic spatial reference only. Road geometries do NOT prove passable conditions or structural safety in a disaster zone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC9D1D9)
                        )
                    }
                }
            }

            // Automatic Fallback Indicator & Direct Jump
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasActiveDownloadedRegion) Color(0xFF13231F) else Color(0xFF2A2114)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (hasActiveDownloadedRegion) Color(0xFF00BFA5).copy(alpha = 0.6f)
                        else Color(0xFFFFB300).copy(alpha = 0.6f)
                    ),
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
                                    imageVector = if (hasActiveDownloadedRegion) Icons.Filled.CheckCircle else Icons.Filled.Radar,
                                    contentDescription = null,
                                    tint = if (hasActiveDownloadedRegion) Color(0xFF00BFA5) else Color(0xFFFFB300),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (hasActiveDownloadedRegion) "Vector Streets Available"
                                    else "Automatic Fallback Active",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Surface(
                                color = (if (hasActiveDownloadedRegion) Color(0xFF00BFA5) else Color(0xFFFFB300)).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (hasActiveDownloadedRegion) "READY" else "RADAR FALLBACK",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasActiveDownloadedRegion) Color(0xFF00BFA5) else Color(0xFFFFB300),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (hasActiveDownloadedRegion)
                                "Offline vector tiles and glyph assets installed for active sector. Full offline street rendering enabled."
                            else
                                "No offline vector package downloaded for current sector. The system automatically routes all rendering to the GPS-Only Situational Canvas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B949E)
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onNavigateToSituationalCanvas,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasActiveDownloadedRegion) Color(0xFF2196F3) else Color(0xFFFFB300),
                                contentColor = Color(0xFF0D1117)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Navigation,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OPEN SITUATIONAL CANVAS",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Storage Quota Progress Card
            item {
                val usedMb = totalUsedBytes / (1024.0 * 1024.0)
                val quotaMb = quotaTotalBytes / (1024.0 * 1024.0)
                val quotaFraction = (totalUsedBytes.toFloat() / quotaTotalBytes.toFloat()).coerceIn(0f, 1f)

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
                            Text(
                                text = "Device Storage Quota",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE6EDF3)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f MB / %.0f MB", usedMb, quotaMb),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2196F3)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { quotaFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF2196F3),
                            trackColor = Color(0xFF21262D)
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Vector Packages: ${regionList.count { it.status == RegionDownloadStatus.DOWNLOADED }} active",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8B949E)
                            )
                            Text(
                                text = "${((1f - quotaFraction) * quotaMb).toInt()} MB Free",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF00BFA5)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFF30363D))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Wi-Fi Constraint Toggle (>=48dp touch target)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Unmetered Wi-Fi Only",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE6EDF3)
                                )
                                Text(
                                    text = "Avoids saturating P2P mesh radios and mobile data",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8B949E)
                                )
                            }
                            Switch(
                                checked = unmeteredOnly,
                                onCheckedChange = { unmeteredOnly = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF0D1117),
                                    checkedTrackColor = Color(0xFF2196F3)
                                ),
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            }

            // Available Sector Regions Header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = null,
                        tint = Color(0xFF2196F3),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Operational Sector Packages (${regionList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6EDF3)
                    )
                }
            }

            // List of Region Cards
            items(regionList, key = { it.id }) { region ->
                OfflineRegionCard(
                    region = region,
                    unmeteredOnly = unmeteredOnly,
                    onDownload = {
                        regionList = regionList.map { r ->
                            if (r.id == region.id) r.copy(status = RegionDownloadStatus.DOWNLOADING, downloadProgress = 0.15f)
                            else r
                        }
                        onDownloadRegion(region.id, unmeteredOnly)
                    },
                    onDelete = {
                        regionList = regionList.map { r ->
                            if (r.id == region.id) r.copy(status = RegionDownloadStatus.AVAILABLE, downloadProgress = 0f)
                            else r
                        }
                        onDeleteRegion(region.id)
                    },
                    onUpdate = {
                        onUpdateRegion(region.id)
                    }
                )
            }
        }
    }
}

/**
 * Tactical Region Item Card.
 */
@Composable
fun OfflineRegionCard(
    region: OfflineRegion,
    unmeteredOnly: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit
) {
    val statusColor = when (region.status) {
        RegionDownloadStatus.DOWNLOADED -> Color(0xFF00BFA5)
        RegionDownloadStatus.DOWNLOADING -> Color(0xFF2196F3)
        RegionDownloadStatus.AVAILABLE -> Color(0xFFFFB300)
        RegionDownloadStatus.FAILED -> Color(0xFFE53935)
    }

    val statusLabel = when (region.status) {
        RegionDownloadStatus.DOWNLOADED -> "DOWNLOADED"
        RegionDownloadStatus.DOWNLOADING -> "DOWNLOADING ${(region.downloadProgress * 100).toInt()}%"
        RegionDownloadStatus.AVAILABLE -> "AVAILABLE"
        RegionDownloadStatus.FAILED -> "FAILED"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (region.isMissionActive) Color(0xFF2196F3) else Color(0xFF30363D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = region.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6EDF3)
                    )
                }

                Surface(
                    color = statusColor.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = region.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8B949E)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CropFree,
                        contentDescription = null,
                        tint = Color(0xFF8B949E),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = region.boundsString,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8B949E)
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f MB", region.sizeMb),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2196F3)
                )
            }

            if (region.status == RegionDownloadStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { region.downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF2196F3),
                    trackColor = Color(0xFF21262D)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons with guaranteed >= 48dp touch targets
            when (region.status) {
                RegionDownloadStatus.AVAILABLE -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2196F3),
                            contentColor = Color(0xFF0D1117)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (unmeteredOnly) "DOWNLOAD (WI-FI ONLY)" else "DOWNLOAD NOW",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                RegionDownloadStatus.DOWNLOADED -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFE53935)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete", style = MaterialTheme.typography.labelMedium)
                        }

                        Button(
                            onClick = onUpdate,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00BFA5),
                                contentColor = Color(0xFF0D1117)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Update", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                RegionDownloadStatus.DOWNLOADING -> {
                    OutlinedButton(
                        onClick = onDelete, // Cancels download
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFE53935)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.5f))
                    ) {
                        Text("Cancel Download", style = MaterialTheme.typography.labelMedium)
                    }
                }

                RegionDownloadStatus.FAILED -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry Download", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
