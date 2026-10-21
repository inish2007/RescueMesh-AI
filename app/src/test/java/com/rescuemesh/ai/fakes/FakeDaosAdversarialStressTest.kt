package com.rescuemesh.ai.fakes

import com.rescuemesh.ai.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Empirical stress harness challenging [FakeObservationDao], [FakeMapDao], [FakeOutboxDao],
 * [FakeMetricsDao], and [FakePeerDao] under heavy concurrent coroutines:
 * 1. Thread safety and weak consistency under concurrent reads/writes
 * 2. Strict atomicity of stats accumulation (no lost updates)
 * 3. Race condition prevention during ID generation and sequencing
 * 4. Concurrent cleanups (clearDelivered, clear) during active iterations
 * 5. Edge cases: empty tables, null fields, non-existent entity mutations
 */
class FakeDaosAdversarialStressTest {

    @Test
    fun testObservationDaoHighConcurrencyAndDuplicateSuppression() = runBlocking(Dispatchers.Default) {
        val dao = FakeObservationDao()
        val numWriters = 20
        val itemsPerWriter = 100
        val totalExpectedUnique = numWriters * itemsPerWriter

        // 1. Concurrent writes with interleaved identical duplicate attempts
        val writeJobs = (0 until numWriters).map { writerId ->
            launch {
                for (seq in 1..itemsPerWriter) {
                    val entity = ObservationEntity(
                        eventId = "evt-$writerId-$seq",
                        missionId = "mission-stress",
                        authorDeviceId = "device-$writerId",
                        authorSequence = seq.toLong(),
                        eventType = if (seq % 2 == 0) "HAZARD" else "NOTE",
                        title = "Concurrent title $seq from writer $writerId",
                        isLocal = (writerId % 2 == 0),
                        observedAtDevice = 1000L + seq
                    )
                    dao.insert(entity)
                    // Attempt duplicate insertion of the exact same eventId with different title
                    dao.insert(entity.copy(title = "DUPLICATE OVERWRITE ATTEMPT"))
                }
            }
        }

        // 2. Concurrent readers querying digests and sequence numbers during active writes
        val readerJobs = (0 until 10).map { readerId ->
            launch {
                for (step in 1..50) {
                    val digests = dao.getAllSequenceDigests()
                    val count = dao.observeCount("mission-stress").first()
                    assertTrue("Count should never be negative", count >= 0)
                    val localCount = dao.observeLocalCount("mission-stress").first()
                    val receivedCount = dao.observeReceivedCount("mission-stress").first()
                    assertTrue("Local count non-negative", localCount >= 0)
                    assertTrue("Received count non-negative", receivedCount >= 0)
                    dao.getLatestSequence("device-$readerId")
                }
            }
        }

        (writeJobs + readerJobs).joinAll()

        // Verify total unique count and consistent count partitioning post-completion
        val count = dao.observeCount("mission-stress").first()
        val localCount = dao.observeLocalCount("mission-stress").first()
        val receivedCount = dao.observeReceivedCount("mission-stress").first()
        assertEquals(totalExpectedUnique, count)
        assertEquals("Local + Received count must equal total count", count, localCount + receivedCount)

        // Verify total unique count (duplicates suppressed via putIfAbsent)
        val all = dao.getAll("mission-stress")
        assertEquals(totalExpectedUnique, all.size)

        // Verify duplicates were indeed ignored and didn't overwrite original title
        val sample = dao.getById("evt-0-1")
        assertNotNull(sample)
        assertFalse("Duplicate overwrite should be ignored", sample!!.title.contains("DUPLICATE OVERWRITE"))

        // Verify sequence digests
        val digests = dao.getAllSequenceDigests()
        assertEquals(numWriters, digests.size)
        for (d in digests) {
            assertEquals("Each author should have max sequence equal to itemsPerWriter", itemsPerWriter.toLong(), d.maxSeq)
        }
    }

    @Test
    fun testPeerDaoStrictAtomicStatsAccumulationNoLostUpdates() = runBlocking(Dispatchers.Default) {
        val dao = FakePeerDao()
        val deviceId = "peer-stressed-target"

        dao.upsert(
            PeerEntity(
                deviceId = deviceId,
                endpointId = "ep-test",
                displayName = "Stress Peer",
                isConnected = true
            )
        )

        val numWriters = 25
        val opsPerWriter = 200
        val bytesPerOp = 37L
        val countPerOp = 3

        val totalExpectedBytes = numWriters * opsPerWriter * bytesPerOp
        val totalExpectedMessages = numWriters * opsPerWriter * countPerOp

        val jobs = (0 until numWriters).map {
            launch {
                for (op in 1..opsPerWriter) {
                    dao.addSentStats(deviceId, bytes = bytesPerOp, count = countPerOp)
                    dao.addReceivedStats(deviceId, bytes = bytesPerOp, count = countPerOp)
                }
            }
        }
        jobs.joinAll()

        val peer = dao.getById(deviceId)
        assertNotNull(peer)
        assertEquals("Atomic accumulation of sent bytes must not lose updates", totalExpectedBytes, peer!!.bytesSent)
        assertEquals("Atomic accumulation of sent messages must not lose updates", totalExpectedMessages, peer.messagesSent)
        assertEquals("Atomic accumulation of received bytes must not lose updates", totalExpectedBytes, peer.bytesReceived)
        assertEquals("Atomic accumulation of received messages must not lose updates", totalExpectedMessages, peer.messagesReceived)
    }

