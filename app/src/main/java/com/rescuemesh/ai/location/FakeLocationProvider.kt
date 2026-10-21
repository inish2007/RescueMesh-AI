package com.rescuemesh.ai.location

import kotlinx.coroutines.delay
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

    val lastKnownCalls: Int get() = _lastKnownCalls.get()
    val currentLocationCalls: Int get() = _currentLocationCalls.get()

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

    fun setLocation(location: LocationResult?) {
        this.lastKnownLocation = location
        this.currentLocation = location
    }

    fun setLastKnownLocation(location: LocationResult?) {
        this.lastKnownLocation = location
    }

    fun setCurrentLocation(location: LocationResult?) {
        this.currentLocation = location
    }

    fun setSimulateUnavailable() {
        this.lastKnownLocation = null
        this.currentLocation = null
    }

    fun setSimulateError(shouldThrow: Boolean) {
        this.shouldThrow = shouldThrow
    }

    fun setDelay(delayMillis: Long) {
        this.delayMillis = delayMillis
    }
}
