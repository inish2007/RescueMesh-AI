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
import com.rescuemesh.ai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservationScreen(
    onSaveObservation: (
        type: String,
        title: String,
        description: String,
        status: String?,
        lat: Double?,
        lon: Double?,
        accuracy: Float?
    ) -> Unit
) {
    var selectedType by remember { mutableStateOf("ROUTE_STATUS") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var reportedStatus by remember { mutableStateOf("BLOCKED") }
    var mockGpsActive by remember { mutableStateOf(true) }
    var savedSuccess by remember { mutableStateOf(false) }

    val observationTypes = listOf("ROUTE_STATUS", "HAZARD", "NOTE", "SURVIVOR")
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
                            "OPEN" -> StatusSafe
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

            // Location card
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
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.MyLocation,
                                contentDescription = null,
                                tint = PrimaryAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "GPS Coordinate Lock",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (mockGpsActive) "Lat: 12.9716, Lon: 77.5946 (±3.2m)" else "Location unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = mockGpsActive,
                        onCheckedChange = { mockGpsActive = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BackgroundDark,
                            checkedTrackColor = PrimaryAmber
                        )
                    )
                }
            }

            if (savedSuccess) {
                Surface(
                    color = StatusSafe.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "✓ Observation recorded & queued in priority outbox!",
                        color = StatusSafe,
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
                        val lat = if (mockGpsActive) 12.9716 else null
                        val lon = if (mockGpsActive) 77.5946 else null
                        val accuracy = if (mockGpsActive) 3.2f else null
                        onSaveObservation(
                            selectedType,
                            title,
                            description,
                            reportedStatus,
                            lat,
                            lon,
                            accuracy
                        )
                        title = ""
                        description = ""
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
