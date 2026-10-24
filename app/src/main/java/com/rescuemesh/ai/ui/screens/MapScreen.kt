package com.rescuemesh.ai.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.intelligence.AutoFitViewportCalculator
import com.rescuemesh.ai.intelligence.CameraMode
import com.rescuemesh.ai.intelligence.CameraStateMachine
import com.rescuemesh.ai.intelligence.CameraTarget
import com.rescuemesh.ai.intelligence.GeoPoint
import com.rescuemesh.ai.intelligence.MarkerCluster
import com.rescuemesh.ai.intelligence.MarkerClusterEngine
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.location.LocationResult
import com.rescuemesh.ai.ui.theme.*

/**
 * Tactical 3-Tier Map Presentation:
 * Tier 1: Surveyed graph with Dijkstra routing when nodes.isNotEmpty()
 * Tier 2: Dynamic Auto-Fit Situational Canvas with Clustering & 4 Camera Modes when nodes.isEmpty()
 * Tier 3: Unlocated situational feed when nodes.isEmpty() and no coordinates are available
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    nodes: List<MapNodeEntity> = emptyList(),
    edges: List<MapEdgeEntity> = emptyList(),
    observations: List<ObservationEntity> = emptyList(),
    userLocation: LocationResult? = null,
    onNavigateToObservationDetail: ((String) -> Unit)? = null,
    onNavigateToChat: ((String) -> Unit)? = null,
    onFindRoute: (fromNodeId: String, toNodeId: String) -> RoutePlanner.RouteResult?
) {
    var selectedFromNode by remember { mutableStateOf<String?>(null) }
    var selectedToNode by remember { mutableStateOf<String?>(null) }
    var routeResult by remember { mutableStateOf<RoutePlanner.RouteResult?>(null) }

    // Situational canvas selected point for inspection
    var selectedSituationalPoint by remember { mutableStateOf<ProjectedSituationalPoint?>(null) }

    // Clear stale node selections if nodes change
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
    val padBottomPx = with(density) { 160.dp.toPx() }
    val hitRadiusPx = with(density) { 36.dp.toPx() }

    val hasSurveyedGraph = nodes.isNotEmpty()
    val geoObservations = remember(observations) {
        observations.filter { it.latitude != null && it.longitude != null }
    }
    val hasCoordinates = userLocation != null || geoObservations.isNotEmpty()

    val topBarTitle = when {
        hasSurveyedGraph -> "Tactical Graph & Routes"
        hasCoordinates -> "Situational Canvas"
        else -> "Tactical Standby"
    }

    val topBarSubtitle = when {
        hasSurveyedGraph -> "${nodes.size} Nodes • ${edges.size} Edges"
        hasCoordinates -> "1 km × 1 km • ${geoObservations.size} Geo Reports"
        else -> "GPS Unavailable • ${observations.size} Unlocated Reports"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = topBarTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = topBarSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    if (hasSurveyedGraph) {
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
            when {
                // Tier 1: Surveyed Topological Graph with Dijkstra routing
                hasSurveyedGraph -> {
                    SurveyedGraphView(
                        nodes = nodes,
                        edges = edges,
                        selectedFromNode = selectedFromNode,
                        selectedToNode = selectedToNode,
                        routeResult = routeResult,
                        padHorizontalPx = padHorizontalPx,
                        padTopPx = padTopPx,
                        padBottomPx = padBottomPx,
                        hitRadiusPx = hitRadiusPx,
                        onNodeTapped = { hitNodeId ->
                            if (selectedFromNode == null || selectedToNode != null) {
                                selectedFromNode = hitNodeId
                                selectedToNode = null
                                routeResult = null
                            } else if (selectedFromNode == hitNodeId) {
                                selectedFromNode = null
                                routeResult = null
                            } else {
                                selectedToNode = hitNodeId
                                routeResult = onFindRoute(selectedFromNode!!, hitNodeId)
                            }
                        },
                        onClearRoute = {
                            selectedFromNode = null
                            selectedToNode = null
                            routeResult = null
                        },
                        onFindRouteClick = {
                            val from = selectedFromNode
                            val to = selectedToNode
                            if (from != null && to != null) {
                                routeResult = onFindRoute(from, to)
                            }
                        }
                    )
                }

                // Tier 2: 1 km x 1 km Metric Situational Canvas
                hasCoordinates -> {
                    SituationalCanvasView(
                        geoObservations = geoObservations,
                        userLocation = userLocation,
                        selectedPoint = selectedSituationalPoint,
                        onPointSelected = { selectedSituationalPoint = it },
                        onDismissSelection = { selectedSituationalPoint = null },
                        onNavigateToObservationDetail = onNavigateToObservationDetail,
                        onNavigateToChat = onNavigateToChat
                    )
                }

                // Tier 3: Unlocated Observation Feed & Standby Notice
                else -> {
                    UnlocatedStandbyFeedView(
                        observations = observations,
                        onObservationClick = onNavigateToObservationDetail,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

private val TacticalBlue = Color(0xFF2196F3)

/**
 * Tier 2: 1 km x 1 km metric situational canvas plotting SOS, hazards, and observations
 * centered on the user's GPS or the centroid of active field reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SituationalCanvasView(
    geoObservations: List<ObservationEntity>,
    userLocation: LocationResult?,
    selectedPoint: ProjectedSituationalPoint?,
    onPointSelected: (ProjectedSituationalPoint) -> Unit,
    onDismissSelection: () -> Unit,
    onNavigateToObservationDetail: ((String) -> Unit)? = null,
    onNavigateToChat: ((String) -> Unit)? = null
) {
    val density = LocalDensity.current
    val padPx = with(density) { 36.dp.toPx() }
    val tapHitRadiusPx = with(density) { 36.dp.toPx() }

    val allGeoPoints = remember(geoObservations, userLocation) {
        val obsPoints = geoObservations.mapNotNull { obs ->
            val lat = obs.latitude
            val lon = obs.longitude
            if (lat != null && lon != null) GeoPoint(lat, lon) else null
        }
        if (userLocation != null) obsPoints + GeoPoint(userLocation.latitude, userLocation.longitude) else obsPoints
    }

    val cameraStateMachine = remember {
        val initialCenterLat = userLocation?.latitude ?: geoObservations.firstOrNull()?.latitude ?: 0.0
        val initialCenterLon = userLocation?.longitude ?: geoObservations.firstOrNull()?.longitude ?: 0.0
        val initialLoc = if (userLocation != null) GeoPoint(userLocation.latitude, userLocation.longitude) else null
        CameraStateMachine(
            com.rescuemesh.ai.intelligence.TacticalCameraState(
                mode = if (userLocation != null) CameraMode.FOLLOW_ME else CameraMode.FIT_ALL,
                centerLat = initialCenterLat,
                centerLon = initialCenterLon,
                userLocation = initialLoc
            )
        )
    }
    var cameraState by remember { mutableStateOf(cameraStateMachine.state) }

    // Synchronize user location updates
    LaunchedEffect(userLocation?.latitude, userLocation?.longitude) {
        if (userLocation != null) {
            cameraState = cameraStateMachine.onUserLocationUpdated(
                latitude = userLocation.latitude,
                longitude = userLocation.longitude,
                allPoints = allGeoPoints
            )
        }
    }

    // Synchronize observation updates
    LaunchedEffect(geoObservations) {
        cameraState = cameraStateMachine.onPointsUpdated(allGeoPoints)
    }

    val points = remember(geoObservations) {
        geoObservations.map { obs ->
            SituationalPoint(
                id = obs.eventId,
                latitude = obs.latitude!!,
                longitude = obs.longitude!!,
                label = obs.title,
                type = obs.eventType,
                status = obs.reportedStatus,
                accuracy = obs.accuracy,
                timestamp = obs.observedAtDevice,
                isLocal = obs.isLocal,
                rawObservation = obs
            )
        }
    }

    var currentLayout by remember { mutableStateOf<SituationalCanvasLayout?>(null) }
    var selectedCluster by remember { mutableStateOf<MarkerCluster?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points, cameraState.centerLat, cameraState.centerLon, cameraState.radiusMeters) {
                    detectTapGestures { offset ->
                        val layout = currentLayout ?: return@detectTapGestures
                        val clusters = MarkerClusterEngine.cluster(layout.projectedPoints, tapHitRadiusPx)
                        val hitCluster = MarkerClusterEngine.findClusterAt(clusters, offset, tapHitRadiusPx)
                        if (hitCluster != null) {
                            if (hitCluster.isCluster) {
                                selectedCluster = hitCluster
                            } else {
                                val hitProj = layout.projectedPoints.find { it.point.id == hitCluster.representativePoint.id }
                                if (hitProj != null) {
                                    onPointSelected(hitProj)
                                }
                            }
                        } else {
                            onDismissSelection()
                            selectedCluster = null
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val layout = MapCoordinateProjector.projectSituationalCanvas(
                centerLat = cameraState.centerLat,
                centerLon = cameraState.centerLon,
                points = points,
                width = canvasWidth,
                height = canvasHeight,
                radiusMeters = cameraState.radiusMeters,
                paddingPx = padPx
            )
            currentLayout = layout

            val center = layout.centerCanvasOffset
            val radiusPx = layout.radiusPx

            // Tactical Circular Radar Scope Background
            drawCircle(
                color = SurfaceDark.copy(alpha = 0.50f),
                radius = radiusPx,
                center = center
            )
            drawCircle(
                color = SurfaceBorder.copy(alpha = 0.85f),
                radius = radiusPx,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Crosshairs (North-South, East-West)
            val crosshairEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            drawLine(
                color = SurfaceBorder.copy(alpha = 0.5f),
                start = Offset(center.x, center.y - radiusPx),
                end = Offset(center.x, center.y + radiusPx),
                strokeWidth = 1.dp.toPx(),
                pathEffect = crosshairEffect
            )
            drawLine(
                color = SurfaceBorder.copy(alpha = 0.5f),
                start = Offset(center.x - radiusPx, center.y),
                end = Offset(center.x + radiusPx, center.y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = crosshairEffect
            )

            // Concentric Dynamic Metric Distance Rings (Calculated via AutoFitViewportCalculator)
            val dynamicRings = AutoFitViewportCalculator.calculateDynamicRings(cameraState.radiusMeters)
            val ringEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            val ringTextPaint = Paint().apply {
                color = android.graphics.Color.parseColor("#8B949E")
                textSize = 20f
                textAlign = Paint.Align.CENTER
            }

            dynamicRings.forEach { meters ->
                val rPx = meters * layout.pixelsPerMeter
                if (rPx <= radiusPx) {
                    drawCircle(
                        color = SurfaceBorder.copy(alpha = 0.45f),
                        radius = rPx,
                        center = center,
                        style = Stroke(width = 1.dp.toPx(), pathEffect = ringEffect)
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        AutoFitViewportCalculator.formatDistance(meters),
                        center.x,
                        center.y - rPx + 16f,
                        ringTextPaint
                    )
                }
            }

            // Cardinal Direction Identifiers (North, South, East, West)
            val cardinalPaint = Paint().apply {
                color = android.graphics.Color.parseColor("#E6EDF3")
                textSize = 26f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText("N", center.x, center.y - radiusPx - 10.dp.toPx(), cardinalPaint)
            drawContext.canvas.nativeCanvas.drawText("S", center.x, center.y + radiusPx + 22.dp.toPx(), cardinalPaint)
            drawContext.canvas.nativeCanvas.drawText("E", center.x + radiusPx + 16.dp.toPx(), center.y + 8f, cardinalPaint)
            drawContext.canvas.nativeCanvas.drawText("W", center.x - radiusPx - 16.dp.toPx(), center.y + 8f, cardinalPaint)

            // Live User Location Marker (Blue core, accuracy halo, heading cone)
            var userCenterOffset: Offset? = null
            if (userLocation != null) {
                val deltaLat = userLocation.latitude - cameraState.centerLat
                val deltaLon = userLocation.longitude - cameraState.centerLon
                val midLatRad = Math.toRadians((cameraState.centerLat + userLocation.latitude) / 2.0)
                val cosLat = kotlin.math.max(0.01, kotlin.math.cos(midLatRad))
                val dy = deltaLat * 111132.95
                val dx = deltaLon * 111320.0 * cosLat
                val userCanvasX = (center.x + dx * layout.pixelsPerMeter).toFloat()
                val userCanvasY = (center.y - dy * layout.pixelsPerMeter).toFloat()
                val uCenter = Offset(userCanvasX, userCanvasY)
                userCenterOffset = uCenter

                // Accuracy Halo
                val userAcc = userLocation.accuracy
                if (userAcc != null && userAcc > 0f) {
                    val userAccPx = (userAcc * layout.pixelsPerMeter).coerceIn(8f, radiusPx)
                    drawCircle(
                        color = TacticalBlue.copy(alpha = 0.20f),
                        radius = userAccPx,
                        center = uCenter
                    )
                    drawCircle(
                        color = TacticalBlue.copy(alpha = 0.60f),
                        radius = userAccPx,
                        center = uCenter,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Heading Cone (wedge pointing in direction of bearing)
                val bearing = userLocation.bearing
                if (bearing != null && bearing >= 0f) {
                    val coneRadiusPx = 36.dp.toPx()
                    val sweepAngle = 45.0
                    val startAngleRad = Math.toRadians((bearing - 90.0 - sweepAngle / 2.0))
                    val endAngleRad = Math.toRadians((bearing - 90.0 + sweepAngle / 2.0))

                    val conePath = Path().apply {
                        moveTo(uCenter.x, uCenter.y)
                        lineTo(
                            (uCenter.x + coneRadiusPx * kotlin.math.cos(startAngleRad)).toFloat(),
                            (uCenter.y + coneRadiusPx * kotlin.math.sin(startAngleRad)).toFloat()
                        )
                        lineTo(
                            (uCenter.x + coneRadiusPx * kotlin.math.cos(endAngleRad)).toFloat(),
                            (uCenter.y + coneRadiusPx * kotlin.math.sin(endAngleRad)).toFloat()
                        )
                        close()
                    }
                    drawPath(
                        path = conePath,
                        color = TacticalBlue.copy(alpha = 0.35f)
                    )
                }

                // User Blue Core
                drawCircle(
                    color = TacticalBlue,
                    radius = 9.dp.toPx(),
                    center = uCenter
                )
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = uCenter
                )

                val userPaint = Paint().apply {
                    color = android.graphics.Color.parseColor("#53B1FD")
                    textSize = 18f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "YOU",
                    uCenter.x,
                    uCenter.y + 18.dp.toPx(),
                    userPaint
                )
            }

            // Dotted connection line to selected target
            if (selectedPoint != null && userCenterOffset != null) {
                val targetEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                drawLine(
                    color = TacticalBlue.copy(alpha = 0.75f),
                    start = userCenterOffset,
                    end = selectedPoint.canvasOffset,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = targetEffect
                )
            }

            // Cluster & Marker Rendering via MarkerClusterEngine
            val clusters = MarkerClusterEngine.cluster(layout.projectedPoints, tapHitRadiusPx)
            clusters.forEach { cluster ->
                if (cluster.isCluster) {
                    // Cluster rendering with outer glow & count
                    val glowColor = when {
                        cluster.hasSos -> StatusBlocked
                        cluster.hasHazard -> StatusHazard
                        else -> TacticalBlue
                    }

                    // Outer halo
                    drawCircle(
                        color = glowColor.copy(alpha = 0.30f),
                        radius = 26.dp.toPx(),
                        center = cluster.screenOffset
                    )
                    // Core surface
                    drawCircle(
                        color = SurfaceDark,
                        radius = 18.dp.toPx(),
                        center = cluster.screenOffset
                    )
                    // Border ring
                    drawCircle(
                        color = glowColor,
                        radius = 18.dp.toPx(),
                        center = cluster.screenOffset,
                        style = Stroke(width = 2.5.dp.toPx())
                    )

                    val countPaint = Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 22f
                        textAlign = Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "${cluster.totalCount}",
                        cluster.screenOffset.x,
                        cluster.screenOffset.y + 8f,
                        countPaint
                    )
                } else {
                    // Single Marker
                    val proj = layout.projectedPoints.find { it.point.id == cluster.representativePoint.id } ?: return@forEach
                    val isSelected = selectedPoint?.point?.id == proj.point.id
                    val pt = proj.point

                    if (proj.isWithinBounds) {
                        if (proj.accuracyRadiusPx > 4f) {
                            drawCircle(
                                color = PrimaryAmber.copy(alpha = 0.1f),
                                radius = proj.accuracyRadiusPx,
                                center = proj.canvasOffset
                            )
                        }

                        val (markerColor, badgeText) = when (pt.type) {
                            "SOS" -> StatusBlocked to "SOS"
                            "HAZARD" -> StatusHazard to "HZ"
                            "ROUTE_STATUS" -> (if (pt.status == "OPEN") StatusClear else StatusBlocked) to (if (pt.status == "OPEN") "OP" else "BL")
                            else -> TacticalBlue to "NOTE"
                        }

                        // SOS High-Visibility Outer Ring
                        if (pt.type == "SOS") {
                            drawCircle(
                                color = StatusBlocked.copy(alpha = 0.35f),
                                radius = 22.dp.toPx(),
                                center = proj.canvasOffset
                            )
                        }

                        // Core Circle
                        drawCircle(
                            color = if (isSelected) Color.White else markerColor,
                            radius = if (isSelected) 18.dp.toPx() else 14.dp.toPx(),
                            center = proj.canvasOffset
                        )
                        drawCircle(
                            color = if (isSelected) PrimaryAmber else SurfaceDark,
                            radius = if (isSelected) 14.dp.toPx() else 11.dp.toPx(),
                            center = proj.canvasOffset
                        )

                        // Center badge label
                        val badgePaint = Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 19f
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            badgeText,
                            proj.canvasOffset.x,
                            proj.canvasOffset.y + 7f,
                            badgePaint
                        )

                        // Title label below marker
                        val labelPaint = Paint().apply {
                            color = android.graphics.Color.parseColor("#C9D1D9")
                            textSize = 18f
                            textAlign = Paint.Align.CENTER
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            pt.label.take(12),
                            proj.canvasOffset.x,
                            proj.canvasOffset.y + 24.dp.toPx(),
                            labelPaint
                        )
                    } else {
                        // Out-of-bounds marker
                        val (tagColor, tagPrefix) = when (pt.type) {
                            "SOS" -> StatusBlocked to "SOS"
                            "HAZARD" -> StatusHazard to "HZ"
                            else -> TextTertiary to "PT"
                        }

                        drawCircle(
                            color = tagColor,
                            radius = 8.dp.toPx(),
                            center = proj.canvasOffset
                        )

                        val distStr = if (proj.distanceMeters >= 1000f) {
                            "${String.format(java.util.Locale.US, "%.1f", proj.distanceMeters / 1000f)}km"
                        } else {
                            "${proj.distanceMeters.toInt()}m"
                        }

                        val outPaint = Paint().apply {
                            color = android.graphics.Color.parseColor("#E6EDF3")
                            textSize = 17f
                            textAlign = Paint.Align.CENTER
                            isFakeBoldText = true
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            "$tagPrefix $distStr",
                            proj.canvasOffset.x,
                            proj.canvasOffset.y - 12.dp.toPx(),
                            outPaint
                        )
                    }
                }
            }
        }

        // Top Tactical Overlays: Camera Modes & Safety Disclaimer
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            // Camera Mode Selector Chips (Touch targets >= 48dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraModeChip(
                    title = "Follow me",
                    isSelected = cameraState.mode == CameraMode.FOLLOW_ME,
                    onClick = { cameraState = cameraStateMachine.onSelectMode(CameraMode.FOLLOW_ME, allGeoPoints) },
                    modifier = Modifier.weight(1f)
                )
                CameraModeChip(
                    title = "Fit all",
                    isSelected = cameraState.mode == CameraMode.FIT_ALL,
                    onClick = { cameraState = cameraStateMachine.onSelectMode(CameraMode.FIT_ALL, allGeoPoints) },
                    modifier = Modifier.weight(1f)
                )
                CameraModeChip(
                    title = "Target",
                    isSelected = cameraState.mode == CameraMode.FIT_ME_TARGET,
                    onClick = { cameraState = cameraStateMachine.onSelectMode(CameraMode.FIT_ME_TARGET, allGeoPoints) },
                    modifier = Modifier.weight(1f)
                )
                CameraModeChip(
                    title = "Manual",
                    isSelected = cameraState.mode == CameraMode.MANUAL,
                    onClick = { cameraState = cameraStateMachine.onSelectMode(CameraMode.MANUAL, allGeoPoints) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Invariant 4 Safety Disclaimer Banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                color = SurfaceDark.copy(alpha = 0.94f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, SurfaceBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = PrimaryAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = AutoFitViewportCalculator.SAFETY_DISCLAIMER,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // Floating Controls on Right Side (Zoom + Recenter)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Recenter Button (visible when decoupled or manual)
            if (cameraState.isDecoupled || cameraState.mode == CameraMode.MANUAL) {
                FloatingActionButton(
                    onClick = { cameraState = cameraStateMachine.onRecenter(allGeoPoints) },
                    modifier = Modifier.size(48.dp),
                    containerColor = TacticalBlue,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Recenter Map", modifier = Modifier.size(22.dp))
                }
            }

            // Zoom In
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        cameraState = cameraStateMachine.onUserGesturePanOrZoom(
                            newRadiusMeters = (cameraState.radiusMeters * 0.7f).coerceAtLeast(AutoFitViewportCalculator.MIN_RADIUS_METERS)
                        )
                    },
                shape = CircleShape,
                color = SurfaceDark.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, SurfaceBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom In", tint = TextPrimary)
                }
            }

            // Zoom Out
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        cameraState = cameraStateMachine.onUserGesturePanOrZoom(
                            newRadiusMeters = (cameraState.radiusMeters * 1.4f).coerceAtMost(AutoFitViewportCalculator.MAX_RADIUS_METERS)
                        )
                    },
                shape = CircleShape,
                color = SurfaceDark.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, SurfaceBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom Out", tint = TextPrimary)
                }
            }
        }

        // Observation Detail Inspection Card & Bottom Tactical Metric Bar (Pinned at bottom on selection)
        selectedPoint?.let { proj ->
            val obs = proj.point.rawObservation as? ObservationEntity
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.98f)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (proj.point.type == "SOS") StatusBlocked else SurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = if (proj.point.type == "SOS") StatusBlocked else if (proj.point.type == "HAZARD") StatusHazard else TacticalBlue,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = proj.point.type,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = proj.point.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = onDismissSelection,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3-Column Tactical Metric Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Distance", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                            val distStr = if (proj.distanceMeters >= 1000f) {
                                String.format(java.util.Locale.US, "%.1f km", proj.distanceMeters / 1000f)
                            } else {
                                "${proj.distanceMeters.toInt()} m"
                            }
                            Text(distStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Bearing", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                            val cardinal = getCardinalDirection(proj.bearingDegrees)
                            Text("$cardinal (${proj.bearingDegrees.toInt()}°)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Target Fix", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                            val acc = proj.point.accuracy
                            val accStr = if (acc != null && acc > 0f) "±${acc.toInt()}m" else "Estimated"
                            Text(accStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }

                    if (!proj.point.status.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Reported Status: ${proj.point.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (proj.point.status == "OPEN") StatusClear else StatusBlocked,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    obs?.let { o ->
                        if (o.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = o.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 2
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tactical Action Buttons (Minimum 48dp touch target)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isCurrentTarget = cameraState.target?.id == proj.point.id
                        OutlinedButton(
                            onClick = {
                                if (isCurrentTarget) {
                                    cameraState = cameraStateMachine.onClearTarget(allGeoPoints)
                                } else {
                                    cameraState = cameraStateMachine.onSelectTarget(
                                        CameraTarget(
                                            id = proj.point.id,
                                            latitude = proj.point.latitude,
                                            longitude = proj.point.longitude,
                                            label = proj.point.label
                                        )
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isCurrentTarget) PrimaryAmber else SurfaceBorder)
                        ) {
                            Icon(
                                if (isCurrentTarget) Icons.Filled.Close else Icons.Filled.Adjust,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isCurrentTarget) PrimaryAmber else TextSecondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isCurrentTarget) "Clear" else "Target",
                                color = if (isCurrentTarget) PrimaryAmber else TextPrimary
                            )
                        }

                        if (onNavigateToChat != null) {
                            Button(
                                onClick = { onNavigateToChat(proj.point.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TacticalBlue)
                            ) {
                                Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Chat")
                            }
                        }

                        Button(
                            onClick = { onNavigateToObservationDetail?.invoke(proj.point.id) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryAmber,
                                contentColor = BackgroundDark
                            )
                        ) {
                            Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Details")
                        }
                    }
                }
            }
        }

        // Cluster Inspection Dialog
        selectedCluster?.let { cluster ->
            AlertDialog(
                onDismissRequest = { selectedCluster = null },
                title = {
                    Text(
                        "Incident Cluster (${cluster.totalCount} Reports)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(cluster.points) { pt ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val layout = currentLayout
                                        if (layout != null) {
                                            val proj = layout.projectedPoints.find { it.point.id == pt.id }
                                            if (proj != null) {
                                                onPointSelected(proj)
                                            }
                                        }
                                        selectedCluster = null
                                    },
                                color = SurfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (pt.type == "SOS") StatusBlocked else SurfaceBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = if (pt.type == "SOS") StatusBlocked else if (pt.type == "HAZARD") StatusHazard else TacticalBlue,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = pt.type,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pt.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        if (!pt.status.isNullOrBlank()) {
                                            Text(
                                                text = "Status: ${pt.status}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = TextTertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { selectedCluster = null },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Close", color = PrimaryAmber)
                    }
                },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun CameraModeChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) TacticalBlue else SurfaceDark.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, if (isSelected) TacticalBlue else SurfaceBorder)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else TextSecondary
            )
        }
    }
}

private fun getCardinalDirection(degrees: Float): String {
    val norm = ((degrees % 360f) + 360f) % 360f
    return when {
        norm >= 337.5f || norm < 22.5f -> "N"
        norm < 67.5f -> "NE"
        norm < 112.5f -> "E"
        norm < 157.5f -> "SE"
        norm < 202.5f -> "S"
        norm < 247.5f -> "SW"
        norm < 292.5f -> "W"
        else -> "NW"
    }
}

/**
 * Tier 1: Surveyed graph with Dijkstra routing.
 */
