package com.rescuemesh.ai.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.data.entity.MetricsEventEntity
import com.rescuemesh.ai.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsScreen(
    metrics: List<MetricsEventEntity>,
    totalObservations: Int,
    connectedPeersCount: Int,
    outboxQueueDepth: Int = 0,
    syncEventsCount: Int = 0,
    bytesSentTotal: Long? = null,
    bytesReceivedTotal: Long? = null,
    onExportAuditLog: () -> Unit
) {
    var selectedScheduler by remember { mutableStateOf("VOI") }
    var exportSuccess by remember { mutableStateOf(false) }

    val effectiveBytesSent = bytesSentTotal ?: metrics.filter { it.metricType == "SEND" || it.metricType == "BYTES_SENT" }
        .sumOf { it.bytesTransferred?.toLong() ?: 0L }
    val effectiveBytesReceived = bytesReceivedTotal ?: metrics.filter { it.metricType == "RECEIVE" || it.metricType == "BYTES_RECEIVED" }
        .sumOf { it.bytesTransferred?.toLong() ?: 0L }
    val effectiveSyncCount = if (syncEventsCount > 0) syncEventsCount else metrics.count {
        it.metricType == "SYNC" || it.metricType == "STATE_DIGEST" || it.metricType == "RECONCILIATION"
    }
    val effectiveQueueDepth = if (outboxQueueDepth > 0) outboxQueueDepth else {
        metrics.firstOrNull { it.metricType == "OUTBOX_QUEUE_DEPTH" }?.bytesTransferred ?: 0
    }
    val avgLatency = metrics.mapNotNull { it.latencyMs }.let {
        if (it.isNotEmpty()) it.average().toLong() else 0L
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Research & Evaluation",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tactical Telemetry & Mesh Metrics",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onExportAuditLog()
                        exportSuccess = true
                    }) {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = "Export Audit Log",
                            tint = PrimaryAmber
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BackgroundDark)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Tactical Telemetry Dashboard Header (Features 25 & 26)
            item {
                Text(
                    text = "Live Mesh Transport Telemetry",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // Tactical Dashboard Cards: Outbox Queue, Bytes Sent, Bytes Received
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricKpiCard(
                        title = "Outbox Queue Depth",
                        value = "$effectiveQueueDepth",
                        subtitle = if (effectiveQueueDepth == 0) "Queue Clear" else "Pending Mesh",
                        valueColor = if (effectiveQueueDepth > 0) StatusConflicted else StatusClear,
                        modifier = Modifier.weight(1f)
                    )
                    MetricKpiCard(
                        title = "Bytes Transmitted",
                        value = "${effectiveBytesSent / 1024} KB",
                        subtitle = "Tx: $effectiveBytesSent B",
                        modifier = Modifier.weight(1f)
                    )
                    MetricKpiCard(
                        title = "Bytes Received",
                        value = "${effectiveBytesReceived / 1024} KB",
                        subtitle = "Rx: $effectiveBytesReceived B",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // State Reconciliation & Mission Activity Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricKpiCard(
                        title = "State Syncs",
                        value = "$effectiveSyncCount",
                        subtitle = "Reconciled",
                        valueColor = Color(0xFF64B5F6),
                        modifier = Modifier.weight(1f)
                    )
                    MetricKpiCard(
                        title = "Observations",
                        value = "$totalObservations",
                        subtitle = "Synced",
                        modifier = Modifier.weight(1f)
                    )
                    MetricKpiCard(
                        title = "Avg Latency",
                        value = "${avgLatency}ms",
                        subtitle = "P2P roundtrip",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Scheduler Comparison / Ablation Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Scheduler Policy Ablation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Compare Value-of-Information (VoI) ranking against classic baseline queuing protocols under bandwidth caps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("VOI (Ours)", "FIFO", "RANDOM").forEach { scheduler ->
                                val code = scheduler.split(" ").first()
                                val isSelected = selectedScheduler == code
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedScheduler = code },
                                    label = { Text(scheduler) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PrimaryAmber,
                                        selectedLabelColor = BackgroundDark,
                                        containerColor = SurfaceVariant,
                                        labelColor = TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (exportSuccess) {
                item {
                    Surface(
                        color = StatusClear.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "✓ Audit trail exported to app external storage!",
                            color = StatusClear,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Live Decision Events Header
            item {
                Text(
                    text = "Decision & Transfer Events (${metrics.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            if (metrics.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No metrics events recorded yet. Perform sync or create observations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            } else {
                items(metrics.take(30), key = { it.id }) { event ->
                    MetricEventRow(event)
                }
            }
        }
    }
}

@Composable
fun MetricKpiCard(
    title: String,
    value: String,
    subtitle: String,
    valueColor: Color = PrimaryAmber,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
    }
}

@Composable
fun MetricEventRow(event: MetricsEventEntity) {
    val typeColor = when (event.metricType) {
        "SEND", "BYTES_SENT" -> StatusClear
        "RECEIVE", "BYTES_RECEIVED" -> PrimaryAmber
        "SYNC", "STATE_DIGEST", "RECONCILIATION" -> Color(0xFF64B5F6)
        "QUEUE_DECISION", "OUTBOX_QUEUE_DEPTH" -> StatusConflicted
        "CONNECTION", "PEERS_CONNECTED" -> StatusClear
        "DISCONNECT" -> StatusBlocked
        else -> TextSecondary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.metricType,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = typeColor
                    )
                    event.schedulerType?.let {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "[$it]",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.details ?: "Peer: ${event.peerId?.take(8) ?: "Broadcast"} • Bytes: ${event.bytesTransferred ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))
            Text(
                text = timeStr,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
    }
}
