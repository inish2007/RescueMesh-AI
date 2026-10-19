package com.rescuemesh.ai.data.dao

import androidx.room.*
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MapDao {

    // --- Nodes ---

    @Query("SELECT * FROM map_nodes WHERE missionId = :missionId")
    fun observeNodes(missionId: String): Flow<List<MapNodeEntity>>

    @Query("SELECT * FROM map_nodes WHERE missionId = :missionId")
    suspend fun getAllNodes(missionId: String): List<MapNodeEntity>

    @Query("SELECT * FROM map_nodes WHERE nodeId = :nodeId")
    suspend fun getNode(nodeId: String): MapNodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: MapNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<MapNodeEntity>)

    // --- Edges ---

    @Query("SELECT * FROM map_edges WHERE missionId = :missionId")
    fun observeEdges(missionId: String): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE missionId = :missionId")
    suspend fun getAllEdges(missionId: String): List<MapEdgeEntity>

    @Query("SELECT * FROM map_edges WHERE edgeId = :edgeId")
    suspend fun getEdge(edgeId: String): MapEdgeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: MapEdgeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<MapEdgeEntity>)

    @Query("""
        UPDATE map_edges 
        SET routeStatus = :status, 
            lastObservationId = :observationId, 
            conflictingObservationIds = :conflictIds,
            updatedAt = :updatedAt 
        WHERE edgeId = :edgeId
    """)
    suspend fun updateEdgeStatus(
        edgeId: String,
        status: String,
        observationId: String?,
        conflictIds: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * Get edges connected to a specific node.
     * Used by the routing engine to traverse the graph.
     */
    @Query("SELECT * FROM map_edges WHERE fromNodeId = :nodeId OR toNodeId = :nodeId")
    suspend fun getEdgesForNode(nodeId: String): List<MapEdgeEntity>
}
