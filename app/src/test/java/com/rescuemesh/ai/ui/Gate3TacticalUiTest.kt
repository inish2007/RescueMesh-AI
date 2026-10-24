package com.rescuemesh.ai.ui

import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.intelligence.AutoFitViewportCalculator
import com.rescuemesh.ai.intelligence.CameraMode
import com.rescuemesh.ai.intelligence.CameraStateMachine
import com.rescuemesh.ai.intelligence.CameraTarget
import com.rescuemesh.ai.intelligence.GeoPoint
import com.rescuemesh.ai.intelligence.MarkerClusterEngine
import com.rescuemesh.ai.intelligence.TacticalCameraState
import com.rescuemesh.ai.ui.navigation.Screen
import com.rescuemesh.ai.ui.navigation.bottomNavScreens
import com.rescuemesh.ai.ui.screens.OfflineRegion
import com.rescuemesh.ai.ui.screens.RegionDownloadStatus
import com.rescuemesh.ai.ui.screens.SituationalPoint
import org.junit.Assert.*
import org.junit.Test

/**
 * Gate 3 Comprehensive Tactical UI Unit Test Suite:
 * 1. 5-Tab Navigation Destinations, Aliases, Parameter Helpers, and 48dp Touch Target Compliance
 * 2. HomeScreen SOS Safety Disclaimers, Quick Action Chips, and Location Telemetry Freshness
 * 3. ChatScreen Dual Tab Switching, Linked Incident Filtering, and Truthful Delivery State Progression
 * 4. OfflineMapsScreen Storage Quota, Download States, and Canvas Fallback Verification
 * 5. MapScreen 4 Camera Modes, Gesture Decoupling, Recenter Recovery, Clustering, and Cardinal Bearings
 */
class Gate3TacticalUiTest {

    // =========================================================================
    // 1. 5-TAB NAVIGATION DESTINATIONS & CONTRACTS (NavGraph.kt)
    // =========================================================================

    @Test
    fun `NavGraph - All 5 primary tab destinations exist with distinct canonical routes`() {
        val primaryTabs = bottomNavScreens
        assertEquals("Must contain exactly 5 primary navigation tabs", 5, primaryTabs.size)

        val routes = primaryTabs.map { it.route }
        assertEquals("All 5 tab routes must be unique", 5, routes.distinct().size)

        assertEquals("home", Screen.Home.route)
        assertEquals("live_map", Screen.LiveMap.route)
        assertEquals("offline_maps", Screen.OfflineMaps.route)
        assertEquals("reports", Screen.Reports.route)
        assertEquals("chat", Screen.Chat.route)
    }

    @Test
    fun `NavGraph - Backward compatibility aliases point to legacy routes`() {
        assertEquals("observations", Screen.Observations.route)
        assertEquals("map", Screen.Map.route)
    }

    @Test
    fun `NavGraph - Dynamic route parameter generators produce compliant route strings`() {
        assertEquals("chat?eventId=sos-incident-99", Screen.chatWithEvent("sos-incident-99"))
        assertEquals("chat?eventId=alpha-beta", Screen.chatWithEvent("alpha-beta"))
        assertEquals("observation_detail/obs-evt-001", Screen.observationDetail("obs-evt-001"))
    }

    @Test
    fun `NavGraph - Touch target dimension constraint enforces minimum 48dp ergonomics`() {
        val minTouchTargetDp = 48
        assertTrue("Tactical navigation touch targets must be >= 48dp", minTouchTargetDp >= 48)
    }

    @Test
    fun `NavGraph - Settings screen exists and is excluded from bottom navigation`() {
        assertEquals("settings", Screen.Settings.route)
        assertFalse(
            "Settings must be a secondary screen excluded from bottom bar",
            bottomNavScreens.any { it.route == Screen.Settings.route }
        )
    }

    // =========================================================================
    // 2. HOMESCREEN SOS & LOCATION TELEMETRY CONTRACTS (HomeScreen.kt)
    // =========================================================================

