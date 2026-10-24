package com.rescuemesh.ai.intelligence

/**
 * 4 Tactical Situational Camera Modes.
 *
 * Implements Gate 3 Requirement R2:
 * 1. [FOLLOW_ME]: Continuously center camera on live user GPS fix.
 * 2. [FIT_ALL]: Auto-fit viewport to enclose all active incident markers and peer beacons.
 * 3. [FIT_ME_TARGET]: Frame current user position and selected emergency SOS or tactical target.
 * 4. [MANUAL]: Allow responder pan and pinch-zoom without being overridden by incoming GPS fixes.
 */
enum class CameraMode {
    FOLLOW_ME,
    FIT_ALL,
    FIT_ME_TARGET,
    MANUAL
}

/**
 * Tactical target for [CameraMode.FIT_ME_TARGET] tracking.
 */
data class CameraTarget(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String = ""
)

/**
 * Immutable tactical camera state snapshot.
 */
data class TacticalCameraState(
    val mode: CameraMode = CameraMode.FIT_ALL,
    val centerLat: Double = 0.0,
    val centerLon: Double = 0.0,
    val radiusMeters: Float = AutoFitViewportCalculator.DEFAULT_RADIUS_METERS,
    val target: CameraTarget? = null,
    val userLocation: GeoPoint? = null,
    val isDecoupled: Boolean = false // True whenever user is interacting manually
)

/**
 * Deterministic State Machine for Situational Map Camera Navigation.
 *
 * Handles gesture decoupling (pan/zoom -> MANUAL), target framing,
 * GPS fix arrivals, and one-tap recentering.
 */