    @Test
    fun testPeerDaoConcurrentDisconnectionsAndReconnections() = runBlocking(Dispatchers.Default) {
        val dao = FakePeerDao()
        val numPeers = 20
        (1..numPeers).forEach { i ->
            dao.upsert(
                PeerEntity(
                    deviceId = "device-$i",
                    endpointId = "ep-$i",
                    displayName = "Peer $i",
                    isConnected = true
                )
            )
        }

        val togglers = (1..numPeers).map { i ->
            launch {
                for (cycle in 1..20) {
                    dao.markDisconnected("ep-$i")
                    dao.updateSyncState("device-$i", System.currentTimeMillis(), "{\"sync\": $cycle}")
                    dao.upsert(
                        PeerEntity(
                            deviceId = "device-$i",
                            endpointId = "ep-$i-new",
                            displayName = "Peer $i",
                            isConnected = true
                        )
                    )
                }
            }
        }
        togglers.joinAll()

        val allPeers = dao.observeAll().first()
        assertEquals(numPeers, allPeers.size)
    }

    @Test
    fun testOutboxDaoConcurrentInsertionsAndConcurrentClearDelivered() = runBlocking(Dispatchers.Default) {
        val dao = FakeOutboxDao()
        val numWriters = 20
        val itemsPerWriter = 50

        // Writers inserting entries and marking some DELIVERED
        val writeJobs = (0 until numWriters).map { writerId ->
            launch {
                for (i in 1..itemsPerWriter) {
                    val id = dao.insert(
                        OutboxEntity(
                            eventId = "evt-outbox-$writerId-$i",
                            targetPeerId = "peer-$writerId",
                            priority = (i % 10) * 0.1f,
                            status = "PENDING"
                        )
                    )
                    assertTrue(id > 0L)
                    if (i % 2 == 0) {
                        dao.updateStatus(id, "DELIVERED")
                    }
                }
            }
        }

        // Sweeper concurrently purging DELIVERED entries
        val sweeperJob = launch {
            for (round in 1..30) {
                dao.clearDelivered()
                dao.getPendingByPriority()
                dao.getPendingByFifo()
            }
        }

        writeJobs.joinAll()
        sweeperJob.join()

        // Final purge
        dao.clearDelivered()

        val remaining = dao.observeRecent(numWriters * itemsPerWriter).first()
        // Exactly half (odd i) should remain PENDING
        val expectedRemaining = numWriters * (itemsPerWriter / 2)
        assertEquals(expectedRemaining, remaining.size)
        assertTrue(remaining.all { it.status == "PENDING" })
    }