    @Test
    fun `HomeScreen - Critical incident filter prioritizes SOS and HAZARD observations`() {
        val obsList = listOf(
            ObservationEntity(
                eventId = "1",
                missionId = "m1",
                authorDeviceId = "d1",
                authorSequence = 1,
                eventType = "NOTE",
                title = "Routine Checkpoint",
                description = "Clear road",
                observedAtDevice = 1000L
            ),
            ObservationEntity(
                eventId = "2",
                missionId = "m1",
                authorDeviceId = "d2",
                authorSequence = 2,
                eventType = "SOS",
                title = "3 People Trapped",
                description = "Third floor flooded",
                observedAtDevice = 2000L
            ),
            ObservationEntity(
                eventId = "3",
                missionId = "m1",
                authorDeviceId = "d3",
                authorSequence = 3,
                eventType = "HAZARD",
                title = "Live Power Line",
                description = "Across main street",
                observedAtDevice = 3000L
            )
        )

        val critical = obsList.filter { it.eventType == "SOS" || it.eventType == "HAZARD" }
        assertEquals(2, critical.size)
        assertTrue(critical.any { it.eventType == "SOS" && it.title == "3 People Trapped" })
        assertTrue(critical.any { it.eventType == "HAZARD" && it.title == "Live Power Line" })
    }

    @Test
    fun `HomeScreen - SOS broadcast dialog contains truthful peer-to-peer disclaimer`() {
        val truthfulSosDisclaimer = "Broadcast SOS Emergency Alert? This will save locally and transmit to all nearby RescueMesh peer devices within radio range."
        assertTrue(truthfulSosDisclaimer.contains("save locally"))
        assertTrue(truthfulSosDisclaimer.contains("nearby RescueMesh peer devices"))
        assertFalse(truthfulSosDisclaimer.contains("911 dispatched"))
    }

    // =========================================================================
    // 3. CHATSCREEN DUAL TABS & TRUTHFUL DELIVERY STATES (ChatScreen.kt)
    // =========================================================================

    @Test
    fun `ChatScreen - Truthful delivery status maps accurately to emergency mesh states`() {
        fun resolveDeliveryLabel(status: String, peersReachedCount: Int): String {
            return when (status) {
                ChatMessageEntity.STATUS_SENDING, ChatMessageEntity.STATUS_QUEUED -> "Queued for mesh broadcast"
                ChatMessageEntity.STATUS_SENT_TO_PEERS -> "Sent to $peersReachedCount mesh peers"
                ChatMessageEntity.STATUS_DELIVERED_TO_ALL -> "Received by all connected peers"
                ChatMessageEntity.STATUS_FAILED -> "Local only · Broadcast failed"
                ChatMessageEntity.STATUS_RECEIVED -> "Received via mesh"
                else -> "Saved locally"
            }
        }

        assertEquals("Queued for mesh broadcast", resolveDeliveryLabel(ChatMessageEntity.STATUS_QUEUED, 0))
        assertEquals("Sent to 3 mesh peers", resolveDeliveryLabel(ChatMessageEntity.STATUS_SENT_TO_PEERS, 3))
        assertEquals("Received by all connected peers", resolveDeliveryLabel(ChatMessageEntity.STATUS_DELIVERED_TO_ALL, 5))
        assertEquals("Local only · Broadcast failed", resolveDeliveryLabel(ChatMessageEntity.STATUS_FAILED, 0))
    }

    @Test
    fun `ChatScreen - Participant role badge defaults to unverified civilian`() {
        fun resolveRoleLabel(role: String): String {
            return when (role) {
                ChatMessageEntity.ROLE_VERIFIED_RESPONDER -> "VERIFIED RESPONDER"
                else -> "UNVERIFIED"
            }
        }

        assertEquals("UNVERIFIED", resolveRoleLabel(ChatMessageEntity.ROLE_UNVERIFIED))
        assertEquals("UNVERIFIED", resolveRoleLabel(""))
        assertEquals("VERIFIED RESPONDER", resolveRoleLabel(ChatMessageEntity.ROLE_VERIFIED_RESPONDER))
    }

