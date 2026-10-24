package com.rescuemesh.ai.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.location.LocationResult
import com.rescuemesh.ai.maps.BoundingBox
import com.rescuemesh.ai.maps.OfflineMapManager
import com.rescuemesh.ai.maps.OfflineRegionModel
import com.rescuemesh.ai.maps.OfflineRegionStatus
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

/**
 * Adapts canonical [OfflineRegionModel] into UI [OfflineRegion].
 */
fun OfflineRegionModel.toUiModel(): OfflineRegion {
    val uiStatus = when (status) {
        OfflineRegionStatus.DOWNLOADED -> RegionDownloadStatus.DOWNLOADED
        OfflineRegionStatus.DOWNLOADING, OfflineRegionStatus.QUEUED -> RegionDownloadStatus.DOWNLOADING
        OfflineRegionStatus.FAILED -> RegionDownloadStatus.FAILED
        OfflineRegionStatus.AVAILABLE -> RegionDownloadStatus.AVAILABLE
    }
    return OfflineRegion(
        id = regionId,
        name = name,
        description = description,
        minLat = minLat,
        maxLat = maxLat,
        minLon = minLon,
        maxLon = maxLon,
        sizeBytes = fileSizeBytes,
        status = uiStatus,
        downloadProgress = if (status == OfflineRegionStatus.DOWNLOADED) 1.0f else 0.0f,
        isMissionActive = status == OfflineRegionStatus.DOWNLOADED
    )
}

/**
 * Selectable radius options for GPS-centered bounding box downloads.
 */
enum class GpsRadiusOption(
    val label: String,
    val radiusMeters: Float,
    val estimatedBytes: Long
) {
    TEN_METERS("10m × 10m", 10f, 1_048_576L),
    FIVE_HUNDRED_METERS("500m × 500m", 500f, 8_388_608L),
    ONE_KILOMETER("1km × 1km (Default)", 1000f, 16_777_216L),
    THREE_KILOMETERS("3km × 3km", 3000f, 47_185_920L),
    AUTO("Auto", 1000f, 16_777_216L),
    MANUAL("Manual", 1000f, 16_777_216L)
}

