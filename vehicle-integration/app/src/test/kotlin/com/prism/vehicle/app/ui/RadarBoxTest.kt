package com.prism.vehicle.app.ui

import ai.prism.visualization.model.RoadConfig
import ai.prism.visualization.rendering.ViewportTransform
import com.prism.vehicle.replay.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarBoxTest {
    @Test fun carBoxStaysCenteredOnTheRenderedCarAcrossPositionsAndHeadings() {
        listOf(600f to 400f, 1200f to 720f).forEach { (width, height) ->
            val projection = ViewportTransform(width, height, RoadConfig(laneCount = 3, laneWidthM = 4f, egoLaneIndex = 1))
            listOf(-4.0 to -12.0, 0.0 to 0.0, 4.0 to 24.0).forEach { (x, y) ->
                listOf(0.0, Math.PI / 2, Math.PI / 3).forEach { heading ->
                    val car = VehicleState("car", x, y, 0.0, 20.0, heading, 4.6, 1.8)
                    val box = radarVehicleBox(car, projection)
                    val center = projection.toScreen(x.toFloat(), y.toFloat())
                    assertEquals(center.x, box.centerX, 0.01f)
                    assertEquals(center.y, box.centerY, 0.01f)
                    assertTrue(box.right > box.left && box.bottom > box.top)
                }
            }
        }
    }
}