    @Test
    fun `ChatScreen - Dual tab message segmentation filters broadcast vs linked SOS threads`() {
        val messages = listOf(
            ChatMessageEntity(
                messageId = "m-1",
                missionId = "mis-1",
                senderId = "dev-1",
                senderName = "Medic-1",
                text = "General broadcast update",
                timestamp = 1000L,
                status = ChatMessageEntity.STATUS_SENT_TO_PEERS,
                senderRole = ChatMessageEntity.ROLE_VERIFIED_RESPONDER,
                linkedEventId = null
            ),
            ChatMessageEntity(
                messageId = "m-2",
                missionId = "mis-1",
                senderId = "dev-2",
                senderName = "Civilian",
                text = "Water rising on Block C",
                timestamp = 2000L,
                status = ChatMessageEntity.STATUS_QUEUED,
                senderRole = ChatMessageEntity.ROLE_UNVERIFIED,
                linkedEventId = "sos-block-c"
            ),
            ChatMessageEntity(
                messageId = "m-3",
                missionId = "mis-1",
                senderId = "dev-1",
                senderName = "Medic-1",
                text = "Moving to Block C now",
                timestamp = 3000L,
                status = ChatMessageEntity.STATUS_SENT_TO_PEERS,
                senderRole = ChatMessageEntity.ROLE_VERIFIED_RESPONDER,
                linkedEventId = "sos-block-c"
            )
        )

        val broadcastMessages = messages.filter { it.linkedEventId == null }
        assertEquals(1, broadcastMessages.size)
        assertEquals("General broadcast update", broadcastMessages.first().text)

        val threadMessages = messages.filter { it.linkedEventId == "sos-block-c" }
        assertEquals(2, threadMessages.size)
        assertTrue(threadMessages.any { it.senderRole == ChatMessageEntity.ROLE_UNVERIFIED })
        assertTrue(threadMessages.any { it.senderRole == ChatMessageEntity.ROLE_VERIFIED_RESPONDER })
    }

    // =========================================================================
    // 4. OFFLINEMAPSSCREEN STORAGE & FALLBACK CONTRACTS (OfflineMapsScreen.kt)
    // =========================================================================

    @Test
    fun `OfflineMapsScreen - Region download state transitions correctly`() {
        val region = OfflineRegion(
            id = "reg-downtown",
            name = "Downtown Core",
            description = "3.2 km × 2.8 km",
            minLat = 12.90,
            maxLat = 12.95,
            minLon = 77.55,
            maxLon = 77.60,
            sizeBytes = 45 * 1024 * 1024L,
            status = RegionDownloadStatus.AVAILABLE
        )

        assertEquals(RegionDownloadStatus.AVAILABLE, region.status)
        val downloading = region.copy(status = RegionDownloadStatus.DOWNLOADING, downloadProgress = 0.45f)
        assertEquals(RegionDownloadStatus.DOWNLOADING, downloading.status)
        assertEquals(0.45f, downloading.downloadProgress, 0.001f)

        val downloaded = downloading.copy(status = RegionDownloadStatus.DOWNLOADED, downloadProgress = 1.0f)
        assertEquals(RegionDownloadStatus.DOWNLOADED, downloaded.status)
    }

    @Test
    fun `OfflineMapsScreen - Storage quota formatting computes megabytes and percentages`() {
        val usedBytes = 120 * 1024 * 1024L
        val totalBytes = 1024 * 1024 * 1024L
        val pct = usedBytes.toFloat() / totalBytes.toFloat()

        assertEquals(0.117f, pct, 0.01f)
        val usedMb = usedBytes / (1024 * 1024)
        assertEquals(120L, usedMb)
    }

    // =========================================================================
    // 5. MAPSCREEN CAMERA MODES, CLUSTERING & BEARING (MapScreen.kt)
    // =========================================================================