@Composable
private fun SurveyedGraphView(
    nodes: List<MapNodeEntity>,
    edges: List<MapEdgeEntity>,
    selectedFromNode: String?,
    selectedToNode: String?,
    routeResult: RoutePlanner.RouteResult?,
    padHorizontalPx: Float,
    padTopPx: Float,
    padBottomPx: Float,
    hitRadiusPx: Float,
    onNodeTapped: (String) -> Unit,
    onClearRoute: () -> Unit,
    onFindRouteClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
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
                            onNodeTapped(hitNode.nodeId)
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
                    style = Stroke(width = 2.dp.toPx())
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

                val paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f
                    textAlign = Paint.Align.CENTER
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
                    val labelPaint = Paint().apply {
                        color = android.graphics.Color.parseColor("#8B949E")
                        textSize = 20f
                        textAlign = Paint.Align.CENTER
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

        // Route Calculation & Status Panel
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
                                IconButton(onClick = onClearRoute) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = "Reset Selection",
                                        tint = TextTertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Button(
                                onClick = onFindRouteClick,
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

/**
 * Tier 3: Tactical Standby View displaying unlocated synchronized observations
 * when no coordinates are available from GNSS or mesh peers.
 */
@Composable
private fun UnlocatedStandbyFeedView(
    observations: List<ObservationEntity>,
    onObservationClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TacticalEmptyMapCard()
        }

        if (observations.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = PrimaryAmber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Synchronized Unlocated Feed (${observations.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            items(observations, key = { it.eventId }) { obs ->
                ObservationFeedCard(
                    obs = obs,
                    onClick = onObservationClick?.let { { it(obs.eventId) } }
                )
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
                text = "GPS Fix Pending • Standby",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Topological map will render automatically when GNSS position is locked or surveyed waypoints are received from field units.",
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
                        text = "Awaiting GNSS or Mesh Graph Data",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