class CameraStateMachine(
    initialState: TacticalCameraState = TacticalCameraState()
) {
    private var _state: TacticalCameraState = initialState

    val state: TacticalCameraState
        get() = _state

    /**
     * User initiated a manual drag, pan, or pinch-zoom gesture.
     * Decouples camera from automated tracking and transitions to [CameraMode.MANUAL].
     */
    fun onUserGesturePanOrZoom(
        newCenterLat: Double? = null,
        newCenterLon: Double? = null,
        newRadiusMeters: Float? = null
    ): TacticalCameraState {
        _state = _state.copy(
            mode = CameraMode.MANUAL,
            centerLat = newCenterLat ?: _state.centerLat,
            centerLon = newCenterLon ?: _state.centerLon,
            radiusMeters = (newRadiusMeters ?: _state.radiusMeters).coerceIn(
                AutoFitViewportCalculator.MIN_RADIUS_METERS,
                AutoFitViewportCalculator.MAX_RADIUS_METERS
            ),
            isDecoupled = true
        )
        return _state
    }

    /**
     * User tapped the "Recenter / Resume Auto-Fit" tactical action.
     * Restores automated tracking:
     * - If target is active: restores [CameraMode.FIT_ME_TARGET]
     * - Else if user GPS is available: restores [CameraMode.FOLLOW_ME]
     * - Otherwise: restores [CameraMode.FIT_ALL]
     */
    fun onRecenter(allPoints: List<GeoPoint> = emptyList()): TacticalCameraState {
        val target = _state.target
        val userLoc = _state.userLocation

        val nextMode = when {
            target != null && userLoc != null -> CameraMode.FIT_ME_TARGET
            userLoc != null -> CameraMode.FOLLOW_ME
            else -> CameraMode.FIT_ALL
        }

        return updateMode(nextMode, allPoints)
    }

    /**
     * User explicitly selected a camera mode chip (e.g. FOLLOW_ME or FIT_ALL).
     */
    fun onSelectMode(mode: CameraMode, allPoints: List<GeoPoint> = emptyList()): TacticalCameraState {
        return updateMode(mode, allPoints)
    }

    /**
     * User selected an SOS incident or tactical target on the map or chat.
     * Automatically transitions to [CameraMode.FIT_ME_TARGET].
     */
    fun onSelectTarget(target: CameraTarget): TacticalCameraState {
        _state = _state.copy(
            target = target,
            mode = CameraMode.FIT_ME_TARGET,
            isDecoupled = false
        )
        recalculateTargetBounds()
        return _state
    }

    /**
     * Clears the active tactical target.
     * Transitions to [CameraMode.FOLLOW_ME] if user location exists, else [CameraMode.FIT_ALL].
     */
    fun onClearTarget(allPoints: List<GeoPoint> = emptyList()): TacticalCameraState {
        val nextMode = if (_state.userLocation != null) CameraMode.FOLLOW_ME else CameraMode.FIT_ALL
        _state = _state.copy(target = null)
        return updateMode(nextMode, allPoints)
    }

    /**
     * Incoming live GPS fix for the device user.
     * In [CameraMode.MANUAL], updates stored user location without moving camera.
     * In other modes, updates camera center and bounding radius accordingly.
     */
    fun onUserLocationUpdated(
        latitude: Double,
        longitude: Double,
        allPoints: List<GeoPoint> = emptyList()
    ): TacticalCameraState {
        val newLoc = GeoPoint(latitude, longitude)
        _state = _state.copy(userLocation = newLoc)

        when (_state.mode) {
            CameraMode.FOLLOW_ME -> {
                _state = _state.copy(
                    centerLat = latitude,
                    centerLon = longitude,
                    isDecoupled = false
                )
            }
            CameraMode.FIT_ALL -> {
                val combinedPoints = allPoints + newLoc
                val bounds = AutoFitViewportCalculator.computeViewport(combinedPoints)
                _state = _state.copy(
                    centerLat = bounds.centerLat,
                    centerLon = bounds.centerLon,
                    radiusMeters = bounds.radiusMeters,
                    isDecoupled = false
                )
            }
            CameraMode.FIT_ME_TARGET -> {
                recalculateTargetBounds()
            }
            CameraMode.MANUAL -> {
                // In MANUAL mode, user location is recorded, but camera position
                // is decoupled and NEVER overridden!
            }
        }

        return _state
    }

    /**
     * Active observation / peer points updated over the mesh.
     * Updates viewport bounds when in [CameraMode.FIT_ALL].
     */
    fun onPointsUpdated(points: List<GeoPoint>): TacticalCameraState {
        if (_state.mode == CameraMode.FIT_ALL) {
            val userLoc = _state.userLocation
            val combined = if (userLoc != null) points + userLoc else points
            val bounds = AutoFitViewportCalculator.computeViewport(combined)
            _state = _state.copy(
                centerLat = bounds.centerLat,
                centerLon = bounds.centerLon,
                radiusMeters = bounds.radiusMeters
            )
        }
        return _state
    }

    private fun updateMode(mode: CameraMode, allPoints: List<GeoPoint>): TacticalCameraState {
        _state = _state.copy(mode = mode, isDecoupled = (mode == CameraMode.MANUAL))

        when (mode) {
            CameraMode.FOLLOW_ME -> {
                val loc = _state.userLocation
                if (loc != null) {
                    _state = _state.copy(
                        centerLat = loc.latitude,
                        centerLon = loc.longitude
                    )
                }
            }
            CameraMode.FIT_ALL -> {
                val userLoc = _state.userLocation
                val combined = if (userLoc != null) allPoints + userLoc else allPoints
                val bounds = AutoFitViewportCalculator.computeViewport(combined)
                _state = _state.copy(
                    centerLat = bounds.centerLat,
                    centerLon = bounds.centerLon,
                    radiusMeters = bounds.radiusMeters
                )
            }
            CameraMode.FIT_ME_TARGET -> {
                recalculateTargetBounds()
            }
            CameraMode.MANUAL -> {
                // Keep existing center and radius
            }
        }

        return _state
    }

    private fun recalculateTargetBounds() {
        val target = _state.target ?: return
        val targetGeo = GeoPoint(target.latitude, target.longitude)
        val userLoc = _state.userLocation

        val framingPoints = if (userLoc != null) {
            listOf(userLoc, targetGeo)
        } else {
            listOf(targetGeo)
        }

        val bounds = AutoFitViewportCalculator.computeViewport(framingPoints)
        _state = _state.copy(
            centerLat = bounds.centerLat,
            centerLon = bounds.centerLon,
            radiusMeters = bounds.radiusMeters,
            isDecoupled = false
        )
    }
}