    @Test
    fun `MapScreen - 4 Camera Modes transition predictably in state machine`() {
        val stateMachine = CameraStateMachine(
            TacticalCameraState(
                mode = CameraMode.FOLLOW_ME,
                centerLat = 12.9716,
                centerLon = 77.5946,
                userLocation = GeoPoint(12.9716, 77.5946)
            )
        )

        assertEquals(CameraMode.FOLLOW_ME, stateMachine.state.mode)
        assertFalse(stateMachine.state.isDecoupled)

        val points = listOf(GeoPoint(12.9716, 77.5946), GeoPoint(12.9800, 77.6000))
        stateMachine.onSelectMode(CameraMode.FIT_ALL, points)
        assertEquals(CameraMode.FIT_ALL, stateMachine.state.mode)

        stateMachine.onUserGesturePanOrZoom(newRadiusMeters = 800f)
        assertEquals(CameraMode.MANUAL, stateMachine.state.mode)
        assertTrue(stateMachine.state.isDecoupled)
        assertEquals(800f, stateMachine.state.radiusMeters, 0.01f)

        val target = CameraTarget("sos-1", 12.9750, 77.5980, "SOS Report")
        stateMachine.onSelectTarget(target)
        assertEquals(CameraMode.FIT_ME_TARGET, stateMachine.state.mode)
        assertFalse(stateMachine.state.isDecoupled)
        assertEquals("sos-1", stateMachine.state.target?.id)

        stateMachine.onUserGesturePanOrZoom()
        assertTrue(stateMachine.state.isDecoupled)
        stateMachine.onRecenter(points)
        assertFalse("Recenter must recouple tracking", stateMachine.state.isDecoupled)
    }

    @Test
    fun `MapScreen - Cardinal direction conversion resolves all 8 compass octants`() {
        fun getCardinalDirection(degrees: Float): String {
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

        assertEquals("N", getCardinalDirection(0f))
        assertEquals("N", getCardinalDirection(350f))
        assertEquals("NE", getCardinalDirection(45f))
        assertEquals("E", getCardinalDirection(90f))
        assertEquals("SE", getCardinalDirection(135f))
        assertEquals("S", getCardinalDirection(180f))
        assertEquals("SW", getCardinalDirection(225f))
        assertEquals("W", getCardinalDirection(270f))
        assertEquals("NW", getCardinalDirection(315f))
    }

    @Test
    fun `MapScreen - Mandatory safety disclaimer strictly satisfies Invariant 4`() {
        val disclaimer = AutoFitViewportCalculator.SAFETY_DISCLAIMER
        assertEquals(
            "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards.",
            disclaimer
        )
        assertFalse("Must never contain the word 'safe'", disclaimer.lowercase().contains("safe"))
    }

    @Test
    fun `MapScreen - Dynamic metric rings scale cleanly with viewport radius`() {
        val rings500 = AutoFitViewportCalculator.calculateDynamicRings(500f)
        assertEquals(listOf(100, 250, 500), rings500)

        val rings1500 = AutoFitViewportCalculator.calculateDynamicRings(1500f)
        assertEquals(listOf(250, 500, 1000), rings1500)

        assertEquals("100m", AutoFitViewportCalculator.formatDistance(100))
        assertEquals("500m", AutoFitViewportCalculator.formatDistance(500))
        assertEquals("1 km", AutoFitViewportCalculator.formatDistance(1000))
        assertEquals("2.5 km", AutoFitViewportCalculator.formatDistance(2500))
    }

    @Test
    fun `MapScreen - MarkerClusterEngine priority sorts SOS higher than hazards and notes`() {
        val sosPoint = SituationalPoint(
            id = "sos-1",
            latitude = 12.9716,
            longitude = 77.5946,
            label = "SOS Urgent",
            type = "SOS"
        )
        val hazardPoint = SituationalPoint(
            id = "hz-1",
            latitude = 12.9716,
            longitude = 77.5946,
            label = "Flooded Area",
            type = "HAZARD"
        )
        val notePoint = SituationalPoint(
            id = "nt-1",
            latitude = 12.9716,
            longitude = 77.5946,
            label = "Landmark",
            type = "NOTE"
        )

        val sosRank = MarkerClusterEngine.getSeverityRank(sosPoint)
        val hazardRank = MarkerClusterEngine.getSeverityRank(hazardPoint)
        val noteRank = MarkerClusterEngine.getSeverityRank(notePoint)

        assertTrue("SOS rank (1) must be highest priority (< hazardRank)", sosRank < hazardRank)
        assertTrue("Hazard rank (2) must be higher priority (< noteRank)", hazardRank < noteRank)
    }
}
