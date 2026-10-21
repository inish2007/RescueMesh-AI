package com.rescuemesh.ai.ui.screens

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
}
