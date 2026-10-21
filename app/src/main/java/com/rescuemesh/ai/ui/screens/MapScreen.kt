package com.rescuemesh.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.ui.theme.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    nodes: List<MapNodeEntity>,
    edges: List<MapEdgeEntity>,
    onFindRoute: (fromNodeId: String, toNodeId: String) -> RoutePlanner.RouteResult?
) {
    var selectedFromNode by remember { mutableStateOf<String?>(null) }
    var selectedToNode by remember { mutableStateOf<String?>(null) }
    var routeResult by remember { mutableStateOf<RoutePlanner.RouteResult?>(null) }

    // Clear stale node selections if nodes change (e.g. mission switch or data wipe)
    LaunchedEffect(nodes) {
        val knownIds = nodes.map { it.nodeId }.toSet()
        if (selectedFromNode != null && selectedFromNode !in knownIds) {
            selectedFromNode = null
            routeResult = null
        }
        if (selectedToNode != null && selectedToNode !in knownIds) {
            selectedToNode = null
            routeResult = null
        }
    }

    val density = LocalDensity.current
    val padHorizontalPx = with(density) { 48.dp.toPx() }
    val padTopPx = with(density) { 48.dp.toPx() }
    val padBottomPx = with(density) { 160.dp.toPx() } // Room for bottom route panel
    val hitRadiusPx = with(density) { 36.dp.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Tactical Graph & Routes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (nodes.isEmpty()) "0 Nodes • 0 Edges (Standby)" else "${nodes.size} Nodes • ${edges.size} Edges",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    if (nodes.isNotEmpty()) {
                        IconButton(onClick = {
                            val from = selectedFromNode
                            val to = selectedToNode
                            if (from != null && to != null) {
                                routeResult = onFindRoute(from, to)
                            }
                        }) {
                            Icon(
                                Icons.Filled.Directions,
                                contentDescription = "Calculate Route",
                                tint = if (selectedFromNode != null && selectedToNode != null) PrimaryAmber else TextTertiary
                            )
                        }
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
        ) {
            if (nodes.isEmpty()) {
                // Feature 11: Tactical Empty State Card
                TacticalEmptyMapCard(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                // Feature 12: Dynamic Canvas Graph Rendering
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(nodes) {
                            detectTapGestures { offset ->
                                val positions = MapCoordinateProjector.project(
                                    nodes = nodes,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                    paddingLeft = padHorizontalPx,
                                    paddingTop = padTopPx,
                                    paddingRight = padHorizontalPx,
                                    paddingBottom = padBottomPx
                                )

                                val hitNode = nodes.find { node ->
                                    val pos = positions[node.nodeId] ?: return@find false
                                    (pos - offset).getDistance() < hitRadiusPx
                                }

                                if (hitNode != null) {
                                    if (selectedFromNode == null || selectedToNode != null) {
                                        selectedFromNode = hitNode.nodeId
                                        selectedToNode = null
                                        routeResult = null
                                    } else if (selectedFromNode == hitNode.nodeId) {
                                        // Tapping same start node deselects it
                                        selectedFromNode = null
                                        routeResult = null
                                    } else {
                                        selectedToNode = hitNode.nodeId
                                        routeResult = onFindRoute(selectedFromNode!!, hitNode.nodeId)
                                    }
                                }
                            }
                        }
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    val positions = MapCoordinateProjector.project(
                        nodes = nodes,
                        width = canvasWidth,
                        height = canvasHeight,
                        paddingLeft = padHorizontalPx,
                        paddingTop = padTopPx,
                        paddingRight = padHorizontalPx,
                        paddingBottom = padBottomPx
                    )

                    // Draw Edges
                    edges.forEach { edge ->
                        val fromPos = positions[edge.fromNodeId]
                        val toPos = positions[edge.toNodeId]

                        if (fromPos != null && toPos != null) {
                            val edgeColor = when (edge.routeStatus) {
                                "REPORTED_OPEN", "VERIFIED_OPEN" -> StatusClear
                                "REPORTED_BLOCKED", "VERIFIED_BLOCKED" -> StatusBlocked
                                "CONFLICTED" -> StatusConflicted
                                "HAZARD" -> StatusHazard
                                else -> TextTertiary
                            }

                            val isHighlightedInRoute = routeResult?.edgesUsed?.contains(edge.edgeId) ?: false
                            val strokeWidth = if (isHighlightedInRoute) 8.dp.toPx() else 3.dp.toPx()
                            val drawColor = if (isHighlightedInRoute) PrimaryAmber else edgeColor

                            val pathEffect = if (edge.routeStatus == "UNKNOWN") {
                                PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                            } else null

                            drawLine(
                                color = drawColor,
                                start = fromPos,
                                end = toPos,
                                strokeWidth = strokeWidth,
                                pathEffect = pathEffect
                            )
                        }
                    }

                    // Draw Nodes
                    nodes.forEach { node ->
                        val pos = positions[node.nodeId] ?: return@forEach
                        val isFrom = node.nodeId == selectedFromNode
                        val isTo = node.nodeId == selectedToNode
                        val isInRoute = routeResult?.path?.contains(node.nodeId) ?: false

                        val nodeColor = when {
                            isFrom -> StatusClear
                            isTo -> StatusBlocked
                            isInRoute -> PrimaryAmber
                            else -> SurfaceElevated
                        }

                        // Node Circle
                        drawCircle(
                            color = nodeColor,
                            radius = 22.dp.toPx(),
                            center = pos
                        )

                        // Outer boundary ring
                        drawCircle(
                            color = TextPrimary,
                            radius = 22.dp.toPx(),
                            center = pos,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )

                        // Center node code text
                        val nodeCode = if (node.nodeId.length <= 4) {
                            node.nodeId.uppercase()
                        } else {
                            node.nodeId.split("-", "_")
                                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                .joinToString("")
                                .take(3)
                                .ifEmpty { node.nodeId.take(3).uppercase() }
                        }

                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 24f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                        }

                        drawContext.canvas.nativeCanvas.drawText(
                            nodeCode,
                            pos.x,
                            pos.y + 8f,
                            paint
                        )

                        // Subtitle node label below circle
                        if (node.label.isNotBlank()) {
                            val labelPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#8B949E") // TextSecondary
                                textSize = 20f
                                textAlign = android.graphics.Paint.Align.CENTER
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                node.label.take(16),
                                pos.x,
                                pos.y + 34.dp.toPx(),
                                labelPaint
                            )
                        }
                    }
                }

                // Route Bottom Panel
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val fromLabel = nodes.find { it.nodeId == selectedFromNode }?.label
                        ?: selectedFromNode ?: "Tap node"
                    val toLabel = nodes.find { it.nodeId == selectedToNode }?.label
                        ?: selectedToNode ?: "Tap node"

                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.95f)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SurfaceBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Start: $fromLabel",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selectedFromNode != null) StatusClear else TextSecondary
                                    )
                                    Text(
                                        text = "Goal: $toLabel",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selectedToNode != null) StatusBlocked else TextSecondary
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selectedFromNode != null || selectedToNode != null) {
                                        IconButton(onClick = {
                                            selectedFromNode = null
                                            selectedToNode = null
                                            routeResult = null
                                        }) {
                                            Icon(
                                                Icons.Filled.Clear,
                                                contentDescription = "Reset Selection",
                                                tint = TextTertiary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            val from = selectedFromNode
                                            val to = selectedToNode
                                            if (from != null && to != null) {
                                                routeResult = onFindRoute(from, to)
                                            }
                                        },
                                        enabled = selectedFromNode != null && selectedToNode != null,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PrimaryAmber,
                                            contentColor = BackgroundDark,
                                            disabledContainerColor = SurfaceElevated,
                                            disabledContentColor = TextTertiary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Find Path", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            routeResult?.let { res ->
                                Spacer(modifier = Modifier.height(10.dp))
                                Divider(color = SurfaceVariant)
                                Spacer(modifier = Modifier.height(8.dp))

                                if (!res.isComplete || res.path.isEmpty()) {
                                    Text(
                                        text = "⚠️ No passable route found. Target path obstructed.",
                                        color = StatusBlocked,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Navigation,
                                            contentDescription = null,
                                            tint = PrimaryAmber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val displayPath = if (res.pathLabels.isNotEmpty()) {
                                            res.pathLabels.joinToString(" ➔ ")
                                        } else {
                                            res.path.joinToString(" ➔ ")
                                        }
                                        Text(
                                            text = "Route: $displayPath (Distance: ${String.format("%.1f", res.totalDistance)}m, Cost: ${String.format("%.0f", res.totalCost)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryAmber
                                        )
                                    }

                                    if (res.warnings.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        res.warnings.forEach { warning ->
                                            Text(
                                                text = "• $warning",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StatusHazard
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tactical empty-state card rendered when no surveyed graph nodes exist in the active mission.
 */
@Composable
fun TacticalEmptyMapCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(PrimaryAmber.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ShareLocation,
                    contentDescription = "No Survey Nodes",
                    tint = PrimaryAmber,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Survey Nodes Available",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Topological map will populate automatically as mesh observations and surveyed waypoints are received from field units.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = SurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, SurfaceBorder.copy(alpha = 0.5f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(PrimaryAmber)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Awaiting Mesh Graph Data",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
