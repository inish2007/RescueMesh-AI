package com.rescuemesh.ai.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.data.entity.PeerEntity
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    peers: List<PeerEntity>,
    isAdvertising: Boolean,
    isDiscovering: Boolean,
    onToggleMesh: () -> Unit,
    onForceSync: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Mesh Peer Topology",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${peers.count { it.isConnected }} Active • ${peers.size} Known",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onForceSync) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = "Sync All Peers",
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Radio Transport Control Card
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isAdvertising || isDiscovering) StatusClear else StatusBlocked)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAdvertising || isDiscovering) "Mesh Radio Active" else "Mesh Radio Standby",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Button(
                                onClick = onToggleMesh,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAdvertising || isDiscovering) StatusBlocked else PrimaryAmber,
                                    contentColor = if (isAdvertising || isDiscovering) Color.White else BackgroundDark
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(if (isAdvertising || isDiscovering) "Stop Mesh" else "Start Mesh")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Advertising: ${if (isAdvertising) "ON" else "OFF"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAdvertising) StatusClear else TextTertiary
                            )
                            Text(
                                text = "Discovering: ${if (isDiscovering) "ON" else "OFF"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDiscovering) StatusClear else TextTertiary
                            )
                            Text(
                                text = "Cluster P2P",
                                style = MaterialTheme.typography.bodySmall,
                                color = PrimaryAmber
                            )
                        }
                    }
                }
            }

            // Peer List Header
            item {
                Text(
                    text = "Discovered Mesh Nodes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (peers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.WifiFind,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Scanning for Nearby Responder Devices...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Bring two devices within Bluetooth/WiFi-Direct range",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                    }
                }
            } else {
                items(peers, key = { it.deviceId }) { peer ->
                    PeerCard(peer)
                }
            }
        }
    }
}

@Composable
fun PeerCard(peer: PeerEntity) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (peer.isConnected) StatusClear else TextTertiary)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = peer.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "ID: ${peer.deviceId.take(12)}... • Endpoint: ${peer.endpointId ?: "None"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }

                Surface(
                    color = if (peer.isConnected) StatusClear.copy(alpha = 0.15f) else SurfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (peer.isConnected) "CONNECTED" else "OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (peer.isConnected) StatusClear else TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = SurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Tx: ${peer.messagesSent} msgs (${peer.bytesSent / 1024} KB)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "Rx: ${peer.messagesReceived} msgs (${peer.bytesReceived / 1024} KB)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                val lastSyncStr = peer.lastSyncAt?.let {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                } ?: "Never"
                Text(
                    text = "Sync: $lastSyncStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrimaryAmber
                )
            }
        }
    }
}
