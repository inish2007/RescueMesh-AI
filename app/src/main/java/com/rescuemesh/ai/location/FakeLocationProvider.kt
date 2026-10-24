package com.rescuemesh.ai.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure in-memory thread-safe fake location provider for unit tests and simulations.
 * Requires zero Android platform or Google Play Services dependencies.
 */
class FakeLocationProvider(
    private var lastKnownLocation: LocationResult? = null,
    private var currentLocation: LocationResult? = null,
    private var shouldThrow: Boolean = false,
    private var delayMillis: Long = 0L
) : RescueLocationProvider {

    private val _lastKnownCalls = AtomicInteger(0)
    private val _currentLocationCalls = AtomicInteger(0)
    private val _observeUpdatesCalls = AtomicInteger(0)

    private val _locationUpdatesFlow = MutableSharedFlow<LocationResult>(replay = 1, extraBufferCapacity = 64)

    val lastKnownCalls: Int get() = _lastKnownCalls.get()
    val currentLocationCalls: Int get() = _currentLocationCalls.get()
    val observeUpdatesCalls: Int get() = _observeUpdatesCalls.get()

    init {
        val initial = currentLocation ?: lastKnownLocation
        if (initial != null) {
            _locationUpdatesFlow.tryEmit(initial)
        }
    }

    override suspend fun getLastKnownLocation(): LocationResult? {
        _lastKnownCalls.incrementAndGet()
        if (delayMillis > 0) delay(delayMillis)
        if (shouldThrow) throw IllegalStateException("Simulated location provider failure")
        return lastKnownLocation
    }

    override suspend fun getCurrentLocation(): LocationResult? {
        _currentLocationCalls.incrementAndGet()
        if (delayMillis > 0) delay(delayMillis)
        if (shouldThrow) throw IllegalStateException("Simulated location provider failure")
        return currentLocation ?: lastKnownLocation
    }

    override fun observeLocationUpdates(
        intervalMs: Long,
        minDistanceMeters: Float
    ): Flow<LocationResult> {
        _observeUpdatesCalls.incrementAndGet()
        if (shouldThrow) throw IllegalStateException("Simulated location provider failure")
        return _locationUpdatesFlow.asSharedFlow()
    }

    fun emitLocation(location: LocationResult) {
        this.lastKnownLocation = location
        this.currentLocation = location
        _locationUpdatesFlow.tryEmit(location)
    }

    fun setLocation(location: LocationResult?) {
        this.lastKnownLocation = location
        this.currentLocation = location
        if (location != null) {
            _locationUpdatesFlow.tryEmit(location)
        }
    }

    fun setLastKnownLocation(location: LocationResult?) {
        this.lastKnownLocation = location
        if (location != null && currentLocation == null) {
            _locationUpdatesFlow.tryEmit(location)
        }
    }

    fun setCurrentLocation(location: LocationResult?) {
        this.currentLocation = location
        if (location != null) {
            _locationUpdatesFlow.tryEmit(location)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun setSimulateUnavailable() {
        this.lastKnownLocation = null
        this.currentLocation = null
        _locationUpdatesFlow.resetReplayCache()
    }

    fun setSimulateError(shouldThrow: Boolean) {
        this.shouldThrow = shouldThrow
    }

    fun setDelay(delayMillis: Long) {
        this.delayMillis = delayMillis
    }
}
