package com.rescuemesh.ai.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeTest {

    private val delta = 1e-4

    @Test
    fun initialStateDefaultsToFitAll() {
        val stateMachine = CameraStateMachine()
        val state = stateMachine.state

        assertEquals(CameraMode.FIT_ALL, state.mode)
        assertFalse(state.isDecoupled)
        assertNull(state.target)
        assertNull(state.userLocation)
    }

    @Test
    fun followMeModeUpdatesCenterWhenUserGpsFixUpdates() {
        val stateMachine = CameraStateMachine()
        stateMachine.onSelectMode(CameraMode.FOLLOW_ME)

        assertEquals(CameraMode.FOLLOW_ME, stateMachine.state.mode)

        // Incoming GPS fix
        val state = stateMachine.onUserLocationUpdated(12.9716, 77.5946)

        assertEquals(12.9716, state.centerLat, delta)
        assertEquals(77.5946, state.centerLon, delta)
        assertFalse(state.isDecoupled)
    }

    @Test
    fun fitAllModeUpdatesBoundsWhenPointsOrLocationChange() {
        val stateMachine = CameraStateMachine()
        val p1 = GeoPoint(12.0, 77.0)
        val p2 = GeoPoint(12.02, 77.02)

        stateMachine.onPointsUpdated(listOf(p1, p2))

        val state = stateMachine.state
        assertEquals(CameraMode.FIT_ALL, state.mode)
        assertEquals(12.01, state.centerLat, delta)
        assertEquals(77.01, state.centerLon, delta)
        assertTrue(state.radiusMeters >= AutoFitViewportCalculator.MIN_RADIUS_METERS)
    }

    @Test
    fun fitMeTargetFramesBothUserLocationAndTarget() {
        val stateMachine = CameraStateMachine()
        // Provide user location first
        stateMachine.onUserLocationUpdated(12.0, 77.0)

        val target = CameraTarget(
            id = "sos_target",
            latitude = 12.02,
            longitude = 77.02,
            label = "Emergency SOS #1"
        )
        val state = stateMachine.onSelectTarget(target)

        assertEquals(CameraMode.FIT_ME_TARGET, state.mode)
        assertNotNull(state.target)
        assertEquals("sos_target", state.target?.id)
        // Center should be midpoint between user (12.0, 77.0) and target (12.02, 77.02)
        assertEquals(12.01, state.centerLat, delta)
        assertEquals(77.01, state.centerLon, delta)
    }

    @Test
    fun userPanGestureTransitionsCameraModeToManual() {
        val stateMachine = CameraStateMachine()
        stateMachine.onSelectMode(CameraMode.FOLLOW_ME)
        stateMachine.onUserLocationUpdated(12.0, 77.0)

        assertEquals(CameraMode.FOLLOW_ME, stateMachine.state.mode)

        // User pans the map
        val manualState = stateMachine.onUserGesturePanOrZoom(
            newCenterLat = 13.0,
            newCenterLon = 78.0,
            newRadiusMeters = 800f
        )

        assertEquals(CameraMode.MANUAL, manualState.mode)
        assertTrue(manualState.isDecoupled)
        assertEquals(13.0, manualState.centerLat, delta)
        assertEquals(78.0, manualState.centerLon, delta)
        assertEquals(800f, manualState.radiusMeters, 0.1f)
    }

    @Test
    fun incomingGpsFixInManualModeDoesNotAlterCameraCenter() {
        val stateMachine = CameraStateMachine()
        stateMachine.onSelectMode(CameraMode.FOLLOW_ME)
        stateMachine.onUserLocationUpdated(12.0, 77.0)

        // User pans to new center
        stateMachine.onUserGesturePanOrZoom(
            newCenterLat = 15.0,
            newCenterLon = 75.0
        )

        assertEquals(CameraMode.MANUAL, stateMachine.state.mode)
        assertEquals(15.0, stateMachine.state.centerLat, delta)
        assertEquals(75.0, stateMachine.state.centerLon, delta)

        // New GPS fix arrives from physical movement
        val updatedState = stateMachine.onUserLocationUpdated(12.001, 77.001)

        // Mode must remain MANUAL
        assertEquals(CameraMode.MANUAL, updatedState.mode)
        assertTrue(updatedState.isDecoupled)
        // Camera center must NOT be snapped or overridden!
        assertEquals(15.0, updatedState.centerLat, delta)
        assertEquals(75.0, updatedState.centerLon, delta)
        // But userLocation data must be updated
        assertEquals(12.001, updatedState.userLocation?.latitude ?: 0.0, delta)
        assertEquals(77.001, updatedState.userLocation?.longitude ?: 0.0, delta)
    }

    @Test
    fun recenterActionRestoresFollowMeWhenUserLocationExists() {
        val stateMachine = CameraStateMachine()
        stateMachine.onUserLocationUpdated(12.0, 77.0)
        stateMachine.onUserGesturePanOrZoom(newCenterLat = 15.0, newCenterLon = 75.0)

        assertEquals(CameraMode.MANUAL, stateMachine.state.mode)

        // Recenter action tapped
        val restoredState = stateMachine.onRecenter()

        assertEquals(CameraMode.FOLLOW_ME, restoredState.mode)
        assertFalse(restoredState.isDecoupled)
        assertEquals(12.0, restoredState.centerLat, delta)
        assertEquals(77.0, restoredState.centerLon, delta)
    }

    @Test
    fun recenterActionRestoresFitAllWhenNoUserLocationExists() {
        val stateMachine = CameraStateMachine()
        val points = listOf(GeoPoint(12.0, 77.0), GeoPoint(12.02, 77.02))
        stateMachine.onUserGesturePanOrZoom(newCenterLat = 15.0, newCenterLon = 75.0)

        assertEquals(CameraMode.MANUAL, stateMachine.state.mode)

        // Recenter with no user GPS fix
        val restoredState = stateMachine.onRecenter(allPoints = points)

        assertEquals(CameraMode.FIT_ALL, restoredState.mode)
        assertFalse(restoredState.isDecoupled)
        assertEquals(12.01, restoredState.centerLat, delta)
        assertEquals(77.01, restoredState.centerLon, delta)
    }

    @Test
    fun recenterActionRestoresFitMeTargetWhenTargetIsActive() {
        val stateMachine = CameraStateMachine()
        stateMachine.onUserLocationUpdated(12.0, 77.0)
        val target = CameraTarget("sos_1", 12.04, 77.04)
        stateMachine.onSelectTarget(target)

        // User pans away to inspect surroundings
        stateMachine.onUserGesturePanOrZoom(newCenterLat = 14.0, newCenterLon = 74.0)
        assertEquals(CameraMode.MANUAL, stateMachine.state.mode)

        // Recenter action
        val restored = stateMachine.onRecenter()

        assertEquals(CameraMode.FIT_ME_TARGET, restored.mode)
        assertFalse(restored.isDecoupled)
        assertEquals(12.02, restored.centerLat, delta)
        assertEquals(77.02, restored.centerLon, delta)
    }

    @Test
    fun clearingTargetRestoresFollowMeWhenUserLocationExists() {
        val stateMachine = CameraStateMachine()
        stateMachine.onUserLocationUpdated(12.0, 77.0)
        val target = CameraTarget("sos_1", 12.04, 77.04)
        stateMachine.onSelectTarget(target)

        assertEquals(CameraMode.FIT_ME_TARGET, stateMachine.state.mode)

        val cleared = stateMachine.onClearTarget()

        assertEquals(CameraMode.FOLLOW_ME, cleared.mode)
        assertNull(cleared.target)
        assertEquals(12.0, cleared.centerLat, delta)
        assertEquals(77.0, cleared.centerLon, delta)
    }
}
