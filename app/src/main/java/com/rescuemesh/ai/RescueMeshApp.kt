package com.rescuemesh.ai

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.rescuemesh.ai.data.database.RescueMeshDatabase
import com.rescuemesh.ai.data.repository.MapRepository
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.mesh.MeshTransport
import com.rescuemesh.ai.mesh.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Application class — initializes all modules with manual dependency injection.
 *
 * This is the "poor man's DI" approach: simple, explicit, no framework overhead.
 * For a prototype this size, it's cleaner than introducing Hilt/Dagger.
 */
class RescueMeshApp : Application() {

    // --- Database ---
    val database: RescueMeshDatabase by lazy { RescueMeshDatabase.getInstance(this) }

    // --- Device Identity ---
    val deviceId: String by lazy {
        // Use Android ID as a semi-stable device identifier
        // In production, you'd use Android Keystore for proper identity
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        androidId ?: UUID.randomUUID().toString().take(8)
    }

    val deviceName: String by lazy {
        "${Build.MANUFACTURER} ${Build.MODEL}".take(20)
    }

    // --- Mission ---
    // Default mission ID — all devices must use the same one to sync
    val missionId: String = "rescue-01"

    // --- Repositories ---
    val observationRepository: ObservationRepository by lazy {
        ObservationRepository(
            database.observationDao(),
            database.outboxDao(),
            deviceId,
            missionId
        )
    }

    val mapRepository: MapRepository by lazy {
        MapRepository(database.mapDao(), missionId)
    }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(database.metricsDao())
    }

    // --- Intelligence ---
    val priorityScheduler: PriorityScheduler by lazy {
        PriorityScheduler(
            database.observationDao(),
            database.mapDao(),
            database.outboxDao()
        )
    }

    val mapFusionEngine: MapFusionEngine by lazy {
        MapFusionEngine(database.mapDao(), database.observationDao())
    }

    val routePlanner: RoutePlanner by lazy {
        RoutePlanner(database.mapDao())
    }

    // --- Mesh ---
    val meshTransport: MeshTransport by lazy {
        MeshTransport(this)
    }

    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            transport = meshTransport,
            observationDao = database.observationDao(),
            outboxDao = database.outboxDao(),
            observationRepo = observationRepository,
            syncRepo = syncRepository,
            scheduler = priorityScheduler,
            deviceId = deviceId,
            missionId = missionId
        )
    }

    // --- App Scope ---
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize sequence counter and seed map in background
        appScope.launch {
            observationRepository.initSequence()

            // Seed sample map if database is empty
            val nodes = mapRepository.getAllNodes()
            if (nodes.isEmpty()) {
                mapRepository.seedSampleMap()
            }
        }
    }
}
