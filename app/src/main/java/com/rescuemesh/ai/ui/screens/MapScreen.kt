package com.rescuemesh.ai.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    nodes: List<MapNodeEntity>,
    edges: List<MapEdgeEntity>,
    onFindRoute: (fromNodeId: String, toNodeId: String) -> RoutePlanner.RouteResult?
) {
    var selectedFromNode by remember { mutableStateOf<String?>("N1") }
    var selectedToNode by remember { mutableStateOf<String?>("N6") }
    var routeResult by remember { mutableStateOf<RoutePlanner.RouteResult?>(null) }

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
                            text = "${nodes.size} Nodes • ${edges.size} Edges",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
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
                            tint = PrimaryAmber
                        )
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
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val hitNode = nodes.find { node ->
                                val pos = getNodeOffset(node.nodeId, size.width.toFloat(), size.height.toFloat())
                                (pos - offset).getDistance() < 50f
                            }
                            if (hitNode != null) {
                                if (selectedFromNode == null || selectedToNode != null) {
                                    selectedFromNode = hitNode.nodeId
                                    selectedToNode = null
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

                // Draw Edges
                edges.forEach { edge ->
                    val fromPos = getNodeOffset(edge.fromNodeId, canvasWidth, canvasHeight)
                    val toPos = getNodeOffset(edge.toNodeId, canvasWidth, canvasHeight)

                    val edgeColor = when (edge.routeStatus) {
                        "REPORTED_OPEN", "VERIFIED_OPEN" -> StatusSafe
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

                // Draw Nodes
                nodes.forEach { node ->
                    val pos = getNodeOffset(node.nodeId, canvasWidth, canvasHeight)
                    val isFrom = node.nodeId == selectedFromNode
                    val isTo = node.nodeId == selectedToNode
                    val isInRoute = routeResult?.path?.contains(node.nodeId) ?: false

                    val nodeColor = when {
                        isFrom -> StatusSafe
                        isTo -> StatusBlocked
                        isInRoute -> PrimaryAmber
                        else -> SurfaceElevated
                    }

                    drawCircle(
                        color = nodeColor,
                        radius = 22.dp.toPx(),
                        center = pos
                    )

                    drawCircle(
                        color = TextPrimary,
                        radius = 22.dp.toPx(),
                        center = pos,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )

                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 28f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        node.nodeId,
                        pos.x,
                        pos.y + 10f,
                        paint
                    )
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
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Start: ${selectedFromNode ?: "Tap"} ➔ Goal: ${selectedToNode ?: "Tap"}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
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
                                    contentColor = BackgroundDark
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Find Path", fontWeight = FontWeight.Bold)
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
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusSafe,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Route: ${res.path.joinToString(" ➔ ")} (${String.format("%.1f", res.totalDistance)}m)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusSafe
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

private fun getNodeOffset(nodeId: String, width: Float, height: Float): Offset {
    val cx = width / 2
    val cy = height / 2 - 40f
    val scale = minOf(width, height) * 0.35f

    return when (nodeId) {
        "N1" -> Offset(cx - scale * 0.8f, cy - scale * 0.8f)
        "N2" -> Offset(cx + scale * 0.8f, cy - scale * 0.8f)
        "N3" -> Offset(cx - scale * 0.9f, cy)
        "N4" -> Offset(cx, cy)
        "N5" -> Offset(cx + scale * 0.9f, cy)
        "N6" -> Offset(cx - scale * 0.6f, cy + scale * 0.8f)
        "N7" -> Offset(cx + scale * 0.6f, cy + scale * 0.8f)
        else -> Offset(cx, cy)
    }
}
