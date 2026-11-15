package com.prism.vehicle.adapters

import com.prism.vehicle.replay.DemoRiskAnalyzer
import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.RiskEventTimeline
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

    @Test fun allScenariosHaveCurrentFrameAssessments() {
        val ids = source.scenarioIds()
        assertEquals(21, ids.size) // 03 엔진(sim_*) 10 + Mock 6 + 방향지시등·사각지대 Mock 5
        ids.forEach(::levels)
    }

    @Test fun normalTrafficIsNotMislabelledAsDanger() {
        listOf("sim_normal", "mock_normal").forEach { id ->
            assertTrue("$id has a false warning", RiskLevel.WARNING !in levels(id))
        }
    }

    @Test fun brakingCutInAndRearApproachEscalate() {
        listOf("sim_sudden_braking", "sim_lane_change", "sim_merge", "sim_rear_approach",
            "mock_sudden_brake", "mock_lane_change", "mock_rear_approach").forEach { id ->
            val observed = levels(id).toSet()
            assertTrue("$id $observed", RiskLevel.WATCH in observed || RiskLevel.WARNING in observed)
        }
    }

    @Test fun eventReasonsFollowTheVehicleMotionInEngineScenarios() {
        mapOf("sim_lane_change" to "CUT_IN", "sim_merge" to "CUT_IN",
            "sim_sudden_braking" to "SUDDEN_BRAKE", "sim_rear_approach" to "REAR_CLOSING").forEach { (id, reason) ->
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

    // ── 5개 Mock 시나리오 회귀 (영상 11~15 분석 결과) ──

    private fun timeline(id: String) = (loader.load(id) as LoadResult.Ready).timeline

    @Test fun mockNormalHasNoWarningOrWatch() {
        val levels = levels("mock_normal").toSet()
        assertEquals("오경고: $levels", setOf(RiskLevel.NORMAL), levels)
    }

    /** 영상 12: car_r 가 '후방 0m → 전방 0m' 로 바뀌던 문제. 같은 차로에서는 뒤에만 있고, 옆 차로로 옮긴 뒤에만 앞으로 간다. */
    @Test fun rearApproachCarOvertakesThroughSideLaneWithoutPassingThroughEgo() {
        val t = timeline("mock_rear_approach")
        val risk = DemoRiskAnalyzer.analyze(t)
        var lastY: Double? = null
        t.vehicleFrames.forEach { f ->
            val ego = f.vehicles.first { it.id == "ego" }
            val r = f.vehicles.firstOrNull { it.id == "car_r" } ?: error("car_r 가 ${f.timestampMs}ms 에 사라짐 (ID 유지 실패)")
            lastY?.let { assertTrue("car_r 위치가 끊김 @${f.timestampMs}", abs(r.y - it) < 1.5) }
            lastY = r.y
            assertTrue("car_r 와 자차가 겹침 @${f.timestampMs}", DemoRiskAnalyzer.clearanceM(ego, r) > 0.0)
            val sameLane = abs(r.x - ego.x) - (r.widthM + ego.widthM) / 2 <= 0.35
            if (sameLane) assertTrue("같은 차로에서 car_r 가 자차 앞에 있음 @${f.timestampMs}", r.y < ego.y)
            val o = risk.getValue(f.timestampMs).riskObjects.first { it.vehicleId == "car_r" }
            assertTrue("car_r 에 전방 원인 @${f.timestampMs}: ${o.reasonCodes}", "FRONT_CLOSING" !in o.reasonCodes && "OVERLAP" !in o.reasonCodes)
        }
        val reasons = risk.values.flatMap { it.riskObjects }.filter { it.vehicleId == "car_r" }
        assertTrue(reasons.any { it.riskLevel == RiskLevel.WARNING && "REAR_CLOSING" in it.reasonCodes })
        assertEquals("추월 뒤에는 위험 해소", RiskLevel.NORMAL, risk.getValue(t.vehicleFrames.last().timestampMs).riskObjects.first { it.vehicleId == "car_r" }.riskLevel)
    }

    /** 영상 13: 앞차 급감속 → 자차가 그 뒤에 감속, 끝에서는 같은 속도로 따라가므로 경고가 남지 않는다. */
    @Test fun suddenBrakeEgoReactsAfterLeadAndWarningClears() {
        val t = timeline("mock_sudden_brake")
        val risk = DemoRiskAnalyzer.analyze(t)
        fun onset(id: String) = t.vehicleFrames.zipWithNext().first { (a, b) ->
            (b.vehicles.first { it.id == id }.vy - a.vehicles.first { it.id == id }.vy) / 0.1 <= -2.0
        }.second.timestampMs
        assertTrue("자차가 앞차보다 먼저 감속", onset("ego") > onset("car_01"))
        val car01 = risk.entries.sortedBy { it.key }.map { it.key to it.value.riskObjects.first { o -> o.vehicleId == "car_01" } }
        assertTrue(car01.any { it.second.riskLevel == RiskLevel.WARNING })
        assertTrue("급제동 전 경고", car01.filter { it.first < 3000 }.all { it.second.riskLevel == RiskLevel.NORMAL })
        assertEquals("시나리오 끝까지 경고가 남음", RiskLevel.NORMAL, car01.last().second.riskLevel)
    }

    /** 영상 14: 차선 변경 의도(CUT_IN)가 실제 진입(LANE_INTRUSION)보다 먼저, 같은 차량 ID 로 이어진다. */
    @Test fun laneChangeIntentPrecedesActualIntrusion() {
        val risk = DemoRiskAnalyzer.analyze(timeline("mock_lane_change")).entries.sortedBy { it.key }
        fun first(code: String) = risk.first { e -> e.value.riskObjects.any { it.vehicleId == "car_02" && code in it.reasonCodes } }.key
        assertTrue(first("CUT_IN") < first("LANE_INTRUSION"))
    }

    /** 영상 15: 화물차 → car_00 → car_01 순으로 감속이 전파되고, 자차 앞차에 연쇄 감속이 표시된다. */
    @Test fun chainReactionShowsOrderedMultiVehicleDeceleration() {
        val t = timeline("mock_chain_reaction")
        val risk = DemoRiskAnalyzer.analyze(t)
        assertEquals(listOf("truck", "car_00", "car_01"), DemoRiskAnalyzer.decelOrder(risk, t.durationMs).map { it.first })
        assertTrue(risk.values.any { f -> f.riskObjects.any { it.vehicleId == "car_01" && "CHAIN_DECEL" in it.reasonCodes } })
        val labels = t.manifest.eventAnnotation.map { it.label }
        assertEquals(listOf("truck", "car_00", "car_01", "자차"), labels.map { it.substringBefore(' ') })
    }

    /** TTC 는 접근 중일 때만, 간격/접근속도와 일치해야 한다. 모든 시나리오·차량에서 다시 계산해 비교. */
    @Test fun ttcMatchesGapAndClosingSpeedEverywhere() {
        source.scenarioIds().forEach { id ->
            val t = timeline(id)
            val risk = DemoRiskAnalyzer.analyze(t)
            t.vehicleFrames.forEach { f ->
                val ego = f.vehicles.first { it.id == f.egoId }
                risk.getValue(f.timestampMs).riskObjects.forEach { o ->
                    val ttc = o.ttcS ?: return@forEach
                    val v = f.vehicles.first { it.id == o.vehicleId }
                    val gap = abs(v.y - ego.y) - (v.lengthM + ego.lengthM) / 2
                    val closing = if (v.y >= ego.y) ego.vy - v.vy else v.vy - ego.vy
                    assertTrue("$id@${f.timestampMs} ${o.vehicleId}: 접근 아님인데 TTC", closing > 0.2 && gap > 0)
                    assertEquals("$id@${f.timestampMs} ${o.vehicleId}", gap / closing, ttc, 1e-6)
                }
            }
        }
    }

    /** 경계값을 오가는 거리에서 매 프레임 등급이 깜박이지 않는다(해제는 600ms 유지 후). */
    @Test fun levelDoesNotFlickerAtThreshold() {
        val frames = (0..20).map { i ->
            val y = if (i % 2 == 0) 10.0 else 11.0 // 간격 5.4m(WARNING) ↔ 6.4m(WATCH) 를 매 프레임 오감
            com.prism.vehicle.replay.VehicleFrame("1.0", i * 100L, "ego_local_m", "ego",
                listOf(car("ego", 0.0, 0.0, 20.0), car("front", 0.0, y, 20.0)))
        }
        val manifest = com.prism.vehicle.replay.ScenarioManifest("flicker", durationMs = 2000, stepMs = 100, egoId = "ego", provenance = "test")
        val levels = DemoRiskAnalyzer.analyze(com.prism.vehicle.replay.ScenarioTimeline.build(manifest, frames, emptyList(), emptyList()))
            .entries.sortedBy { it.key }.map { it.value.riskObjects.single().riskLevel }
        assertEquals(setOf(RiskLevel.WARNING), levels.toSet())
    }

    // ── 방향지시등 · 사각지대 · 차선 변경 안전성 · 이벤트 타임라인 ──

    private fun frames(id: String, vehicle: String) = timeline(id).vehicleFrames.mapNotNull { f -> f.vehicles.firstOrNull { it.id == vehicle }?.let { f.timestampMs to it } }
    private fun reasonsOf(id: String, vehicle: String) = DemoRiskAnalyzer.analyze(timeline(id)).entries.sortedBy { it.key }
        .mapNotNull { e -> e.value.riskObjects.firstOrNull { it.vehicleId == vehicle }?.let { e.key to it } }

    /** 방향지시등 방향 = 차량 자신의 진행 방향 기준. 실제 가로 이동 방향과 같아야 한다(왼쪽 = -x). */
    private fun assertSignalMatchesMotion(id: String, vehicle: String, expected: String) {
        val fs = frames(id, vehicle)
        assertTrue("$id $vehicle 에 $expected 깜빡이가 없음", fs.any { it.second.indicator == expected })
        fs.filter { abs(it.second.vx) > 0.35 }.forEach { (ts, v) ->
            assertEquals("$id@$ts 이동 방향과 깜빡이 불일치", expected, if (v.vx < 0) "LEFT" else "RIGHT")
            assertEquals("$id@$ts 이동 중 깜빡이 꺼짐", expected, v.indicator)
        }
        assertTrue("$id 깜빡이가 이동보다 먼저 켜져야 함", fs.first { it.second.indicator == expected }.first < fs.first { abs(it.second.vx) > 0.35 }.first)
        assertTrue(reasonsOf(id, vehicle).none { "SIGNAL_MISMATCH" in it.second.reasonCodes })
    }

    @Test fun leftLaneCutInUsesRightSignal() = assertSignalMatchesMotion("mock_cut_in_left_signal", "car_11", "RIGHT")
    @Test fun rightLaneCutInUsesLeftSignal() = assertSignalMatchesMotion("mock_brake_and_cut_in", "car_02", "LEFT")
    @Test fun egoLeftLaneChangeUsesLeftSignal() = assertSignalMatchesMotion("mock_ego_lc_blind_left", "ego", "LEFT")
    @Test fun egoRightLaneChangeUsesRightSignal() = assertSignalMatchesMotion("mock_ego_lc_blind_right", "ego", "RIGHT")

    @Test fun cutInWithoutSignalIsNotGivenAFakeSignal() {
        assertTrue(frames("mock_lane_change", "car_02").all { it.second.indicator == "OFF" })
        assertTrue("방향지시등 없는 끼어들기 표시 없음", reasonsOf("mock_lane_change", "car_02").any { "NO_SIGNAL" in it.second.reasonCodes })
    }

    @Test fun signalWithoutLaneChangeIsOnlyIntent() {
        val fs = frames("mock_signal_no_change", "car_12")
        assertTrue(fs.any { it.second.indicator == "LEFT" })
        assertTrue("차선을 바꾸지 않았는데 이동함", fs.all { abs(it.second.vx) < 0.01 && it.second.laneId == "L3" })
        val rs = reasonsOf("mock_signal_no_change", "car_12")
        assertTrue(rs.any { "SIGNAL_TOWARD_EGO" in it.second.reasonCodes })
        assertTrue("깜빡이만으로 실제 진입으로 판단함", rs.none { "LANE_INTRUSION" in it.second.reasonCodes || "CUT_IN" in it.second.reasonCodes })
        val ev = RiskEventTimeline.build(timeline("mock_signal_no_change"), DemoRiskAnalyzer.analyze(timeline("mock_signal_no_change"))).filter { it.vehicleId == "car_12" }
        assertTrue(ev.any { it.kind == RiskEventTimeline.Kind.SIGNAL_ON })
        assertTrue(ev.none { it.kind == RiskEventTimeline.Kind.LANE_BOUNDARY_CROSSED || it.kind == RiskEventTimeline.Kind.LATERAL_MOVE_START })
    }

    private fun blindSpot(id: String, other: String, side: String, wrong: String) {
        val t = timeline(id)
        val rs = reasonsOf(id, other)
        assertTrue("$id 사각지대 감지 없음", rs.any { "BLIND_SPOT_$side" in it.second.reasonCodes })
        assertTrue("$id 반대쪽 사각지대로 표시", rs.none { "BLIND_SPOT_$wrong" in it.second.reasonCodes })
        val conflict = rs.filter { "LANE_CHANGE_CONFLICT" in it.second.reasonCodes }
        assertTrue("$id 깜빡이 켠 쪽 사각지대 차량에 차선 변경 충돌 경고 없음", conflict.isNotEmpty() && conflict.all { it.second.riskLevel == RiskLevel.WARNING })
        val f3000 = t.vehicleFrames.first { it.timestampMs == 3000L }
        assertEquals(DemoRiskAnalyzer.LaneChangeLevel.DANGER, DemoRiskAnalyzer.laneChangeCheck(f3000)?.level)
        // 사각지대가 비고 나서 실제로 이동을 시작할 때는 충돌 위험(DANGER)이 아니어야 함
        val start = t.vehicleFrames.first { f -> f.vehicles.first { it.id == "ego" }.let { abs(it.vx) > 0.35 } }
        assertTrue(DemoRiskAnalyzer.laneChangeCheck(start)?.level != DemoRiskAnalyzer.LaneChangeLevel.DANGER)
    }

    @Test fun leftBlindSpotWarnsOnlyOnTheLeft() = blindSpot("mock_ego_lc_blind_left", "car_bl", "LEFT", "RIGHT")
    @Test fun rightBlindSpotWarnsOnlyOnTheRight() = blindSpot("mock_ego_lc_blind_right", "car_br", "RIGHT", "LEFT")

    @Test fun laneChangeCheckNeedsSignalOrMotion() {
        val f = timeline("mock_normal").vehicleFrames[10]
        assertEquals(null, DemoRiskAnalyzer.laneChangeCheck(f))   // 깜빡이도 이동도 없으면 판정하지 않음
    }

    /** 동시 위험: 상단에 오는 위험은 등급이 가장 높고, 같은 등급이면 TTC 가 가장 짧다. */
    @Test fun simultaneousRisksAreOrderedByUrgency() {
        val risk = DemoRiskAnalyzer.analyze(timeline("mock_brake_and_cut_in"))
        val multi = risk.values.filter { f -> f.riskObjects.count { it.riskLevel == RiskLevel.WARNING || it.riskLevel == RiskLevel.WATCH } >= 2 }
        assertTrue("동시 위험 프레임 없음", multi.isNotEmpty())
        multi.forEach { f ->
            val sorted = f.riskObjects.filter { it.riskLevel == RiskLevel.WARNING || it.riskLevel == RiskLevel.WATCH }.sortedWith(DemoRiskAnalyzer.urgency)
            val top = sorted.first()
            assertTrue(sorted.none { it.riskLevel == RiskLevel.WARNING } || top.riskLevel == RiskLevel.WARNING)
            val sameLevel = sorted.filter { it.riskLevel == top.riskLevel }.mapNotNull { it.ttcS }
            if (top.ttcS != null && sameLevel.isNotEmpty()) assertEquals(sameLevel.min(), top.ttcS!!, 1e-9)
        }
    }

    /** 이벤트 순서: 깜빡이 → 측면 이동 → 차선 경계 통과 → 진입 완료 → 깜빡이 끔. */
    @Test fun timelineSeparatesSignalIntentAndActualLaneChange() {
        val t = timeline("mock_cut_in_left_signal")
        val ev = RiskEventTimeline.build(t, DemoRiskAnalyzer.analyze(t)).filter { it.vehicleId == "car_11" }
        fun at(k: RiskEventTimeline.Kind) = ev.first { it.kind == k }.tMs
        val order = listOf(RiskEventTimeline.Kind.SIGNAL_ON, RiskEventTimeline.Kind.LATERAL_MOVE_START, RiskEventTimeline.Kind.LANE_BOUNDARY_CROSSED,
            RiskEventTimeline.Kind.LANE_ENTRY_DONE, RiskEventTimeline.Kind.SIGNAL_OFF).map(::at)
        assertEquals(order.sorted(), order)
        assertTrue(ev.any { it.kind == RiskEventTimeline.Kind.LANE_CHANGE_INTENT })
    }

    /** 03 엔진 데이터에는 방향지시등이 없다 → 만들어내지 않는다(이벤트·불일치·미사용 판정 없음). */
    @Test fun engineDataWithoutSignalIsNotInvented() {
        listOf("sim_lane_change", "sim_merge").forEach { id ->
            val t = timeline(id)
            assertTrue(t.vehicleFrames.all { f -> f.vehicles.all { it.indicator == null } })
            val risk = DemoRiskAnalyzer.analyze(t)
            assertTrue(risk.values.flatMap { it.riskObjects }.none { "NO_SIGNAL" in it.reasonCodes || "SIGNAL_MISMATCH" in it.reasonCodes || "SIGNAL_TOWARD_EGO" in it.reasonCodes })
            assertTrue(RiskEventTimeline.build(t, risk).none { it.kind == RiskEventTimeline.Kind.SIGNAL_ON })
        }
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    private fun car(id: String, x: Double, y: Double, vy: Double) =
        com.prism.vehicle.replay.VehicleState(id, x, y, 0.0, vy, Math.PI / 2, 4.6, 1.8)
    private fun frame(vararg cars: com.prism.vehicle.replay.VehicleState) =
        com.prism.vehicle.replay.VehicleFrame("1.0", 0, "ego_local_m", "ego", cars.toList())
}