/**
 * Offline Maps Management Screen.
 *
 * Implements Gate 3 Requirement:
 * 1. Storage quota meter (truthful physical disk usage vs allocated quota).
 * 2. Unmetered Wi-Fi default with explicit mobile data cellular preference toggle.
 * 3. WorkManager download triggers for regional vector packages.
 * 4. GPS-centered bounding box downloads with cosine latitude scaling.
 * 5. Automatic fallback indicator to GPS-only Situational Canvas.
 * 6. Minimum 48dp touch targets on all actionable buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    regions: List<OfflineRegion>? = null,
    offlineMapManager: OfflineMapManager? = null,
    currentLocation: LocationResult? = null,
    quotaTotalBytes: Long = 1024L * 1024L * 1024L, // 1 GB quota
    onNavigateToSituationalCanvas: () -> Unit = {},
    onDownloadRegion: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteRegion: (String) -> Unit = {},
    onUpdateRegion: (String) -> Unit = {}
) {
    val context = LocalContext.current.applicationContext
    val manager = remember(offlineMapManager, context) {
        offlineMapManager ?: OfflineMapManager(context, initialMaxQuotaBytes = quotaTotalBytes)
    }

    val managerRegions by manager.regionsFlow.collectAsState()
    val effectiveRegions = remember(managerRegions) {
        managerRegions.map { it.toUiModel() }
    }

    var localRegionsOverride by remember(regions) { mutableStateOf(regions) }
    val displayRegions = localRegionsOverride ?: effectiveRegions

    var allowCellular by remember { mutableStateOf(false) }
    var showCellularWarningDialog by remember { mutableStateOf(false) }
    var selectedRadiusOption by remember { mutableStateOf(GpsRadiusOption.ONE_KILOMETER) }
    var showManualAreaDialog by remember { mutableStateOf(false) }

    var manualLat by remember { mutableStateOf("12.9716") }
    var manualLon by remember { mutableStateOf("77.5946") }
    var manualRadius by remember { mutableStateOf("1000") }

    val totalUsedBytes = remember(displayRegions, managerRegions) {
        if (localRegionsOverride != null) {
            displayRegions.filter { it.status == RegionDownloadStatus.DOWNLOADED }.sumOf { it.sizeBytes }
        } else {
            manager.getTotalStorageUsedBytes()
        }
    }

    val downloadedCount = remember(displayRegions) {
        displayRegions.count { it.status == RegionDownloadStatus.DOWNLOADED }
    }

    val hasActiveDownloadedRegion = downloadedCount > 0

    // Coordinate resolution for GPS bounding box
    val effectiveLat = remember(currentLocation, selectedRadiusOption, manualLat) {
        if (selectedRadiusOption == GpsRadiusOption.MANUAL) {
            manualLat.toDoubleOrNull() ?: (currentLocation?.latitude ?: 12.9716)
        } else {
            currentLocation?.latitude ?: 12.9716
        }
    }

    val effectiveLon = remember(currentLocation, selectedRadiusOption, manualLon) {
        if (selectedRadiusOption == GpsRadiusOption.MANUAL) {
            manualLon.toDoubleOrNull() ?: (currentLocation?.longitude ?: 77.5946)
        } else {
            currentLocation?.longitude ?: 77.5946
        }
    }

    val effectiveRadius = remember(currentLocation, selectedRadiusOption, manualRadius) {
        when (selectedRadiusOption) {
            GpsRadiusOption.AUTO -> {
                val acc = currentLocation?.accuracy ?: 100f
                if (acc <= 20f) 500f else 1000f
            }
            GpsRadiusOption.MANUAL -> {
                (manualRadius.toFloatOrNull() ?: 1000f).coerceIn(10f, 50000f)
            }
            else -> selectedRadiusOption.radiusMeters
        }
    }

    val previewBox = remember(effectiveLat, effectiveLon, effectiveRadius) {
        OfflineRegionModel.calculateBoundingBox(effectiveLat, effectiveLon, effectiveRadius)
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
                            text = if (hasActiveDownloadedRegion) "Offline Vector Coverage: Active ($downloadedCount packages)"
                            else "No offline map downloaded • Using GPS Situational Canvas",
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

            // Truthful Fallback Banner (Prominent Amber banner when 0 downloaded packages on disk)
            item {
                if (!hasActiveDownloadedRegion) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2114)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.8f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Radar,
                                        contentDescription = null,
                                        tint = Color(0xFFFFB300),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "No offline map downloaded • Using GPS Situational Canvas",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFB300)
                                    )
                                }
                                Surface(
                                    color = Color(0xFFFFB300).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "RADAR ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFFB300),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No offline vector package is present in device storage. RescueMesh AI operates 100% offline using live GPS fixes, cardinal compass bearing, proximity tracking, and P2P mesh alerts on the Situational Canvas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC9D1D9)
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onNavigateToSituationalCanvas,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFB300),
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
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF13231F)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF00BFA5).copy(alpha = 0.6f)),
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
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF00BFA5),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Vector Streets Available",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Surface(
                                    color = Color(0xFF00BFA5).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "READY",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00BFA5),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Offline vector tiles and glyph assets installed ($downloadedCount packages). Full offline street rendering enabled.",
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
                                    containerColor = Color(0xFF2196F3),
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
            }

            // Real GPS-Centered Bounding Box Download Card
            item {
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
                                    imageVector = Icons.Filled.MyLocation,
                                    contentDescription = null,
                                    tint = Color(0xFF2196F3),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Download Current GPS Area",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE6EDF3)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        val locText = if (currentLocation != null) {
                            String.format(
                                Locale.US,
                                "GPS Center: %.4f°N, %.4f°E (Accuracy: ±%.1fm)",
                                currentLocation.latitude,
                                currentLocation.longitude,
                                currentLocation.accuracy ?: 10f
                            )
                        } else {
                            "GPS Fix Pending • Default center: 12.9716°N, 77.5946°E"
                        }
                        Text(
                            text = locText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentLocation != null) Color(0xFF00BFA5) else Color(0xFFFFB300)
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Select Sector Radius:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8B949E)
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        // Radius selection grid
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                GpsRadiusOption.entries.take(3).forEach { option ->
                                    val isSelected = selectedRadiusOption == option
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedRadiusOption = option },
                                        label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF2196F3),
                                            selectedLabelColor = Color(0xFF0D1117)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 40.dp)
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                GpsRadiusOption.entries.drop(3).forEach { option ->
                                    val isSelected = selectedRadiusOption == option
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedRadiusOption = option
                                            if (option == GpsRadiusOption.MANUAL) {
                                                showManualAreaDialog = true
                                            }
                                        },
                                        label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF2196F3),
                                            selectedLabelColor = Color(0xFF0D1117)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 40.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        // Calculated Bounding Box with Cosine Scaling preview
                        Surface(
                            color = Color(0xFF1F242C),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF30363D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Bounding Box (Cosine Latitude Scaled):",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8B949E)
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.4f°N - %.4f°N, %.4f°E - %.4f°E",
                                        previewBox.minLat, previewBox.maxLat, previewBox.minLon, previewBox.maxLon
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF2196F3)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Estimated Size: ~${selectedRadiusOption.estimatedBytes / (1024 * 1024)} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFC9D1D9)
                                    )
                                    Text(
                                        text = if (allowCellular) "Cellular Allowed" else "Wi-Fi Only",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allowCellular) Color(0xFFFFB300) else Color(0xFF00BFA5)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val created = manager.createGpsAreaRegion(
                                    latitude = effectiveLat,
                                    longitude = effectiveLon,
                                    radiusMeters = effectiveRadius
                                )
                                manager.enqueueDownload(created.regionId, allowCellular)
                                onDownloadRegion(created.regionId, !allowCellular)
                            },
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
                                text = "DOWNLOAD GPS SECTOR (${selectedRadiusOption.label})",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // Storage Quota Progress Card & Cellular Toggle
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
                                text = "Vector Packages: $downloadedCount active",
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

                        // Cellular Download Toggle with Confirmation Dialog
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Allow downloads using mobile data",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE6EDF3)
                                )
                                Text(
                                    text = if (allowCellular) "Mobile data permitted (potential 10–70 MB usage)"
                                    else "Unmetered Wi-Fi only (preserves mobile data quota)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (allowCellular) Color(0xFFFFB300) else Color(0xFF8B949E)
                                )
                            }
                            Switch(
                                checked = allowCellular,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showCellularWarningDialog = true
                                    } else {
                                        allowCellular = false
                                    }
                                },
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
                        text = "Operational Sector Packages (${displayRegions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6EDF3)
                    )
                }
            }

            // List of Region Cards
            items(displayRegions, key = { it.id }) { region ->
                OfflineRegionCard(
                    region = region,
                    unmeteredOnly = !allowCellular,
                    onDownload = {
                        if (localRegionsOverride != null) {
                            localRegionsOverride = localRegionsOverride?.map { r ->
                                if (r.id == region.id) r.copy(status = RegionDownloadStatus.DOWNLOADING, downloadProgress = 0.15f)
                                else r
                            }
                        }
                        manager.enqueueDownload(region.id, allowCellular)
                        onDownloadRegion(region.id, !allowCellular)
                    },
                    onDelete = {
                        if (localRegionsOverride != null) {
                            localRegionsOverride = localRegionsOverride?.map { r ->
                                if (r.id == region.id) r.copy(status = RegionDownloadStatus.AVAILABLE, downloadProgress = 0f)
                                else r
                            }
                        }
                        manager.deleteRegion(region.id)
                        onDeleteRegion(region.id)
                    },
                    onUpdate = {
                        manager.enqueueDownload(region.id, allowCellular)
                        onUpdateRegion(region.id)
                    }
                )
            }
        }
    }

    // Cellular Confirmation Dialog
    if (showCellularWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCellularWarningDialog = false },
            title = {
                Text(
                    text = "Allow downloads using mobile data?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB300)
                )
            },
            text = {
                Text(
                    text = "Downloading offline vector maps over cellular networks may consume between 10 MB and 70 MB of mobile data per sector. In disaster zones, preserving cellular bandwidth and battery is critical. Are you sure you want to enable cellular downloads?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC9D1D9)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        allowCellular = true
                        showCellularWarningDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFB300),
                        contentColor = Color(0xFF0D1117)
                    )
                ) {
                    Text("ALLOW CELLULAR", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showCellularWarningDialog = false }
                ) {
                    Text("CANCEL")
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    // Manual Area Selection Dialog
    if (showManualAreaDialog) {
        AlertDialog(
            onDismissRequest = { showManualAreaDialog = false },
            title = {
                Text(
                    text = "Manual Bounding Box Selection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2196F3)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Specify coordinates and radius for custom disaster sector:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B949E)
                    )
                    OutlinedTextField(
                        value = manualLat,
                        onValueChange = { manualLat = it },
                        label = { Text("Center Latitude (-90 to 90)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualLon,
                        onValueChange = { manualLon = it },
                        label = { Text("Center Longitude (-180 to 180)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualRadius,
                        onValueChange = { manualRadius = it },
                        label = { Text("Radius in meters (e.g. 500, 1000, 3000)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showManualAreaDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3),
                        contentColor = Color(0xFF0D1117)
                    )
                ) {
                    Text("APPLY", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showManualAreaDialog = false }
                ) {
                    Text("CANCEL")
                }
            },
            containerColor = Color(0xFF161B22)
        )
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
                            text = if (unmeteredOnly) "DOWNLOAD (WI-FI ONLY)" else "DOWNLOAD (CELLULAR)",
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
