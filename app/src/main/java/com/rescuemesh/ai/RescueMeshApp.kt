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
import com.rescuemesh.ai.mesh.NearbyMeshTransport
import com.rescuemesh.ai.mesh.SyncCoordinator
import com.rescuemesh.ai.data.entity.MissionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // --- Mission Lifecycle & State ---
    companion object {
        const val DEFAULT_MISSION_ID = "rescue-01"
        const val DEFAULT_MISSION_NAME = "Disaster Alpha"
        const val DEFAULT_PAIRING_CODE = "RC-01"
    }

    private val _activeMission = MutableStateFlow<MissionEntity?>(
        MissionEntity(
            missionId = DEFAULT_MISSION_ID,
            name = DEFAULT_MISSION_NAME,
            pairingCode = DEFAULT_PAIRING_CODE,
            description = "Default disaster response sector",
            isActive = true
        )
    )
    val activeMission: StateFlow<MissionEntity?> = _activeMission.asStateFlow()

    // Dynamic active mission ID, backwards compatible with all callers reading app.missionId
    val missionId: String
        get() = _activeMission.value?.missionId ?: DEFAULT_MISSION_ID

    // --- Repositories ---
    val observationRepository: ObservationRepository by lazy {
        ObservationRepository(
            database.observationDao(),
            database.outboxDao(),
            deviceId
        ) { missionId }
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

    // --- Location ---
    val locationProvider: com.rescuemesh.ai.location.RescueLocationProvider by lazy {
        com.rescuemesh.ai.location.AndroidRescueLocationProvider(this)
    }

    // --- Evidence ---
    val evidenceManager: com.rescuemesh.ai.evidence.EvidenceManager by lazy {
        com.rescuemesh.ai.evidence.PhotoEvidenceManager(this)
    }

    // --- Ephemeral Location Sharing ---
    val peerSharingRepo: com.rescuemesh.ai.location.EphemeralPeerLocationRepository by lazy {
        com.rescuemesh.ai.location.EphemeralPeerLocationRepository()
    }

    // --- Mesh ---
    val meshTransport: MeshTransport by lazy {
        NearbyMeshTransport(this) { missionId }
    }

    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            transport = meshTransport,
            observationDao = database.observationDao(),
            outboxDao = database.outboxDao(),
            observationRepo = observationRepository,
            syncRepo = syncRepository,
            scheduler = priorityScheduler,
            mapFusionEngine = mapFusionEngine,
            deviceId = deviceId,
            missionId = missionId,
            chatDao = database.chatDao(),
            peerSharingRepo = peerSharingRepo
        )
    }

    // --- App Scope ---
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize active mission and sequence counter in background
        appScope.launch {
            initActiveMission()
            observationRepository.initSequence()

            // Observe active mission changes from database to update in-memory state flow
            database.missionDao().observeActiveMission().collect { mission ->
                if (mission != null) {
                    _activeMission.value = mission
                }
            }
        }
    }

    suspend fun initActiveMission(): MissionEntity {
        val dao = database.missionDao()
        var current = dao.getActiveMission()
        if (current == null) {
            val all = dao.getAllMissions()
            if (all.isEmpty()) {
                val defaultMission = MissionEntity(
                    missionId = DEFAULT_MISSION_ID,
                    name = DEFAULT_MISSION_NAME,
                    pairingCode = DEFAULT_PAIRING_CODE,
                    description = "Default disaster response sector",
                    isActive = true
                )
                dao.insertMission(defaultMission)
                current = defaultMission
            } else {
                dao.setActiveMission(all.first().missionId)
                current = dao.getActiveMission() ?: all.first()
            }
        }
        _activeMission.value = current
        return current
    }

    suspend fun switchMission(targetMissionId: String): Boolean {
        val dao = database.missionDao()
        val target = dao.getAllMissions().find { it.missionId == targetMissionId }
        if (target != null) {
            dao.setActiveMission(targetMissionId)
            _activeMission.value = target.copy(isActive = true)
            return true
        }
        return false
    }

    suspend fun switchMissionByPairingCode(pairingCode: String): MissionEntity? {
        val dao = database.missionDao()
        val target = dao.getMissionByPairingCode(pairingCode.trim().uppercase())
        if (target != null) {
            dao.setActiveMission(target.missionId)
            _activeMission.value = target.copy(isActive = true)
            return target
        }
        return null
    }

    suspend fun createMission(
        name: String,
        pairingCode: String,
        description: String = "",
        missionId: String = UUID.randomUUID().toString().take(8),
        setAsActive: Boolean = true
    ): MissionEntity {
        val mission = MissionEntity(
            missionId = missionId,
            name = name,
            pairingCode = pairingCode.trim().uppercase(),
            description = description,
            createdAt = System.currentTimeMillis(),
            isActive = setAsActive
        )
        val dao = database.missionDao()
        dao.insertMission(mission)
        if (setAsActive) {
            dao.setActiveMission(mission.missionId)
            _activeMission.value = mission
        }
        return mission
    }

    suspend fun getAllMissions(): List<MissionEntity> {
        return database.missionDao().getAllMissions()
    }
}
