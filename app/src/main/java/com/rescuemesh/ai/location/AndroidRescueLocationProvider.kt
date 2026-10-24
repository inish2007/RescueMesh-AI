package com.rescuemesh.ai.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Production Android implementation of [RescueLocationProvider] designed for
 * network-denied disaster environments.
 *
 * Acquisition strategy:
 * 1. Checks runtime permissions (ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION).
 *    If missing, returns null immediately without throwing.
 * 2. Attempts [FusedLocationProviderClient]. If available and successful, returns result.
 * 3. Falls back to offline hardware [LocationManager.GPS_PROVIDER] (and optionally NETWORK_PROVIDER).
 * 4. Applies a bounded timeout to prevent indefinite suspension in subterranean/shielded zones.
 * 5. Returns null on timeout, hardware failure, or disabled GPS. Never throws or fabricates data.
 */
class AndroidRescueLocationProvider(
    private val context: Context,
    private val fusedClient: FusedLocationProviderClient? = try {
        LocationServices.getFusedLocationProviderClient(context.applicationContext)
    } catch (_: Throwable) {
        null
    },
    private val locationManager: LocationManager? = try {
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    } catch (_: Throwable) {
        null
    },
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS
) : RescueLocationProvider {

    companion object {
        private const val TAG = "RescueLocationProvider"
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val FAST_TIMEOUT_MS = 3_000L
        const val DEFAULT_UPDATE_INTERVAL_MS = 2000L
        const val DEFAULT_MIN_DISTANCE_METERS = 0.5f
    }

    /**
     * Emits continuous 2-second location updates (min displacement 0.5m) for real-time foreground tracking.
     */
    fun observeContinuousLocationUpdates(
        intervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS,
        minDistanceMeters: Float = DEFAULT_MIN_DISTANCE_METERS
    ): Flow<LocationResult> = observeLocationUpdates(intervalMs, minDistanceMeters)

    override suspend fun getLastKnownLocation(): LocationResult? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted for getLastKnownLocation")
            return@withContext null
        }

        // 1. Try FusedLocationProviderClient last location
        val fusedLoc = getFusedLastLocation()
        if (fusedLoc != null) {
            return@withContext fusedLoc.toLocationResult("fused")
        }

        // 2. Fallback to LocationManager last known location
        val lmLoc = getLocationManagerLastLocation()
        if (lmLoc != null) {
            return@withContext lmLoc.toLocationResult(lmLoc.provider ?: "location_manager")
        }

        Log.d(TAG, "No cached last known location available")
        null
    }

    override suspend fun getCurrentLocation(): LocationResult? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted for getCurrentLocation")
            return@withContext null
        }

        // 1. Try FusedLocationProviderClient current location with timeout
        val fusedLoc = getFusedCurrentLocation(timeoutMillis)
        if (fusedLoc != null) {
            return@withContext fusedLoc.toLocationResult("fused")
        }

        // 2. Fallback to hardware LocationManager (GPS_PROVIDER primary, NETWORK_PROVIDER secondary)
        val lmLoc = getLocationManagerCurrentLocation(timeoutMillis)
        if (lmLoc != null) {
            return@withContext lmLoc.toLocationResult(lmLoc.provider ?: "gps_fallback")
        }

        Log.d(TAG, "No current location acquired within timeout of ${timeoutMillis}ms")
        null
    }

    @SuppressLint("MissingPermission")
    override fun observeLocationUpdates(
        intervalMs: Long,
        minDistanceMeters: Float
    ): Flow<LocationResult> = callbackFlow {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted for observeLocationUpdates")
            close()
            return@callbackFlow
        }

        var registeredWithFused = false
        var fusedCallback: LocationCallback? = null

        if (fusedClient != null) {
            try {
                val priority = if (hasFineLocationPermission()) {
                    Priority.PRIORITY_HIGH_ACCURACY
                } else {
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY
                }
                val request = LocationRequest.Builder(priority, intervalMs).apply {
                    setMinUpdateIntervalMillis(intervalMs / 2)
                    setMinUpdateDistanceMeters(minDistanceMeters)
                    setWaitForAccurateLocation(false)
                }.build()

                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                        result.lastLocation?.let { loc ->
                            trySend(loc.toLocationResult("fused"))
                        }
                    }
                }
                fusedCallback = callback
                fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                registeredWithFused = true
                Log.d(TAG, "Registered for location updates via FusedLocationProviderClient")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException registering Fused updates: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Exception registering Fused updates: ${e.message}")
            }
        }

        var lmListener: LocationListener? = null
        if (!registeredWithFused) {
            val lm = locationManager
            if (lm != null) {
                val isGpsEnabled = hasFineLocationPermission() && try {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                } catch (_: Exception) { false }

                val isNetworkEnabled = try {
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                } catch (_: Exception) { false }

                val providerToUse = when {
                    isGpsEnabled -> LocationManager.GPS_PROVIDER
                    isNetworkEnabled -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }

                if (providerToUse != null) {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            trySend(location.toLocationResult(providerToUse))
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    lmListener = listener
                    try {
                        lm.requestLocationUpdates(
                            providerToUse,
                            intervalMs,
                            minDistanceMeters,
                            listener,
                            Looper.getMainLooper()
                        )
                        Log.d(TAG, "Registered for location updates via LocationManager ($providerToUse)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception registering LocationManager updates: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "No LocationManager provider enabled for updates")
                }
            }
        }

        awaitClose {
            if (registeredWithFused && fusedCallback != null) {
                try {
                    fusedClient?.removeLocationUpdates(fusedCallback)
                    Log.d(TAG, "Removed Fused location updates")
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing Fused updates: ${e.message}")
                }
            }
            if (lmListener != null) {
                try {
                    locationManager?.removeUpdates(lmListener)
                    Log.d(TAG, "Removed LocationManager updates")
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing LocationManager updates: ${e.message}")
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    // --- Permissions Verification ---

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // --- Fused Location Implementation ---

    @SuppressLint("MissingPermission")
    private suspend fun getFusedLastLocation(): Location? {
        val client = fusedClient ?: return null
        return try {
            withTimeoutOrNull(FAST_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    client.lastLocation
                        .addOnSuccessListener { loc ->
                            if (continuation.isActive) continuation.resume(loc)
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Fused lastLocation failed: ${e.message}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                        .addOnCanceledListener {
                            if (continuation.isActive) continuation.cancel()
                        }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in getFusedLastLocation: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception in getFusedLastLocation: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getFusedCurrentLocation(timeoutMs: Long): Location? {
        val client = fusedClient ?: return null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val cts = CancellationTokenSource()
                    continuation.invokeOnCancellation {
                        cts.cancel()
                    }

                    val priority = if (hasFineLocationPermission()) {
                        Priority.PRIORITY_HIGH_ACCURACY
                    } else {
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    }

                    client.getCurrentLocation(priority, cts.token)
                        .addOnSuccessListener { loc ->
                            if (continuation.isActive) continuation.resume(loc)
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Fused getCurrentLocation failed: ${e.message}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                        .addOnCanceledListener {
                            if (continuation.isActive) continuation.cancel()
                        }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in getFusedCurrentLocation: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception in getFusedCurrentLocation: ${e.message}")
            null
        }
    }

    // --- LocationManager Fallback Implementation ---

    @SuppressLint("MissingPermission")
    private fun getLocationManagerLastLocation(): Location? {
        val lm = locationManager ?: return null
        return try {
            val providers = mutableListOf<String>()
            if (hasFineLocationPermission() && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                providers.add(LocationManager.GPS_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                providers.add(LocationManager.NETWORK_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                providers.add(LocationManager.PASSIVE_PROVIDER)
            }

            var bestLocation: Location? = null
            for (provider in providers) {
                try {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading last location from $provider: ${e.message}")
                }
            }
            bestLocation
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in getLocationManagerLastLocation: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception in getLocationManagerLastLocation: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLocationManagerCurrentLocation(timeoutMs: Long): Location? {
        val lm = locationManager ?: return null

        val isGpsEnabled = hasFineLocationPermission() && try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }

        val isNetworkEnabled = try {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }

        val providerToUse = when {
            isGpsEnabled -> LocationManager.GPS_PROVIDER
            isNetworkEnabled -> LocationManager.NETWORK_PROVIDER
            else -> {
                Log.d(TAG, "Neither GPS nor Network provider is enabled on LocationManager")
                return null
            }
        }

        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            try {
                                lm.removeUpdates(this)
                            } catch (_: Exception) {}
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                        override fun onProviderEnabled(provider: String) {}

                        override fun onProviderDisabled(provider: String) {
                            if (provider == providerToUse) {
                                try {
                                    lm.removeUpdates(this)
                                } catch (_: Exception) {}
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        try {
                            lm.removeUpdates(listener)
                        } catch (_: Exception) {}
                    }

                    try {
                        lm.requestLocationUpdates(
                            providerToUse,
                            1000L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    } catch (e: SecurityException) {
                        try { lm.removeUpdates(listener) } catch (_: Exception) {}
                        if (continuation.isActive) continuation.resume(null)
                    } catch (e: Exception) {
                        try { lm.removeUpdates(listener) } catch (_: Exception) {}
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in getLocationManagerCurrentLocation: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception in getLocationManagerCurrentLocation: ${e.message}")
            null
        }
    }

    private fun Location.toLocationResult(sourceProvider: String): LocationResult {
        return LocationResult(
            latitude = latitude,
            longitude = longitude,
            accuracy = if (hasAccuracy()) accuracy else null,
            provider = sourceProvider,
            timestamp = if (time > 0) time else System.currentTimeMillis(),
            bearing = if (hasBearing()) bearing else null,
            speed = if (hasSpeed()) speed else null,
            altitude = if (hasAltitude()) altitude else null
        )
    }
}
