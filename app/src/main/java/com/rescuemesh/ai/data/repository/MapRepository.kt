package com.rescuemesh.ai.data.repository

import com.rescuemesh.ai.data.dao.MapDao
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the operational map graph.
 * Provides access to nodes and edges, and seeds the initial map.
 */
class MapRepository(
    private val mapDao: MapDao,
    private val missionId: String
) {
    fun observeNodes(): Flow<List<MapNodeEntity>> = mapDao.observeNodes(missionId)
    fun observeEdges(): Flow<List<MapEdgeEntity>> = mapDao.observeEdges(missionId)

    suspend fun getAllNodes(): List<MapNodeEntity> = mapDao.getAllNodes(missionId)
    suspend fun getAllEdges(): List<MapEdgeEntity> = mapDao.getAllEdges(missionId)

    suspend fun getNode(nodeId: String): MapNodeEntity? = mapDao.getNode(nodeId)
    suspend fun getEdge(edgeId: String): MapEdgeEntity? = mapDao.getEdge(edgeId)
    suspend fun getEdgesForNode(nodeId: String): List<MapEdgeEntity> = mapDao.getEdgesForNode(nodeId)

    suspend fun insertNode(node: MapNodeEntity) = mapDao.insertNode(node)
    suspend fun insertEdge(edge: MapEdgeEntity) = mapDao.insertEdge(edge)

    /**
     * Seed a sample campus/building map for testing.
     * This represents a REAL surveyed area — modify coordinates to match your test site.
     *
     * The default layout is a simple building floor with:
     * - Main entrance
     * - Corridors (A, B, C)
     * - 4 rooms
     * - Emergency exit
     * - Stairway
     *
     * Adjust the lat/lon to your actual campus GPS coordinates.
     */
    suspend fun seedSampleMap(centerLat: Double = 12.9716, centerLon: Double = 77.5946) {
        val nodes = listOf(
            MapNodeEntity("entrance", missionId, "Main Entrance", 0, centerLat, centerLon, "ENTRANCE"),
            MapNodeEntity("corridor-a", missionId, "Corridor A", 0, centerLat + 0.0001, centerLon, "JUNCTION"),
            MapNodeEntity("corridor-b", missionId, "Corridor B", 0, centerLat + 0.0002, centerLon, "JUNCTION"),
            MapNodeEntity("corridor-c", missionId, "Corridor C", 0, centerLat + 0.0002, centerLon + 0.0001, "JUNCTION"),
            MapNodeEntity("room-101", missionId, "Room 101", 0, centerLat + 0.0001, centerLon - 0.0001, "ROOM"),
            MapNodeEntity("room-102", missionId, "Room 102", 0, centerLat + 0.0001, centerLon + 0.0001, "ROOM"),
            MapNodeEntity("room-201", missionId, "Room 201", 0, centerLat + 0.0002, centerLon - 0.0001, "ROOM"),
            MapNodeEntity("room-202", missionId, "Room 202", 0, centerLat + 0.0003, centerLon + 0.0001, "ROOM"),
            MapNodeEntity("stairway", missionId, "Stairway", 0, centerLat + 0.0003, centerLon, "STAIRWAY"),
            MapNodeEntity("exit-emergency", missionId, "Emergency Exit", 0, centerLat + 0.0003, centerLon - 0.0001, "EXIT")
        )

        val edges = listOf(
            MapEdgeEntity("e-entrance-corridorA", missionId, "entrance", "corridor-a", 15f),
            MapEdgeEntity("e-corridorA-corridorB", missionId, "corridor-a", "corridor-b", 20f),
            MapEdgeEntity("e-corridorB-corridorC", missionId, "corridor-b", "corridor-c", 12f),
            MapEdgeEntity("e-corridorA-room101", missionId, "corridor-a", "room-101", 5f),
            MapEdgeEntity("e-corridorA-room102", missionId, "corridor-a", "room-102", 5f),
            MapEdgeEntity("e-corridorB-room201", missionId, "corridor-b", "room-201", 5f),
            MapEdgeEntity("e-corridorC-room202", missionId, "corridor-c", "room-202", 8f),
            MapEdgeEntity("e-corridorB-stairway", missionId, "corridor-b", "stairway", 10f),
            MapEdgeEntity("e-stairway-exit", missionId, "stairway", "exit-emergency", 8f),
            MapEdgeEntity("e-corridorB-exit", missionId, "corridor-b", "exit-emergency", 25f)
        )

        mapDao.insertNodes(nodes)
        mapDao.insertEdges(edges)
    }
}
