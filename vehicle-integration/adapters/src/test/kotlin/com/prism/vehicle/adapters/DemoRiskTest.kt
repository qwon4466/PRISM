package com.prism.vehicle.adapters

import com.prism.vehicle.replay.DemoRiskAnalyzer
import com.prism.vehicle.replay.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class AssetFiles(private val root: File) : TextSource {
    override fun read(path: String): String? = File(root, path).takeIf { it.isFile }?.readText()
}

class DemoRiskTest {
    private val source = JsonFileProviders(AssetFiles(File("../app/src/main/assets")))
    private val loader = ScenarioLoader(source, source, source)

    private fun levels(id: String): List<RiskLevel> {
        val timeline = (loader.load(id) as LoadResult.Ready).timeline
        val risk = DemoRiskAnalyzer.analyze(timeline)
        timeline.vehicleFrames.forEach { frame ->
            val objects = risk.getValue(frame.timestampMs).riskObjects
            assertEquals("$id@${frame.timestampMs}", frame.vehicles.size - 1, objects.size)
            assertEquals(frame.vehicles.filter { it.id != frame.egoId }.map { it.id }.toSet(), objects.map { it.vehicleId }.toSet())
            assertTrue(objects.all { (it.ttcS ?: 0.0) >= 0.0 })
        }
        return risk.values.flatMap { it.riskObjects }.map { it.riskLevel }
    }

    @Test fun allThirteenScenariosHaveCurrentFrameAssessments() {
        val ids = source.scenarioIds()
        assertEquals(13, ids.size)
        ids.forEach(::levels)
    }

    @Test fun normalTrafficIsNotMislabelledAsDanger() {
        listOf("normal", "mock_normal").forEach { id ->
            assertTrue("$id has a false warning", RiskLevel.WARNING !in levels(id))
        }
    }

    @Test fun brakingCutInAndRearApproachEscalate() {
        listOf("sudden_braking", "lane_change", "merge", "rear_approach",
            "mock_sudden_brake", "mock_lane_change", "mock_rear_approach").forEach { id ->
            val observed = levels(id).toSet()
            assertTrue("$id $observed", RiskLevel.WATCH in observed || RiskLevel.WARNING in observed)
        }
    }

    @Test fun eventReasonsFollowTheVehicleMotionInEngineScenarios() {
        mapOf("lane_change" to "CUT_IN", "merge" to "CUT_IN",
            "sudden_braking" to "SUDDEN_BRAKE", "rear_approach" to "REAR_CLOSING").forEach { (id, reason) ->
            val timeline = (loader.load(id) as LoadResult.Ready).timeline
            val objects = DemoRiskAnalyzer.analyze(timeline).values.flatMap { it.riskObjects }
            assertTrue("$id never reports $reason", objects.any {
                reason in it.reasonCodes && it.riskLevel != RiskLevel.NORMAL
            })
        }
    }

    @Test fun closerFrontCarHasNoLowerRiskAtSameClosingSpeed() {
        val ego = car("ego", 0.0, 0.0, 25.0)
        fun level(y: Double) = DemoRiskAnalyzer.assess(frame(ego, car("front", 0.0, y, 15.0)))
            .riskObjects.single().riskLevel
        assertEquals(RiskLevel.NORMAL, level(60.0))
        assertEquals(RiskLevel.WATCH, level(25.0))
        assertEquals(RiskLevel.WARNING, level(10.0))
    }

    @Test fun adjacentCarDoesNotWarnJustBecauseItIsClose() {
        val ego = car("ego", 0.0, 0.0, 25.0)
        val side = car("side", 3.6, 2.0, 25.0)
        assertEquals(RiskLevel.NORMAL, DemoRiskAnalyzer.assess(frame(ego, side)).riskObjects.single().riskLevel)
    }

    @Test fun closeFollowingCarIsFlaggedEvenAtConstantSpeed() {
        val ego = car("ego", 0.0, 0.0, 25.0)
        val rear = car("rear", 0.0, -10.0, 25.0)
        assertEquals(RiskLevel.WATCH, DemoRiskAnalyzer.assess(frame(ego, rear)).riskObjects.single().riskLevel)
    }

    private fun car(id: String, x: Double, y: Double, vy: Double) =
        com.prism.vehicle.replay.VehicleState(id, x, y, 0.0, vy, Math.PI / 2, 4.6, 1.8)
    private fun frame(vararg cars: com.prism.vehicle.replay.VehicleState) =
        com.prism.vehicle.replay.VehicleFrame("1.0", 0, "ego_local_m", "ego", cars.toList())
}
