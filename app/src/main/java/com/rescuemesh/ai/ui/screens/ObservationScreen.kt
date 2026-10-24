package com.rescuemesh.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import com.rescuemesh.ai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservationScreen(
    currentLocation: com.rescuemesh.ai.location.LocationResult? = null,
    evidenceManager: com.rescuemesh.ai.evidence.EvidenceManager? = null,
    onSaveObservation: (
        type: String,
        title: String,
        description: String,
        status: String?,
        lat: Double?,
        lon: Double?,
        accuracy: Float?,
        evidencePath: String?,
        evidenceHash: String?
    ) -> Unit
) {
    var selectedType by remember { mutableStateOf("ROUTE_STATUS") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var indoorSector by remember { mutableStateOf("") }
    var reportedStatus by remember { mutableStateOf("BLOCKED") }
    var savedSuccess by remember { mutableStateOf(false) }

    // Evidence Photo Capture State
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedPhotoHash by rememberSaveable { mutableStateOf<String?>(null) }
    var photoErrorMessage by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = pendingPhotoPath
        if (success && path != null) {
            val file = java.io.File(path)
            if (file.exists() && file.isFile && file.length() > 0L) {
                try {
                    val hash = evidenceManager?.computeSha256(file) ?: ""
                    capturedPhotoPath = file.absolutePath
                    capturedPhotoHash = hash
                    photoErrorMessage = null
                } catch (e: Exception) {
                    evidenceManager?.cleanupFile(file)
                    capturedPhotoPath = null
                    capturedPhotoHash = null
                    photoErrorMessage = "Evidence hashing failed: ${e.message}"
                }
            } else {
                evidenceManager?.cleanupFile(file) ?: run { if (file.exists()) file.delete() }
                capturedPhotoPath = null
                capturedPhotoHash = null
                photoErrorMessage = "Captured image file was empty or corrupted."
            }
        } else {
            // Cancelled or camera error: clean up empty created file immediately
            path?.let {
                val f = java.io.File(it)
                evidenceManager?.cleanupFile(f) ?: run { if (f.exists()) f.delete() }
            }
            if (!success && path != null) {
                photoErrorMessage = "Camera capture cancelled"
            }
        }
        pendingPhotoPath = null
    }

    val observationTypes = listOf("ROUTE_STATUS", "HAZARD", "SOS", "NOTE", "SURVIVOR")
    val statuses = listOf("OPEN", "BLOCKED", "HAZARD", "UNKNOWN")

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Record Observation",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Type Selection
            Text(
                text = "Observation Type",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                observationTypes.forEach { type ->
                    val isSelected = selectedType == type
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedType = type },
                        label = { Text(type.replace("_", " ")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryAmber,
                            selectedLabelColor = BackgroundDark,
                            containerColor = SurfaceDark,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            // Status Chip Selector (for Route Status / Hazard)
            if (selectedType == "ROUTE_STATUS" || selectedType == "HAZARD") {
                Text(
                    text = "Reported Status",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statuses.forEach { status ->
                        val isSelected = reportedStatus == status
                        val chipColor = when (status) {
                            "OPEN" -> StatusClear
                            "BLOCKED" -> StatusBlocked
                            "HAZARD" -> StatusHazard
                            else -> TextSecondary
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { reportedStatus = status },
                            label = { Text(status) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipColor,
                                selectedLabelColor = Color.White,
                                containerColor = SurfaceDark,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
            }

            // Title
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title / Summary") },
                placeholder = { Text("e.g. Corridor B ceiling collapsed") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryAmber,
                    focusedLabelColor = PrimaryAmber,
                    cursorColor = PrimaryAmber,
                    unfocusedContainerColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark
                )
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Detailed Observation Notes") },
                placeholder = { Text("Add specific evidence, alternate passability notes...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryAmber,
                    focusedLabelColor = PrimaryAmber,
                    cursorColor = PrimaryAmber,
                    unfocusedContainerColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark
                )
            )

            // Indoor Survey / Landmark Field (Optional)
            OutlinedTextField(
                value = indoorSector,
                onValueChange = { indoorSector = it },
                label = { Text("Indoor Sector / Landmark (Optional)") },
                placeholder = { Text("e.g. Room 204, Stairwell B, East Corridor") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryAmber,
                    unfocusedBorderColor = SurfaceBorder,
                    focusedLabelColor = PrimaryAmber,
                    unfocusedLabelColor = TextSecondary,
                    cursorColor = PrimaryAmber,
                    unfocusedContainerColor = SurfaceDark,
                    focusedContainerColor = SurfaceDark
                )
            )

            // Location card (Genuine Android Location or UNKNOWN Fallback)
            // Photo Evidence Section
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CameraAlt,
                                contentDescription = null,
                                tint = PrimaryAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Photo Evidence",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }

                        if (capturedPhotoPath == null) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        evidenceManager?.let { mgr ->
                                            val (uri, file) = mgr.createEvidenceUri()
                                            pendingPhotoPath = file.absolutePath
                                            cameraLauncher.launch(uri)
                                        }
                                    } catch (e: ActivityNotFoundException) {
                                        pendingPhotoPath?.let { p ->
                                            val f = java.io.File(p)
                                            evidenceManager?.cleanupFile(f) ?: run { if (f.exists()) f.delete() }
                                        }
                                        pendingPhotoPath = null
                                        photoErrorMessage = "No camera application available on this device"
                                    } catch (e: Exception) {
                                        pendingPhotoPath?.let { p ->
                                            val f = java.io.File(p)
                                            evidenceManager?.cleanupFile(f) ?: run { if (f.exists()) f.delete() }
                                        }
                                        pendingPhotoPath = null
                                        photoErrorMessage = "Unable to start camera: ${e.message}"
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryAmber)
                            ) {
                                Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Attach Photo Evidence", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (capturedPhotoPath != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = SurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val bitmap = remember(capturedPhotoPath) {
                                        capturedPhotoPath?.let { path ->
                                            try {
                                                BitmapFactory.decodeFile(path)
                                            } catch (e: Throwable) {
                                                null
                                            }
                                        }
                                    }

                                    if (bitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Evidence thumbnail",
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .background(SurfaceVariant, RoundedCornerShape(6.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Photo,
                                                contentDescription = "Thumbnail placeholder",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    val file = java.io.File(capturedPhotoPath!!)
                                    val sizeKb = (file.length() / 1024L).coerceAtLeast(1L)

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = StatusClear,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = file.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                maxLines = 1
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Size: $sizeKb KB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Surface(
                                    color = BackgroundDark,
                                    shape = RoundedCornerShape(4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = PrimaryAmber,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "SHA-256: ${capturedPhotoHash ?: "computing..."}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = PrimaryAmber
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            capturedPhotoPath?.let { p ->
                                                val f = java.io.File(p)
                                                evidenceManager?.cleanupFile(f)
                                            }
                                            capturedPhotoPath = null
                                            capturedPhotoHash = null
                                            try {
                                                evidenceManager?.let { mgr ->
                                                    val (uri, f) = mgr.createEvidenceUri()
                                                    pendingPhotoPath = f.absolutePath
                                                    cameraLauncher.launch(uri)
                                                }
                                            } catch (e: ActivityNotFoundException) {
                                                pendingPhotoPath?.let { p ->
                                                    val f = java.io.File(p)
                                                    evidenceManager?.cleanupFile(f) ?: run { if (f.exists()) f.delete() }
                                                }
                                                pendingPhotoPath = null
                                                photoErrorMessage = "No camera application available on this device"
                                            } catch (e: Exception) {
                                                pendingPhotoPath?.let { p ->
                                                    val f = java.io.File(p)
                                                    evidenceManager?.cleanupFile(f) ?: run { if (f.exists()) f.delete() }
                                                }
                                                pendingPhotoPath = null
                                                photoErrorMessage = "Unable to start camera: ${e.message}"
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryAmber),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Retake", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    OutlinedButton(
                                        onClick = {
                                            capturedPhotoPath?.let { p ->
                                                val f = java.io.File(p)
                                                evidenceManager?.cleanupFile(f)
                                            }
                                            capturedPhotoPath = null
                                            capturedPhotoHash = null
                                            photoErrorMessage = null
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusBlocked),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusBlocked.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Delete", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    if (photoErrorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠ $photoErrorMessage",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusBlocked
                        )
                    }
                }
            }

            // GPS & Indoor Location Indicator
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (currentLocation != null) Icons.Filled.MyLocation else Icons.Filled.LocationOff,
                                contentDescription = null,
                                tint = if (currentLocation != null) StatusClear else StatusBlocked,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (currentLocation != null) {
                                Surface(
                                    color = StatusClear.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusClear.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "[LOCATION ACQUIRED: ${currentLocation.provider.uppercase()}]",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = StatusClear,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    color = StatusBlocked.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusBlocked.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "[UNKNOWN LOCATION]",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = StatusBlocked,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (currentLocation != null) {
                                val accStr = currentLocation.accuracy?.let { " (±${String.format("%.1f", it)}m)" } ?: ""
                                "Lat: ${String.format("%.5f", currentLocation.latitude)}, Lon: ${String.format("%.5f", currentLocation.longitude)}$accStr"
                            } else {
                                "GPS unavailable (subterranean or shielded). Observation will record without coordinates."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            if (savedSuccess) {
                Surface(
                    color = StatusClear.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "✓ Observation recorded & queued in priority outbox!",
                        color = StatusClear,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Submit Button
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val lat = currentLocation?.latitude
                        val lon = currentLocation?.longitude
                        val accuracy = currentLocation?.accuracy
                        val fullDesc = if (indoorSector.isNotBlank()) {
                            "[Sector: ${indoorSector.trim()}] $description".trim()
                        } else {
                            description
                        }
                        onSaveObservation(
                            selectedType,
                            title,
                            fullDesc,
                            reportedStatus,
                            lat,
                            lon,
                            accuracy,
                            capturedPhotoPath,
                            capturedPhotoHash
                        )
                        title = ""
                        description = ""
                        indoorSector = ""
                        capturedPhotoPath = null
                        capturedPhotoHash = null
                        photoErrorMessage = null
                        savedSuccess = true
                    }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryAmber,
                    contentColor = BackgroundDark,
                    disabledContainerColor = SurfaceVariant,
                    disabledContentColor = TextTertiary
                )
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Broadcast Observation",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
