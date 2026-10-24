package com.rescuemesh.ai.ui.screens

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.data.entity.MapNodeEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates [MapCoordinateProjector] geometry calculations and degenerate case safety.
 */
class MapCoordinateProjectorTest {

    @Test
    fun `empty nodes returns empty map without error`() {
        val result = MapCoordinateProjector.project(emptyList(), 800f, 600f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single node is centered in usable canvas`() {
        val node = MapNodeEntity(nodeId = "N1", missionId = "M1", label = "Node 1", floor = 0, latitude = 12.0, longitude = 77.0)
        val result = MapCoordinateProjector.project(listOf(node), 800f, 600f, paddingLeft = 40f, paddingTop = 40f, paddingRight = 40f, paddingBottom = 40f)

        assertEquals(1, result.size)
        val offset = result["N1"]
        assertNotNull(offset)
        assertEquals(400f, offset!!.x, 0.1f)
        assertEquals(300f, offset.y, 0.1f)
    }

    @Test
    fun `multiple nodes preserve north-up and east-right orientation`() {
        // N_North is at lat 13.0, N_South is at lat 12.0
        // N_East is at lon 78.0, N_West is at lon 77.0
        val nSouth = MapNodeEntity(nodeId = "SOUTH", missionId = "M1", label = "South", floor = 0, latitude = 12.0, longitude = 77.5)
        val nNorth = MapNodeEntity(nodeId = "NORTH", missionId = "M1", label = "North", floor = 0, latitude = 13.0, longitude = 77.5)
        val nWest = MapNodeEntity(nodeId = "WEST", missionId = "M1", label = "West", floor = 0, latitude = 12.5, longitude = 77.0)
        val nEast = MapNodeEntity(nodeId = "EAST", missionId = "M1", label = "East", floor = 0, latitude = 12.5, longitude = 78.0)

        val result = MapCoordinateProjector.project(listOf(nSouth, nNorth, nWest, nEast), 1000f, 1000f)

        val posSouth = result["SOUTH"]!!
        val posNorth = result["NORTH"]!!
        val posWest = result["WEST"]!!
        val posEast = result["EAST"]!!

        // Canvas coordinates: top is smaller Y, bottom is larger Y
        assertTrue("North must have smaller Y than South (top of canvas)", posNorth.y < posSouth.y)
        // Canvas coordinates: left is smaller X, right is larger X
        assertTrue("East must have larger X than West (right of canvas)", posEast.x > posWest.x)
    }

    @Test
    fun `collinear points along single latitude do not produce NaN or Inf`() {
        val n1 = MapNodeEntity(nodeId = "N1", missionId = "M1", label = "N1", floor = 0, latitude = 12.0, longitude = 77.1)
        val n2 = MapNodeEntity(nodeId = "N2", missionId = "M1", label = "N2", floor = 0, latitude = 12.0, longitude = 77.5)
        val n3 = MapNodeEntity(nodeId = "N3", missionId = "M1", label = "N3", floor = 0, latitude = 12.0, longitude = 77.9)

        val result = MapCoordinateProjector.project(listOf(n1, n2, n3), 800f, 600f)

        assertEquals(3, result.size)
        result.values.forEach { offset ->
            assertFalse(offset.x.isNaN())
            assertFalse(offset.y.isNaN())
            assertFalse(offset.x.isInfinite())
            assertFalse(offset.y.isInfinite())
        }
    }

    @Test
    fun `situational canvas calculates metric distance, bearing, and bounds correctly`() {
        val centerLat = 12.9716
        val centerLon = 77.5946

        // 1 deg latitude ≈ 111132.95m => 0.001 deg ≈ 111.1m
        val pNorth = SituationalPoint(
            id = "sos-north",
            latitude = centerLat + 0.002, // ~222m North
            longitude = centerLon,
            label = "North SOS",
            type = "SOS"
        )
        val pEast = SituationalPoint(
            id = "hazard-east",
            latitude = centerLat,
            longitude = centerLon + 0.002, // ~216m East (at lat 12.97, cos ≈ 0.9745)
            label = "East Hazard",
            type = "HAZARD"
        )
        val pFarSouth = SituationalPoint(
            id = "far-south",
            latitude = centerLat - 0.02, // ~2.2 km South (outside 500m radius)
            longitude = centerLon,
            label = "Far South",
            type = "NOTE"
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(pNorth, pEast, pFarSouth),
            width = 1000f,
            height = 1000f,
            radiusMeters = 500f,
            paddingPx = 50f
        )

        assertEquals(450f, layout.radiusPx, 0.1f)
        assertEquals(3, layout.ringRadiiPx.size) // 100m, 250m, 500m
        assertEquals(3, layout.projectedPoints.size)

        val projNorth = layout.projectedPoints.find { it.point.id == "sos-north" }!!
        val projEast = layout.projectedPoints.find { it.point.id == "hazard-east" }!!
        val projFar = layout.projectedPoints.find { it.point.id == "far-south" }!!

        // North: Y is smaller than center (toward top), bearing ~ 0 deg
        assertTrue("North point must be within bounds", projNorth.isWithinBounds)
        assertTrue("North point Y must be < center Y", projNorth.canvasOffset.y < layout.centerCanvasOffset.y)
        assertEquals(0f, projNorth.bearingDegrees, 1.0f)
        assertTrue("Distance ~ 222m", projNorth.distanceMeters in 215f..230f)

        // East: X is larger than center (toward right), bearing ~ 90 deg
        assertTrue("East point must be within bounds", projEast.isWithinBounds)
        assertTrue("East point X must be > center X", projEast.canvasOffset.x > layout.centerCanvasOffset.x)
        assertEquals(90f, projEast.bearingDegrees, 2.0f)

        // Far South: Outside 500m bounds, bearing ~ 180 deg, clamped to perimeter
        assertFalse("Far point must be outside bounds", projFar.isWithinBounds)
        assertEquals(180f, projFar.bearingDegrees, 1.0f)
        assertTrue("Distance > 2000m", projFar.distanceMeters > 2000f)
        val distFromCenterPx = (projFar.canvasOffset - layout.centerCanvasOffset).getDistance()
        assertEquals(layout.radiusPx, distFromCenterPx, 0.5f)
    }

    @Test
    fun `identical coordinates produces zero distance zero bearing and no NaN`() {
        val centerLat = 12.9716
        val centerLon = 77.5946

        val pointExactCenter = SituationalPoint(
            id = "center-point",
            latitude = centerLat,
            longitude = centerLon,
            label = "User Origin",
            type = "USER"
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(pointExactCenter),
            width = 800f,
            height = 800f,
            radiusMeters = 500f
        )

        val proj = layout.projectedPoints.first()
        assertEquals(0f, proj.distanceMeters, 1e-4f)
        assertEquals(0f, proj.bearingDegrees, 1e-4f)
        assertFalse("distance must not be NaN", proj.distanceMeters.isNaN())
        assertFalse("bearing must not be NaN", proj.bearingDegrees.isNaN())
        assertFalse("X must not be NaN", proj.canvasOffset.x.isNaN())
        assertFalse("Y must not be NaN", proj.canvasOffset.y.isNaN())
        assertEquals(layout.centerCanvasOffset.x, proj.canvasOffset.x, 0.01f)
        assertEquals(layout.centerCanvasOffset.y, proj.canvasOffset.y, 0.01f)
        assertTrue(proj.isWithinBounds)

        // Also test MapCoordinateProjector.project with multiple identical nodes
        val n1 = MapNodeEntity(nodeId = "SAME1", missionId = "M1", label = "Same1", floor = 0, latitude = centerLat, longitude = centerLon)
        val n2 = MapNodeEntity(nodeId = "SAME2", missionId = "M1", label = "Same2", floor = 0, latitude = centerLat, longitude = centerLon)
        val nodeProjected = MapCoordinateProjector.project(listOf(n1, n2), 800f, 600f)
        assertEquals(2, nodeProjected.size)
        nodeProjected.values.forEach { offset ->
            assertFalse(offset.x.isNaN())
            assertFalse(offset.y.isNaN())
            assertEquals(400f, offset.x, 0.1f)
            assertEquals(254f, offset.y, 0.1f)
        }
    }

    @Test
    fun `extreme coordinates at equator high latitude and date line handle safely without crash`() {
        // 1. Equator (lat = 0.0)
        val equatorLayout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = 0.0,
            centerLon = 0.0,
            points = listOf(
                SituationalPoint(id = "eq-n", latitude = 0.001, longitude = 0.0, label = "Eq North", type = "NOTE"),
                SituationalPoint(id = "eq-e", latitude = 0.0, longitude = 0.001, label = "Eq East", type = "NOTE")
            ),
            width = 600f,
            height = 600f
        )
        val eqN = equatorLayout.projectedPoints.find { it.point.id == "eq-n" }!!
        val eqE = equatorLayout.projectedPoints.find { it.point.id == "eq-e" }!!
        assertFalse(eqN.distanceMeters.isNaN())
        assertFalse(eqE.distanceMeters.isNaN())
        assertEquals(0f, eqN.bearingDegrees, 1.0f)
        assertEquals(90f, eqE.bearingDegrees, 1.0f)

        // 2. High latitude (lat = 85.0 near Arctic)
        val arcticLayout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = 85.0,
            centerLon = 20.0,
            points = listOf(
                SituationalPoint(id = "arc-pt", latitude = 85.001, longitude = 20.005, label = "Arctic Point", type = "HAZARD")
            ),
            width = 600f,
            height = 600f
        )
        val arcPt = arcticLayout.projectedPoints.first()
        assertFalse(arcPt.distanceMeters.isNaN())
        assertFalse(arcPt.canvasOffset.x.isNaN())
        assertFalse(arcPt.canvasOffset.y.isNaN())
        assertTrue(arcPt.distanceMeters > 0f)

        // 3. International Date Line longitude boundary (lon near 180 / -180)
        val dateLineLayout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = 10.0,
            centerLon = 179.999,
            points = listOf(
                SituationalPoint(id = "dateline-pt", latitude = 10.001, longitude = 180.000, label = "180 Meridian", type = "SOS")
            ),
            width = 600f,
            height = 600f
        )
        val dlPt = dateLineLayout.projectedPoints.first()
        assertFalse(dlPt.distanceMeters.isNaN())
        assertFalse(dlPt.bearingDegrees.isNaN())
        assertFalse(dlPt.canvasOffset.x.isNaN())
        assertFalse(dlPt.canvasOffset.y.isNaN())

        // Also verify MapCoordinateProjector.project across wide longitudes
        val nodeWest = MapNodeEntity(nodeId = "DL-W", missionId = "M1", label = "West DL", floor = 0, latitude = 10.0, longitude = 179.9)
        val nodeEast = MapNodeEntity(nodeId = "DL-E", missionId = "M1", label = "East DL", floor = 0, latitude = 10.0, longitude = 180.0)
        val dlResult = MapCoordinateProjector.project(listOf(nodeWest, nodeEast), 500f, 500f)
        assertEquals(2, dlResult.size)
        dlResult.values.forEach { offset ->
            assertFalse(offset.x.isNaN())
            assertFalse(offset.y.isNaN())
        }
    }

    @Test
    fun `canvas size zero or negative usable dimensions falls back safely without crash`() {
        val node = MapNodeEntity(nodeId = "N1", missionId = "M1", label = "Node 1", floor = 0, latitude = 12.0, longitude = 77.0)
        val point = SituationalPoint(id = "P1", latitude = 12.0, longitude = 77.0, label = "Point 1", type = "NOTE")

        // 1. MapCoordinateProjector.project with 0x0
        val zeroResult = MapCoordinateProjector.project(listOf(node), 0f, 0f)
        assertEquals(1, zeroResult.size)
        assertEquals(Offset(0f, 0f), zeroResult["N1"])

        // 2. MapCoordinateProjector.project with dimensions smaller than padding (negative usable space)
        val tinyResult = MapCoordinateProjector.project(
            listOf(node),
            width = 50f,
            height = 50f,
            paddingLeft = 40f,
            paddingTop = 40f,
            paddingRight = 40f,
            paddingBottom = 40f
        )
        assertEquals(1, tinyResult.size)
        assertEquals(Offset(25f, 25f), tinyResult["N1"])

        // 3. projectSituationalCanvas with 0x0 or negative dimensions
        val zeroLayout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = 12.0,
            centerLon = 77.0,
            points = listOf(point),
            width = 0f,
            height = 0f,
            paddingPx = 36f
        )
        assertNotNull(zeroLayout)
        assertTrue("radiusPx must remain positive", zeroLayout.radiusPx > 0f)
        assertFalse(zeroLayout.centerCanvasOffset.x.isNaN())
        assertFalse(zeroLayout.centerCanvasOffset.y.isNaN())
        assertEquals(1, zeroLayout.projectedPoints.size)
        assertFalse(zeroLayout.projectedPoints.first().canvasOffset.x.isNaN())
        assertFalse(zeroLayout.projectedPoints.first().canvasOffset.y.isNaN())

        // 4. projectSituationalCanvas with negative width/height
        val negLayout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = 12.0,
            centerLon = 77.0,
            points = listOf(point),
            width = -100f,
            height = -100f
        )
        assertNotNull(negLayout)
        assertTrue(negLayout.radiusPx >= 10f)
    }

    @Test
    fun `null accuracy zero accuracy and positive accuracy are handled correctly`() {
        val centerLat = 12.9716
        val centerLon = 77.5946

        val ptNullAcc = SituationalPoint(id = "p-null", latitude = centerLat + 0.001, longitude = centerLon, label = "Null Acc", type = "NOTE", accuracy = null)
        val ptZeroAcc = SituationalPoint(id = "p-zero", latitude = centerLat + 0.001, longitude = centerLon, label = "Zero Acc", type = "NOTE", accuracy = 0f)
        val ptNegAcc = SituationalPoint(id = "p-neg", latitude = centerLat + 0.001, longitude = centerLon, label = "Neg Acc", type = "NOTE", accuracy = -10f)
        val ptPosAcc = SituationalPoint(id = "p-pos", latitude = centerLat + 0.001, longitude = centerLon, label = "Pos Acc", type = "NOTE", accuracy = 25f)

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(ptNullAcc, ptZeroAcc, ptNegAcc, ptPosAcc),
            width = 1000f,
            height = 1000f,
            radiusMeters = 500f,
            paddingPx = 50f
        )

        val projNull = layout.projectedPoints.find { it.point.id == "p-null" }!!
        val projZero = layout.projectedPoints.find { it.point.id == "p-zero" }!!
        val projNeg = layout.projectedPoints.find { it.point.id == "p-neg" }!!
        val projPos = layout.projectedPoints.find { it.point.id == "p-pos" }!!

        assertEquals(0f, projNull.accuracyRadiusPx, 1e-4f)
        assertEquals(0f, projZero.accuracyRadiusPx, 1e-4f)
        assertEquals(0f, projNeg.accuracyRadiusPx, 1e-4f)

        // For positive accuracy: 25m * (450px / 500m) = 22.5px
        assertEquals(22.5f, projPos.accuracyRadiusPx, 0.5f)
        assertTrue("accuracyRadiusPx must be within [4f, radiusPx]", projPos.accuracyRadiusPx in 4f..layout.radiusPx)
    }
}
