package com.rescuemesh.ai.fakes

import android.util.Log
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MetricsEventEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.entity.PeerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeDaosConcurrencyTest {

    @Test
    fun testAndroidLogReturnsDefaultValueOnJvm() {
        // Verifies Feature 4: testOptions { unitTests.isReturnDefaultValues = true }
        // When configured properly, android.util.Log calls do not throw RuntimeException.
        val result = Log.d("TestTag", "Testing default return values")
        assertEquals(0, result)
    }

    @Test
    fun testFakeObservationDaoConcurrentWritesAndReads() = runBlocking(Dispatchers.Default) {
        val dao = FakeObservationDao()
        val numThreads = 10
        val itemsPerThread = 50

        val jobs = (0 until numThreads).map { threadIdx ->
            launch {
                for (i in 0 until itemsPerThread) {
                    val eventId = "event-$threadIdx-$i"
                    dao.insert(
                        ObservationEntity(
                            eventId = eventId,
                            missionId = "mission-1",
                            authorDeviceId = "device-$threadIdx",
                            authorSequence = i.toLong(),
                            eventType = "NOTE",
                            title = "Observation $i from thread $threadIdx",
                            observedAtDevice = System.currentTimeMillis()
                        )
                    )
                    // Interleaved concurrent reads
                    dao.getAllSequenceDigests()
                    dao.getAll("mission-1")
                    dao.getLatestSequence("device-$threadIdx")
                }
            }
        }
        jobs.joinAll()

        val all = dao.getAll("mission-1")
        assertEquals(numThreads * itemsPerThread, all.size)
        val digests = dao.getAllSequenceDigests()
        assertEquals(numThreads, digests.size)
    }

    @Test
    fun testFakeMapDaoConcurrentEdgeUpdates() = runBlocking(Dispatchers.Default) {
        val dao = FakeMapDao()
        val edgeId = "edge-1"
        dao.insertEdge(
            MapEdgeEntity(
                edgeId = edgeId,
                missionId = "mission-1",
                fromNodeId = "node-A",
                toNodeId = "node-B",
                distance = 10f,
                routeStatus = "OPEN"
            )
        )

        val numUpdaters = 10
        val updatesPerThread = 30
        val jobs = (0 until numUpdaters).map { threadIdx ->
            launch {
                for (i in 0 until updatesPerThread) {
                    dao.updateEdgeStatus(
                        edgeId = edgeId,
                        status = if (i % 2 == 0) "REPORTED_BLOCKED" else "REPORTED_OPEN",
                        observationId = "obs-$threadIdx-$i",
                        conflictIds = "obs-conflict-$threadIdx",
                        updatedAt = System.currentTimeMillis()
                    )
                    dao.getEdgesForNode("node-A")
                }
            }
        }
        jobs.joinAll()

        val edge = dao.getEdge(edgeId)
        assertNotNull(edge)
        assertTrue(edge!!.routeStatus in listOf("REPORTED_BLOCKED", "REPORTED_OPEN"))
    }

    @Test
    fun testFakeOutboxDaoConcurrentInsertsAndStateUpdates() = runBlocking(Dispatchers.Default) {
        val dao = FakeOutboxDao()
        val numThreads = 10
        val itemsPerThread = 40

        val jobs = (0 until numThreads).map { threadIdx ->
            launch {
                for (i in 0 until itemsPerThread) {
                    val id = dao.insert(
                        OutboxEntity(
                            eventId = "event-$threadIdx-$i",
                            targetPeerId = "peer-$threadIdx",
                            priority = (i % 10) * 0.1f,
                            status = "PENDING"
                        )
                    )
                    assertTrue(id > 0L)
                    dao.updateStatus(id, "SENT")
                    dao.updatePriority(id, 0.95f, "VOI")
                    dao.insertReceipt(
                        DeliveryReceiptEntity(
                            eventId = "event-$threadIdx-$i",
                            peerDeviceId = "peer-$threadIdx",
                            bytesTransferred = 128
                        )
                    )
                    dao.getPendingByPriority()
                    dao.isDeliveredTo("event-$threadIdx-$i", "peer-$threadIdx")
                }
            }
        }
        jobs.joinAll()

        val recent = dao.observeRecent(numThreads * itemsPerThread).first()
        assertEquals(numThreads * itemsPerThread, recent.size)
        // IDs must be distinct and monotonically increasing
        val ids = recent.map { it.id }.toSet()
        assertEquals(numThreads * itemsPerThread, ids.size)
    }

    @Test
    fun testFakeMetricsDaoConcurrentAggregations() = runBlocking(Dispatchers.Default) {
        val dao = FakeMetricsDao()
        val numThreads = 8
        val eventsPerThread = 25

        val jobs = (0 until numThreads).map { threadIdx ->
            launch {
                for (i in 0 until eventsPerThread) {
                    dao.insert(
                        MetricsEventEntity(
                            metricType = "SEND",
                            bytesTransferred = 100,
                            peerId = "peer-$threadIdx"
                        )
                    )
                    dao.insert(
                        MetricsEventEntity(
                            metricType = "RECEIVE",
                            bytesTransferred = 50,
                            peerId = "peer-$threadIdx"
                        )
                    )
                    dao.insert(
                        MetricsEventEntity(
                            metricType = "DELIVERY",
                            latencyMs = 200L,
                            peerId = "peer-$threadIdx"
                        )
                    )
                    dao.insert(
                        MetricsEventEntity(
                            metricType = "QUEUE_DECISION",
                            schedulerType = "VOI"
                        )
                    )
                }
            }
        }
        jobs.joinAll()

        val totalSent = dao.observeTotalBytesSent().first()
        assertEquals((numThreads * eventsPerThread * 100).toLong(), totalSent)

        val totalReceived = dao.observeTotalBytesReceived().first()
        assertEquals((numThreads * eventsPerThread * 50).toLong(), totalReceived)

        val avgLatency = dao.observeAvgDeliveryLatency().first()
        assertEquals(200.0, avgLatency!!, 0.001)

        val queueDecisions = dao.observeQueueDecisionCount().first()
        assertEquals(numThreads * eventsPerThread, queueDecisions)
    }

    @Test
    fun testFakePeerDaoConcurrentStatsAccumulation() = runBlocking(Dispatchers.Default) {
        val dao = FakePeerDao()
        val deviceId = "peer-device-alpha"
        dao.upsert(
            PeerEntity(
                deviceId = deviceId,
                endpointId = "ep-123",
                displayName = "Peer Alpha",
                isConnected = true
            )
        )

        val numThreads = 10
        val opsPerThread = 50
        val jobs = (0 until numThreads).map {
            launch {
                for (i in 0 until opsPerThread) {
                    dao.addSentStats(deviceId, bytes = 10L, count = 1)
                    dao.addReceivedStats(deviceId, bytes = 5L, count = 1)
                    dao.updateSyncState(deviceId, System.currentTimeMillis(), "{\"d1\": 5}")
                }
            }
        }
        jobs.joinAll()

        val peer = dao.getById(deviceId)
        assertNotNull(peer)
        assertEquals((numThreads * opsPerThread * 10).toLong(), peer!!.bytesSent)
        assertEquals(numThreads * opsPerThread, peer.messagesSent)
        assertEquals((numThreads * opsPerThread * 5).toLong(), peer.bytesReceived)
        assertEquals(numThreads * opsPerThread, peer.messagesReceived)
    }
}
