package com.rescuemesh.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tactical Emergency Chat Destination Tabs.
 */
enum class ChatTab {
    BROADCAST,
    SOS_THREADS
}

/**
 * Tactical UI model for incident thread selection.
 */
data class SosIncidentThread(
    val eventId: String,
    val title: String,
    val severity: String,
    val timestamp: Long,
    val unreadCount: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null
)

/**
 * Emergency Chat Screen.
 *
 * Implements Gate 3 Requirement:
 * 1. Dual tabs: [Broadcast] channel and [SOS-Linked Incident Threads].
 * 2. Incident header with [Map] jump button to view incident on map.
 * 3. Chat bubbles with truthful delivery state chips:
 *    - "Saved locally on this phone"
 *    - "Queued for mesh transfer"
 *    - "Sent to X peers"
 *    Decoupled from responder dispatch (never claims rescue dispatch).
 * 4. Participant badges:
 *    - UNVERIFIED_PARTICIPANT (default for civilian and unauthenticated peers)
 *    - VERIFIED_RESPONDER (only for verified responder role)
 * 5. Minimum 48dp touch targets on all actionable buttons, tabs, and input bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessageEntity> = emptyList(),
    sosIncidents: List<ObservationEntity> = emptyList(),
    initialLinkedEventId: String? = null,
    currentUserId: String = "local-device",
    currentUserName: String = "Tactical Unit",
    onSendMessage: (text: String, linkedEventId: String?) -> Unit = { _, _ -> },
    onNavigateToMap: ((String) -> Unit)? = null
) {
    var selectedTab by remember {
        mutableStateOf(if (initialLinkedEventId != null) ChatTab.SOS_THREADS else ChatTab.BROADCAST)
    }

    // Active selected SOS thread eventId
    var activeLinkedEventId by remember(initialLinkedEventId, sosIncidents) {
        mutableStateOf(initialLinkedEventId ?: sosIncidents.firstOrNull { it.eventType == "SOS" }?.eventId)
    }

    var inputText by remember { mutableStateOf("") }

    // Map SOS observations to thread headers
    val threadItems = remember(sosIncidents) {
        sosIncidents
            .filter { it.eventType == "SOS" || it.eventType == "HAZARD" }
            .map { obs ->
                SosIncidentThread(
                    eventId = obs.eventId,
                    title = obs.title.ifBlank { "Emergency Incident ${obs.eventId.take(6)}" },
                    severity = if (obs.eventType == "SOS") "CRITICAL" else "HAZARD",
                    timestamp = obs.observedAtDevice,
                    latitude = obs.latitude,
                    longitude = obs.longitude
                )
            }
    }

    val activeThread = remember(threadItems, activeLinkedEventId) {
        threadItems.find { it.eventId == activeLinkedEventId }
    }

    // Filter messages according to active tab
    val displayedMessages = remember(messages, selectedTab, activeLinkedEventId) {
        when (selectedTab) {
            ChatTab.BROADCAST -> {
                // Messages without a linked eventId belong to General Broadcast
                messages.filter { it.linkedEventId == null }
            }
            ChatTab.SOS_THREADS -> {
                // Messages tied to the selected SOS eventId
                if (activeLinkedEventId != null) {
                    messages.filter { it.linkedEventId == activeLinkedEventId }
                } else {
                    emptyList()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Tactical Mesh Chat",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2196F3) // Primary Blue
                        )
                        Text(
                            text = if (selectedTab == ChatTab.BROADCAST) "Channel: General Broadcast (All Peers)"
                            else "Channel: SOS-Linked Thread • ${activeThread?.title?.take(20) ?: "Select Case"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B949E)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161B22) // Dark Surface
                )
            )
        },
        bottomBar = {
            // Compose Input Bar with >= 48dp minimum touch target
            Surface(
                color = Color(0xFF161B22),
                border = BorderStroke(1.dp, Color(0xFF30363D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // Target channel pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Surface(
                            color = (if (selectedTab == ChatTab.BROADCAST) Color(0xFF2196F3) else Color(0xFFE53935)).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (selectedTab == ChatTab.BROADCAST) "BROADCAST TO MESH"
                                else "TARGET: ${activeThread?.title?.take(24) ?: "SOS CASE"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == ChatTab.BROADCAST) Color(0xFF2196F3) else Color(0xFFE53935),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${inputText.length} / ${ChatMessageEntity.MAX_TEXT_LENGTH}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (inputText.length > ChatMessageEntity.MAX_TEXT_LENGTH) Color(0xFFE53935) else Color(0xFF8B949E)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = {
                                if (it.length <= ChatMessageEntity.MAX_TEXT_LENGTH) {
                                    inputText = it
                                }
                            },
                            placeholder = {
                                Text(
                                    text = if (selectedTab == ChatTab.BROADCAST) "Message to all mesh peers..."
                                    else "Case detail, landmarks, floor...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF8B949E)
                                )
                            },
                            maxLines = 3,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = Color(0xFF30363D),
                                focusedTextColor = Color(0xFFE6EDF3),
                                unfocusedTextColor = Color(0xFFE6EDF3)
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                val text = inputText.trim()
                                if (text.isNotBlank()) {
                                    val linkedId = if (selectedTab == ChatTab.SOS_THREADS) activeLinkedEventId else null
                                    onSendMessage(text, linkedId)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier
                                .size(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedTab == ChatTab.BROADCAST) Color(0xFF2196F3) else Color(0xFFE53935),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF21262D),
                                disabledContentColor = Color(0xFF484F58)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "Send Message",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0D1117)) // Dark Navy Background
        ) {
            // Dual Tab Bar with guaranteed >= 48dp touch targets
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color(0xFF161B22),
                contentColor = Color(0xFF2196F3),
                divider = { HorizontalDivider(color = Color(0xFF30363D)) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Tab(
                    selected = selectedTab == ChatTab.BROADCAST,
                    onClick = { selectedTab = ChatTab.BROADCAST },
                    modifier = Modifier.heightIn(min = 48.dp),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Campaign,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Broadcast",
                                fontWeight = if (selectedTab == ChatTab.BROADCAST) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                )

                Tab(
                    selected = selectedTab == ChatTab.SOS_THREADS,
                    onClick = { selectedTab = ChatTab.SOS_THREADS },
                    modifier = Modifier.heightIn(min = 48.dp),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Sos,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (selectedTab == ChatTab.SOS_THREADS) Color(0xFFE53935) else Color(0xFF8B949E)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SOS Threads (${threadItems.size})",
                                fontWeight = if (selectedTab == ChatTab.SOS_THREADS) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                )
            }

            // SOS-Linked Incident Header (when on SOS_THREADS tab)
            if (selectedTab == ChatTab.SOS_THREADS) {
                if (threadItems.isEmpty()) {
                    Surface(
                        color = Color(0xFF1F242C),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF30363D))
                    ) {
                        Text(
                            text = "No active SOS incidents reported in this mission sector yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8B949E),
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // Selector row for available threads
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF161B22))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            threadItems.forEach { thread ->
                                val isSelected = thread.eventId == activeLinkedEventId
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { activeLinkedEventId = thread.eventId },
                                    label = {
                                        Text(
                                            text = thread.title.take(16),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = if (thread.severity == "CRITICAL") Color(0xFFE53935) else Color(0xFFFFB300),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFE53935).copy(alpha = 0.2f),
                                        selectedLabelColor = Color(0xFFE53935),
                                        selectedLeadingIconColor = Color(0xFFE53935)
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = if (isSelected) Color(0xFFE53935) else Color(0xFF30363D)
                                    ),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }

                        // Detailed Case Header Card with [Map] jump button
                        activeThread?.let { thread ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF281418)),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = Color(0xFFE53935),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = thread.severity,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = thread.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Case ID: ${thread.eventId.take(8)} • Coordinates: ${
                                                if (thread.latitude != null) String.format(Locale.US, "%.4f, %.4f", thread.latitude, thread.longitude)
                                                else "GPS UNKNOWN"
                                            }",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF8B949E)
                                        )
                                    }

                                    // Mandatory [Map] Jump Button (>= 48dp touch target)
                                    Button(
                                        onClick = { onNavigateToMap?.invoke(thread.eventId) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF2196F3),
                                            contentColor = Color(0xFF0D1117)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Map,
                                            contentDescription = "View on Map",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "MAP",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Broadcast Channel Explanatory Banner
                Surface(
                    color = Color(0xFF161B22),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Broadcast messages propagate to all active mesh peers in radio range. Keep routine chatter concise.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8B949E)
                        )
                    }
                }
            }

            // Message List
            if (displayedMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Forum,
                            contentDescription = null,
                            tint = Color(0xFF30363D),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (selectedTab == ChatTab.BROADCAST) "No broadcast messages yet"
                            else "No messages in this SOS incident thread",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF8B949E)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Send an offline message below to coordinate with field peers",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6E7681)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayedMessages, key = { it.messageId }) { msg ->
                        ChatBubbleItem(
                            message = msg,
                            isCurrentUser = msg.isLocal || msg.senderId == currentUserId
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tactical Chat Bubble Component.
 *
 * Implements truthful delivery status progression and explicit participant badges.
 */
