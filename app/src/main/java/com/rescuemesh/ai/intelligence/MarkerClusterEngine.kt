package com.rescuemesh.ai.intelligence

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.ui.screens.ProjectedSituationalPoint
import com.rescuemesh.ai.ui.screens.SituationalPoint
import kotlin.math.sqrt

/**
 * Screen-space marker cluster data model.
 *
 * CRITICAL ARCHITECTURAL INVARIANT (Zero Synthetic Coordinates):
 * - [representativePoint] preserves the genuine physical GPS coordinates (latitude, longitude)
 *   of the highest-severity real-world report.
 * - Under NO circumstances may synthetic coordinates or artificial radial displacement
 *   be generated on the canvas.
 * - Expanding clusters is handled exclusively via tactical inspection sheets listing [points].
 */
data class MarkerCluster(
    val clusterId: String,
    val screenOffset: Offset,
    val representativePoint: SituationalPoint,
    val points: List<SituationalPoint>,
    val hasSos: Boolean,
    val hasHazard: Boolean,
    val totalCount: Int
) {
    val isCluster: Boolean get() = totalCount > 1
}

/**
 * Screen-Space Greedy Distance Clustering Engine with Emergency Priority Sorting.
 *
 * Implements Gate 3 Requirement R2:
 * 1. Screen-space greedy distance clustering with threshold $D_{\text{cluster}} = 36\text{ dp}$.
 * 2. Emergency severity priority sorting:
 *    SOS (rank 1) > HAZARD (rank 2) > ROUTE_STATUS:BLOCKED (rank 3) >
 *    ROUTE_STATUS:OPEN (rank 4) > NOTE (rank 5) > PEER (rank 6) > OTHER (rank 7).
 * 3. Exact physical GPS coordinate preservation for cluster centroids (zero synthetic coordinates;
 *    synthetic radial displacement is strictly prohibited).
 * 4. Badge and glow inheritance:
 *    - `hasSos = true` -> displays SOS badge and tactical red outer glow.
 *    - `hasHazard = true` -> displays hazard badge and amber outer glow.
 * 5. Full underlying points preservation for tactical report inspection bottom sheets.
 * 6. High-performance hit-testing for touch interactions on mobile screens.
 */
object MarkerClusterEngine {

    const val DEFAULT_CLUSTER_DISTANCE_PX = 36f // 36 dp default screen threshold

    /**
     * Mandatory tactical safety disclaimer required by Sentinel [P0-4 / G3-R04].
     */
    const val SAFETY_DISCLAIMER =
        "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards."

    /**
     * Clusters projected situational points into [MarkerCluster] instances.
     *
     * @param projectedPoints Screen-projected points from [MapCoordinateProjector].
     * @param clusterDistancePx Screen distance threshold in pixels for merging markers.
     * @return List of [MarkerCluster] instances sorted by emergency priority.
     */
    fun cluster(
        projectedPoints: List<ProjectedSituationalPoint>,
        clusterDistancePx: Float = DEFAULT_CLUSTER_DISTANCE_PX
    ): List<MarkerCluster> {
        if (projectedPoints.isEmpty()) return emptyList()

        val safeDistance = kotlin.math.max(1f, clusterDistancePx)

        // 1. Sort all candidates by emergency severity rank ascending (1 = highest priority),
        // then by timestamp descending (newest first), then by id for determinism.
        val sortedPoints = projectedPoints.sortedWith(
            compareBy<ProjectedSituationalPoint> { getSeverityRank(it.point) }
                .thenByDescending { it.point.timestamp }
                .thenBy { it.point.id }
        )

        val visitedIds = HashSet<String>()
        val clusters = ArrayList<MarkerCluster>()

        for (candidate in sortedPoints) {
            if (candidate.point.id in visitedIds) continue

            // Form a new cluster seeded by this highest-severity point
            visitedIds.add(candidate.point.id)
            val clusterProjectedPoints = ArrayList<ProjectedSituationalPoint>()
            clusterProjectedPoints.add(candidate)

            val seedOffset = candidate.canvasOffset

            // Greedy distance search among remaining unvisited points
            for (other in sortedPoints) {
                if (other.point.id in visitedIds) continue

                val dx = seedOffset.x - other.canvasOffset.x
                val dy = seedOffset.y - other.canvasOffset.y
                val dist = sqrt(dx * dx + dy * dy)

                if (dist <= safeDistance) {
                    visitedIds.add(other.point.id)
                    clusterProjectedPoints.add(other)
                }
            }

            // Order cluster members by emergency severity for tactical inspection sheets
            val orderedPoints = clusterProjectedPoints
                .map { it.point }
                .sortedWith(
                    compareBy<SituationalPoint> { getSeverityRank(it) }
                        .thenByDescending { it.timestamp }
                )

            val representative = orderedPoints.first()
            val hasSos = orderedPoints.any { isSos(it) }
            val hasHazard = orderedPoints.any { isHazard(it) }

            clusters.add(
                MarkerCluster(
                    clusterId = "cluster_${representative.id}_${orderedPoints.size}",
                    screenOffset = seedOffset,
                    representativePoint = representative,
                    points = orderedPoints,
                    hasSos = hasSos,
                    hasHazard = hasHazard,
                    totalCount = orderedPoints.size
                )
            )
        }

        return clusters
    }

    /**
     * Determines emergency severity rank (1 = highest priority, 7 = lowest priority).
     */
    fun getSeverityRank(point: SituationalPoint): Int {
        val type = point.type.trim().uppercase()
        val status = point.status?.trim()?.uppercase()

        return when {
            type == "SOS" -> 1
            type == "HAZARD" -> 2
            type == "ROUTE_STATUS" && (status == "BLOCKED" || status == "REPORTED_BLOCKED" || status == "CONFLICTED") -> 3
            type == "ROUTE_STATUS" -> 4
            type == "NOTE" -> 5
            type == "PEER" -> 6
            else -> 7
        }
    }

    fun isSos(point: SituationalPoint): Boolean =
        point.type.trim().equals("SOS", ignoreCase = true)

    fun isHazard(point: SituationalPoint): Boolean =
        point.type.trim().equals("HAZARD", ignoreCase = true)

    /**
     * Finds the cluster tapped by the user within a touch hit radius.
     *
     * In the event of overlapping clusters, prioritizes clusters with SOS, then Hazard,
     * then largest total point count, then smallest distance to tap.
     */
    fun findClusterAt(
        clusters: List<MarkerCluster>,
        tapOffset: Offset,
        hitRadiusPx: Float = DEFAULT_CLUSTER_DISTANCE_PX
    ): MarkerCluster? {
        val candidates = clusters.mapNotNull { cluster ->
            val dx = cluster.screenOffset.x - tapOffset.x
            val dy = cluster.screenOffset.y - tapOffset.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= hitRadiusPx) {
                Pair(cluster, dist)
            } else {
                null
            }
        }

        return candidates.minWithOrNull(
            compareBy<Pair<MarkerCluster, Float>> { if (it.first.hasSos) 0 else 1 }
                .thenBy { if (it.first.hasHazard) 0 else 1 }
                .thenByDescending { it.first.totalCount }
                .thenBy { it.second }
        )?.first
    }
}
