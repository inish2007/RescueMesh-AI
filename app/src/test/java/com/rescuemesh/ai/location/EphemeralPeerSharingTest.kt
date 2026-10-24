package com.rescuemesh.ai.location

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Validates [EphemeralPeerLocationRepository] / [EphemeralPeerSharing]:
 * - In-memory storage with zero Room database writes.
 * - 10-minute TTL pruning via pruneAndEmit.
 * - Mission isolation dropping mismatched beacons.
 * - StateFlow emission of active unexpired peers.
 */
class EphemeralPeerSharingTest {

    private lateinit var repository: EphemeralPeerLocationRepository

    @Before
    fun setUp() {
        // Use default 10-minute TTL (600,000 ms)
        repository = EphemeralPeerLocationRepository(ttlMillis = 10 * 60 * 1000L)
    }

    @Test
    fun `updatePeerLocation stores beacon and updates activePeers`() {
        val now = System.currentTimeMillis()
        val beacon = PeerLocationBeacon(
            deviceId = "device-alpha",
            deviceName = "Alpha Responder",
            missionId = "MISSION-FLOOD-1",
            latitude = 12.9716,
            longitude = 77.5946,
            accuracy = 4.5f,
            bearing = 90.0f,
            timestamp = now
        )

        repository.updatePeerLocation(beacon, currentMissionId = "MISSION-FLOOD-1", now = now)

        assertEquals(1, repository.peerCount)
        val peer = repository.getPeerLocation("device-alpha")
        assertNotNull(peer)
        assertEquals("Alpha Responder", peer?.deviceName)
        assertEquals(12.9716, peer?.latitude ?: 0.0, 1e-6)
        assertEquals(90.0f, peer?.bearing ?: 0f, 0.01f)

        val flowPeers = repository.activePeers.value
        assertEquals(1, flowPeers.size)
        assertEquals("device-alpha", flowPeers[0].deviceId)
    }

    @Test
    fun `pruneAndEmit removes beacons older than 10-minute TTL`() {
        val simulatedNow = 1_000_000L

        // Fresh beacon (5 minutes old relative to simulatedNow)
        val freshBeacon = PeerLocationBeacon(
            deviceId = "device-fresh",
            deviceName = "Fresh Peer",
            missionId = "MISSION-1",
            latitude = 12.91,
            longitude = 77.61,
            timestamp = simulatedNow - 5 * 60 * 1000L
        )

        // Expired beacon (11 minutes old relative to simulatedNow)
        val expiredBeacon = PeerLocationBeacon(
            deviceId = "device-expired",
            deviceName = "Expired Peer",
            missionId = "MISSION-1",
            latitude = 12.92,
            longitude = 77.62,
            timestamp = simulatedNow - 11 * 60 * 1000L
        )

        repository.updatePeerLocation(freshBeacon, currentMissionId = "MISSION-1", now = simulatedNow)
        repository.updatePeerLocation(expiredBeacon, currentMissionId = "MISSION-1", now = simulatedNow)

        // Expired beacon was pruned immediately on update with timestamp < cutoff
        // Verify only fresh beacon is retained
        val active = repository.pruneAndEmit(now = simulatedNow)

        assertEquals(1, active.size)
        assertEquals("device-fresh", active[0].deviceId)
        assertNull(repository.getPeerLocation("device-expired"))
        assertNotNull(repository.getPeerLocation("device-fresh"))
        assertEquals(1, repository.activePeers.value.size)
    }

    @Test
    fun `mission isolation drops cross-mission beacons immediately`() {
        val now = System.currentTimeMillis()
        val currentMission = "MISSION-ZONE-A"

        val matchingBeacon = PeerLocationBeacon(
            deviceId = "device-team-a",
            deviceName = "Team A Member",
            missionId = "MISSION-ZONE-A",
            latitude = 12.95,
            longitude = 77.55,
            timestamp = now
        )

        val foreignBeacon = PeerLocationBeacon(
            deviceId = "device-foreign",
            deviceName = "Foreign Team",
            missionId = "MISSION-ZONE-B",
            latitude = 13.05,
            longitude = 77.70,
            timestamp = now
        )

        repository.updatePeerLocation(matchingBeacon, currentMissionId = currentMission, now = now)
        repository.updatePeerLocation(foreignBeacon, currentMissionId = currentMission, now = now)

        assertEquals(1, repository.peerCount)
        assertNotNull(repository.getPeerLocation("device-team-a"))
        assertNull(repository.getPeerLocation("device-foreign"))
    }

    @Test
    fun `updating same deviceId replaces previous beacon without duplicates`() {
        val now = System.currentTimeMillis()
        val beacon1 = PeerLocationBeacon(
            deviceId = "device-mobile",
            deviceName = "Rover",
            missionId = "M1",
            latitude = 12.90,
            longitude = 77.60,
            timestamp = now
        )
        val beacon2 = PeerLocationBeacon(
            deviceId = "device-mobile",
            deviceName = "Rover",
            missionId = "M1",
            latitude = 12.95,
            longitude = 77.65,
            timestamp = now + 4000L
        )

        repository.updatePeerLocation(beacon1, currentMissionId = "M1", now = now)
        repository.updatePeerLocation(beacon2, currentMissionId = "M1", now = now + 4000L)

        assertEquals(1, repository.peerCount)
        val current = repository.getPeerLocation("device-mobile")
        assertEquals(12.95, current?.latitude ?: 0.0, 1e-6)
        assertEquals(now + 4000L, current?.timestamp)
    }

    @Test
    fun `clear purges all in-memory peer state`() {
        val now = System.currentTimeMillis()
        val beacon = PeerLocationBeacon(
            deviceId = "device-1",
            deviceName = "Node 1",
            missionId = "M1",
            latitude = 12.0,
            longitude = 77.0,
            timestamp = now
        )
        repository.updatePeerLocation(beacon, currentMissionId = "M1", now = now)
        assertEquals(1, repository.peerCount)

        repository.clear()
        assertEquals(0, repository.peerCount)
        assertTrue(repository.activePeers.value.isEmpty())
        assertNull(repository.getPeerLocation("device-1"))
    }
}