    @Test
    fun testMetricsDaoConcurrentAggregationsAndEdgeCases() = runBlocking(Dispatchers.Default) {
        val dao = FakeMetricsDao()

        // 1. Initial empty checks (SQLite SUM/AVG nullability semantics)
        assertNull("Empty table observeTotalBytesSent must return null", dao.observeTotalBytesSent().first())
        assertNull("Empty table observeTotalBytesReceived must return null", dao.observeTotalBytesReceived().first())
        assertNull("Empty table observeAvgDeliveryLatency must return null", dao.observeAvgDeliveryLatency().first())
        assertEquals(0, dao.observeQueueDecisionCount().first())

        // 2. High concurrency inserts
        val numWriters = 15
        val itemsPerWriter = 80

        val totalExpectedSentBytes = numWriters * itemsPerWriter * 150L
        val totalExpectedReceivedBytes = numWriters * itemsPerWriter * 75L
        val expectedLatencyMs = 120L

        val jobs = (0 until numWriters).map { writerId ->
            launch {
                for (i in 1..itemsPerWriter) {
                    dao.insert(
                        MetricsEventEntity(
                            metricType = "SEND",
                            bytesTransferred = 150,
                            peerId = "peer-$writerId"
                        )
                    )
                    dao.insert(
                        MetricsEventEntity(
                            metricType = "RECEIVE",
                            bytesTransferred = 75,
                            peerId = "peer-$writerId"
                        )
                    )
                    dao.insert(
                        MetricsEventEntity(
                            metricType = "DELIVERY",
                            latencyMs = expectedLatencyMs,
                            peerId = "peer-$writerId"
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

        assertEquals(totalExpectedSentBytes, dao.observeTotalBytesSent().first())
        assertEquals(totalExpectedReceivedBytes, dao.observeTotalBytesReceived().first())
        assertEquals(expectedLatencyMs.toDouble(), dao.observeAvgDeliveryLatency().first()!!, 1e-4)
        assertEquals(numWriters * itemsPerWriter, dao.observeQueueDecisionCount().first())
    }

    @Test
    fun testMapDaoConcurrentEdgeStatusUpdatesAndPreservation() = runBlocking(Dispatchers.Default) {
        val dao = FakeMapDao()
        val numEdges = 30
        (1..numEdges).forEach { i ->
            dao.insertEdge(
                MapEdgeEntity(
                    edgeId = "edge-$i",
                    missionId = "m1",
                    fromNodeId = "node-$i",
                    toNodeId = "node-${i + 1}",
                    distance = 10f * i,
                    routeStatus = "UNKNOWN"
                )
            )
        }

        val updaters = (1..numEdges).map { i ->
            launch {
                for (update in 1..20) {
                    val status = if (update % 2 == 0) "REPORTED_BLOCKED" else "CONFLICTED"
                    dao.updateEdgeStatus(
                        edgeId = "edge-$i",
                        status = status,
                        observationId = "obs-$i-$update",
                        conflictIds = "conflict-$i",
                        updatedAt = System.currentTimeMillis()
                    )
                    val edges = dao.getEdgesForNode("node-$i")
                    assertTrue(edges.isNotEmpty())
                }
            }
        }
        updaters.joinAll()

        val allEdges = dao.getAllEdges("m1")
        assertEquals(numEdges, allEdges.size)
        assertTrue(allEdges.all { it.routeStatus in listOf("REPORTED_BLOCKED", "CONFLICTED") })
    }

    @Test
    fun testClearMethodsFullyResetInternalStateWithoutMemoryLeaks() = runBlocking(Dispatchers.Default) {
        val obsDao = FakeObservationDao()
        val mapDao = FakeMapDao()
        val outboxDao = FakeOutboxDao()
        val peerDao = FakePeerDao()
        val metricsDao = FakeMetricsDao()

        // Populate with records
        obsDao.insert(ObservationEntity(eventId = "e1", missionId = "m1", authorDeviceId = "d1", authorSequence = 1L, eventType = "NOTE", title = "T1", observedAtDevice = 1L))
        mapDao.insertNode(MapNodeEntity(nodeId = "n1", missionId = "m1", label = "Node 1", latitude = 1.0, longitude = 2.0))
        mapDao.insertEdge(MapEdgeEntity(edgeId = "ed1", missionId = "m1", fromNodeId = "n1", toNodeId = "n2", distance = 5f, routeStatus = "OPEN"))
        outboxDao.insert(OutboxEntity(eventId = "e1", priority = 0.5f, status = "PENDING"))
        outboxDao.insertReceipt(DeliveryReceiptEntity(eventId = "e1", peerDeviceId = "d2", bytesTransferred = 50))
        peerDao.upsert(PeerEntity(deviceId = "d1", endpointId = "ep1", displayName = "Peer 1"))
        metricsDao.insert(MetricsEventEntity(metricType = "SEND", bytesTransferred = 100))

        // Verify populated
        assertEquals(1, obsDao.getAll("m1").size)
        assertEquals(1, mapDao.getAllNodes("m1").size)
        assertEquals(1, mapDao.getAllEdges("m1").size)
        assertEquals(1, outboxDao.observeRecent(10).first().size)
        assertTrue(outboxDao.isDeliveredTo("e1", "d2"))
        assertNotNull(peerDao.getById("d1"))
        assertEquals(1, metricsDao.getAll().size)

        // Clear all
        obsDao.clear()
        mapDao.clear()
        outboxDao.clear()
        peerDao.clear()
        metricsDao.clearAll()

        // Verify completely cleared
        assertEquals(0, obsDao.getAll("m1").size)
        assertEquals(0, mapDao.getAllNodes("m1").size)
        assertEquals(0, mapDao.getAllEdges("m1").size)
        assertEquals(0, outboxDao.observeRecent(10).first().size)
        assertFalse(outboxDao.isDeliveredTo("e1", "d2"))
        assertNull(peerDao.getById("d1"))
        assertEquals(0, metricsDao.getAll().size)

        // Verify ID counter resets and new inserts start at 1
        val newOutboxId = outboxDao.insert(OutboxEntity(eventId = "e2", priority = 0.9f))
        assertEquals(1L, newOutboxId)

        metricsDao.insert(MetricsEventEntity(metricType = "RECEIVE", bytesTransferred = 200))
        val newMetric = metricsDao.getAll().first()
        assertEquals(1L, newMetric.id)
    }
}