@Composable
fun ChatBubbleItem(
    message: ChatMessageEntity,
    isCurrentUser: Boolean
) {
    val isVerifiedResponder = message.senderRole == ChatMessageEntity.ROLE_VERIFIED_RESPONDER
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val timeStr = remember(message.timestamp) { timeFormatter.format(Date(message.timestamp)) }

    // Truthful Delivery Badge String
    val deliveryStatusText = remember(message.status, message.ackPeerCount, message.isLocal) {
        when (message.status) {
            ChatMessageEntity.STATUS_SENDING -> "Saved locally"
            ChatMessageEntity.STATUS_QUEUED -> "Queued"
            ChatMessageEntity.STATUS_SENT_TO_PEERS -> {
                if (message.ackPeerCount <= 1) "Sent to 1 peer"
                else "Sent to ${message.ackPeerCount} peers"
            }
            ChatMessageEntity.STATUS_DELIVERED_TO_ALL -> "Delivered to mesh cluster"
            ChatMessageEntity.STATUS_FAILED -> "Failed to queue"
            ChatMessageEntity.STATUS_RECEIVED -> "Received"
            else -> if (message.isLocal) "Saved locally" else "Received"
        }
    }

    val deliveryStatusColor = when (message.status) {
        ChatMessageEntity.STATUS_SENT_TO_PEERS, ChatMessageEntity.STATUS_DELIVERED_TO_ALL -> Color(0xFF00BFA5)
        ChatMessageEntity.STATUS_QUEUED -> Color(0xFFFFB300)
        ChatMessageEntity.STATUS_FAILED -> Color(0xFFE53935)
        else -> Color(0xFF8B949E)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCurrentUser) Alignment.End else Alignment.Start
    ) {
        // Author metadata line
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = message.senderName.ifBlank { message.senderId.take(8) },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFC9D1D9)
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Participant Role Badge: UNVERIFIED_PARTICIPANT vs VERIFIED_RESPONDER
            Surface(
                color = if (isVerifiedResponder) Color(0xFF00BFA5).copy(alpha = 0.2f) else Color(0xFFFFB300).copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = if (isVerifiedResponder) "VERIFIED RESPONDER" else "UNVERIFIED",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isVerifiedResponder) Color(0xFF00BFA5) else Color(0xFFFFB300),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = timeStr,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6E7681)
            )
        }

        // Bubble Container
        Surface(
            color = if (isCurrentUser) Color(0xFF1F242C) else Color(0xFF161B22),
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isCurrentUser) 12.dp else 2.dp,
                bottomEnd = if (isCurrentUser) 2.dp else 12.dp
            ),
            border = BorderStroke(
                1.dp,
                if (isCurrentUser) Color(0xFF2196F3).copy(alpha = 0.4f) else Color(0xFF30363D)
            ),
            modifier = Modifier
                .widthIn(min = 120.dp, max = 320.dp)
                .padding(vertical = 2.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE6EDF3)
                )

                if (isCurrentUser) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (message.status) {
                                ChatMessageEntity.STATUS_SENT_TO_PEERS, ChatMessageEntity.STATUS_DELIVERED_TO_ALL -> Icons.Filled.DoneAll
                                ChatMessageEntity.STATUS_QUEUED -> Icons.Filled.Schedule
                                else -> Icons.Filled.Done
                            },
                            contentDescription = null,
                            tint = deliveryStatusColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = deliveryStatusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = deliveryStatusColor
                        )
                    }
                }
            }
        }
    }
}
