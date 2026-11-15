package com.prism.prediction

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModesTest {
    private fun vehicle(vx: Double, vy: Double, heading: Double) =
        VehicleState("car", 0.0, 10.0, vx, vy, heading, 4.5, 1.8, "L1")

    @Test
    fun laneChangeIsSmoothAndBoundedByLaneWidth() {
        val trajectories = Modes.candidates(
            listOf(TimedVehicle(0, vehicle(0.0, 5.0, PI / 2))),
            PredictionConfig(horizonsMs = listOf(1000, 2000, 4000), laneWidthM = 3.5),
        )
        val keep = trajectories[0].points
        val left = trajectories[2].points
        val offsets = keep.zip(left).map { (base, shifted) -> base.x - shifted.x }
        assertTrue(offsets[0] in 0.0..offsets[1])
        assertTrue(offsets[1] < offsets[2])
        assertEquals(3.5, offsets.last(), 1e-9)
    }

    @Test
    fun laneKeepDuringLaneChangeStaysWithinOneLane() {
        // 차선 변경 중(횡속도 4.6 m/s): 등속 외삽이면 5초에 23 m(약 6차선)까지 뻗던 경로
        val keep = Modes.candidates(
            listOf(TimedVehicle(0, VehicleState("car", -3.6, 6.7, 4.6, 24.4, 1.464, 5.0, 2.0, "L1"))),
            PredictionConfig(laneWidthM = 4.0),
        )[0].points
        val lateral = keep.map { it.x - (-3.6) }
        assertTrue(lateral.all { it in 0.0..4.0 + 1e-9 })
        assertTrue(lateral.zipWithNext().all { (a, b) -> b >= a - 1e-9 })
        assertEquals(PI / 2, keep.last().headingRad, 0.05) // 차선 방향으로 돌아온다
        assertTrue(keep.zipWithNext().all { (a, b) -> b.y > a.y }) // 계속 전진
    }

    @Test
    fun stoppingVehicleLaneChangePathDoesNotSlideSideways() {
        // sim_stop_and_go car_23: 정지한 자차 뒤에서 멈추는 차량. 예전엔 4초 안에 옆 차선까지 거의 옆으로 이동하는 경로였다
        val c = Modes.candidates(
            listOf(
                TimedVehicle(0, VehicleState("car", 0.0, -16.0, 0.0, 5.4, PI / 2, 5.0, 2.0, "L2")),
                TimedVehicle(100, VehicleState("car", 0.0, -15.5, 0.0, 5.1, PI / 2, 5.0, 2.0, "L2")),
            ),
            PredictionConfig(laneWidthM = 4.0),
        )
        for (tr in c.filter { it.mode.startsWith("lane_change") }) tr.points.forEach { p ->
            val forward = p.y - (-15.5)
            assertTrue("${tr.mode} 횡 ${p.x} 전진 $forward", abs(p.x) <= 0.3 * forward + 1e-9)
        }
    }

    @Test
    fun midLaneChangeVehicleStopsAtTargetLaneCentre() {
        // sim_stop_and_go car_22: 오른쪽 차선(+4)에서 자차 차선(0)으로 진입 중. 예전엔 왼쪽 경로가 x = -1.3(왼쪽 차선 쪽)까지 지나쳤다
        val c = Modes.candidates(
            listOf(TimedVehicle(0, VehicleState("car", 3.4, 28.0, -1.2, 11.9, 1.67, 5.0, 2.0, "L3"))),
            PredictionConfig(laneWidthM = 4.0),
        ).associateBy { it.mode }
        for (m in listOf("lane_keep", "lane_change_left")) {
            assertTrue(m, c.getValue(m).points.all { it.x >= -1e-9 }) // 목표 차선 중앙(0)을 넘지 않음
            assertEquals(m, 0.0, c.getValue(m).points.last().x, 0.05)
        }
        assertEquals(4.0, c.getValue("lane_change_right").points.last().x, 0.05) // 반대 방향 = 원래 차선 복귀
        assertTrue(c.getValue("braking").points.all { it.x in -1e-9..3.4 }) // 감속하면서도 목표 차선 중앙을 넘지 않음
    }

    @Test
    fun brakingVehicleIsNotPredictedToReverse() {
        // 정지한 자차 뒤에서 제동 중인 후방 차량: 등가속 외삽은 속도 0 이후 후진으로 넘어간다
        val keep = Modes.candidates(
            listOf(
                TimedVehicle(0, VehicleState("car", 0.0, -40.0, 0.0, 12.0, PI / 2, 5.0, 2.0, "L2")),
                TimedVehicle(100, VehicleState("car", 0.0, -38.8, 0.0, 11.4, PI / 2, 5.0, 2.0, "L2")),
            ),
        )[0].points
        assertTrue(keep.zipWithNext().all { (a, b) -> b.y >= a.y - 1e-9 })
        assertEquals(keep[keep.size - 2].y, keep.last().y, 1e-9) // 멈춘 뒤 제자리
        assertTrue(keep.all { it.y > -38.8 })
    }

    @Test
    fun curvatureChangesPositionAndHeading() {
        val point = Modes.candidates(
            listOf(TimedVehicle(0, vehicle(5.0, 0.0, 0.0).copy(y = 0.0))),
            PredictionConfig(horizonsMs = listOf(1000), roadCurvaturePerM = 0.1),
        ).first().points.single()
        assertTrue(point.y > 0.0)
        assertTrue(point.headingRad > 0.0)
    }
}
