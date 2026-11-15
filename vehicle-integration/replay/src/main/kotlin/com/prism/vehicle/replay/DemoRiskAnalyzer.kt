package com.prism.vehicle.replay

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Offline scenario display heuristic. This is not Engine 02 or a live ADAS assessment.
 * Positions are ego-local metres (x right, y ahead); velocities are in m/s.
 *
 * 등급 규칙 (진입 조건, 매 프레임):
 *  - TTC = 앞뒤 간격(m) / 접근 속도(m/s). 접근 속도 = 앞차면 자차-앞차, 뒤차면 뒤차-자차. 0.2 m/s 이하면 TTC 없음(null).
 *  - WARNING: 앞 간격 ≤ 6m 또는 TTC ≤ 1.5s / 뒤 간격 ≤ 3m 또는 접근 중 간격 ≤ 5m·TTC ≤ 1.5s / 끼어들기 9m 이내 / 겹침
 *  - WATCH: 앞 차간시간 < 1.0s(최소 6m) 또는 TTC ≤ 3s 또는 같은 차로 앞차 급감속 / 연쇄 감속 / 뒤 간격 ≤ 8m 등
 * 해제 조건: 낮은 등급이 [RELEASE_HOLD_MS] 동안 이어져야 내린다(경계값 깜박임 방지). 올릴 때는 즉시.
 */
object DemoRiskAnalyzer {
    const val RELEASE_HOLD_MS = 600L
    private const val BRAKE_MPS2 = -2.5       // 단일 급감속 판정
    private const val CHAIN_DECEL_MPS2 = -2.0 // 연쇄 감속 구성원(뒤차는 앞차보다 부드럽게 감속)

    private class Held(val obj: RiskObject, val lowerSince: Long?)

    fun analyze(timeline: ScenarioTimeline): Map<Long, RiskFrame> = buildMap {
        val held = HashMap<String, Held>()
        var lastTs: Long? = null
        timeline.vehicleFrames.forEachIndexed { index, frame ->
            val previous = timeline.vehicleFrames.getOrNull((index - 5).coerceAtLeast(0))
                ?.takeIf { frame.timestampMs - it.timestampMs in 100..1_000 }
            val rawUnknown = timeline.snapshotAt(frame.timestampMs).risk?.riskObjects
                ?.filter { it.riskLevel == RiskLevel.UNKNOWN }?.map { it.vehicleId }?.toSet().orEmpty()
            // 프레임 누락 뒤에는 이전 등급을 이어 붙이지 않는다
            lastTs?.let { if (frame.timestampMs - it > timeline.manifest.stepMs * 2) held.clear() }
            lastTs = frame.timestampMs
            held.keys.retainAll(frame.vehicles.map { it.id }.toSet())
            val raw = assess(frame, previous, rawUnknown)
            put(frame.timestampMs, raw.copy(riskObjects = raw.riskObjects.map { hold(it, frame.timestampMs, held) }))
        }
    }

    private fun rank(l: RiskLevel) = when (l) { RiskLevel.WARNING -> 2; RiskLevel.WATCH -> 1; else -> 0 }

    private fun hold(o: RiskObject, ts: Long, held: MutableMap<String, Held>): RiskObject {
        val prev = held[o.vehicleId]
        if (o.riskLevel == RiskLevel.UNKNOWN || prev == null || rank(o.riskLevel) >= rank(prev.obj.riskLevel)) {
            held[o.vehicleId] = Held(o, null); return o
        }
        val since = prev.lowerSince ?: ts
        if (ts - since >= RELEASE_HOLD_MS) { held[o.vehicleId] = Held(o, null); return o }
        held[o.vehicleId] = Held(prev.obj, since)
        return o.copy(riskLevel = prev.obj.riskLevel, reasonCodes = o.reasonCodes.ifEmpty { prev.obj.reasonCodes })
    }

    fun assess(frame: VehicleFrame, previous: VehicleFrame? = null, unknownIds: Set<String> = emptySet()): RiskFrame {
        val ego = frame.vehicles.firstOrNull { it.id == frame.egoId }
        if (ego == null) return RiskFrame("1.0", frame.timestampMs, emptyList())
        val previousById = previous?.vehicles?.associateBy { it.id }.orEmpty()
        val dt = previous?.let { (frame.timestampMs - it.timestampMs) / 1000.0 } ?: 0.0
        fun decel(v: VehicleState): Double {
            val was = previousById[v.id] ?: return 0.0
            return if (dt > 0) (v.vy - was.vy) / dt else 0.0
        }
        fun lateralGap(v: VehicleState) = max(0.0, abs(v.x - ego.x) - (v.widthM + ego.widthM) / 2)
        val sameLaneAhead = frame.vehicles.filter { it.id != ego.id && it.y > ego.y && lateralGap(it) <= 0.35 && valid(it) }
        // 자차 차로 앞에서 2대 이상이 동시에 감속 중이면 단일 급제동이 아니라 연쇄 감속. 가장 가까운 앞차에 표시한다.
        val chainLead = sameLaneAhead.minByOrNull { it.y }?.id
            ?.takeIf { valid(ego) && sameLaneAhead.count { decel(it) <= CHAIN_DECEL_MPS2 } >= 2 }

        val egoTowards = egoMovingTo(ego)
        val objects = frame.vehicles.asSequence().filter { it.id != ego.id }.map { other ->
            if (other.id in unknownIds || !valid(other) || !valid(ego)) {
                return@map RiskObject(other.id, RiskLevel.UNKNOWN, listOf("UNRELIABLE_OBSERVATION"))
            }
            val dx = other.x - ego.x
            val dy = other.y - ego.y
            val lateralGap = lateralGap(other)
            val lengthGap = max(0.0, abs(dy) - (other.lengthM + ego.lengthM) / 2)
            val samePath = lateralGap <= 0.35
            val ahead = dy >= 0
            val closing = if (ahead) ego.vy - other.vy else other.vy - ego.vy
            val ttc = if (samePath && closing > 0.2 && lengthGap > 0) lengthGap / closing else null
            val towardEgo = if (dx > 0) ego.vx - other.vx else other.vx - ego.vx
            // 그 차 스스로 자차 쪽으로 움직이는지(절대 가로속도). 자차가 옆으로 움직여 생기는 상대 접근을 끼어들기로 착각하지 않게
            val otherToward = if (dx > 0) -other.vx else other.vx
            // 의도(옆 차로에서 자차 쪽으로 이동 중)와 실제 진입(이미 자차 차로에 걸친 채 계속 이동)을 나눈다
            val cutIn = ahead && dy < 35 && lateralGap > 0.35 && otherToward > 0.35 && towardEgo > 0.35 &&
                lateralGap / towardEgo <= 2.5
            val intruding = ahead && samePath && dy < 35 && abs(dx) > 0.3 && otherToward > 0.35
            // 사각지대: 바로 옆 차로(가로 간격 0.35~2.5m)에서 자차 뒤범퍼 8m 뒤 ~ 앞범퍼 1m 앞
            val sideLane = lateralGap > 0.35 && lateralGap < 2.5
            val blindSpot = sideLane && other.y + other.lengthM / 2 > ego.y - ego.lengthM / 2 - 8 &&
                other.y - other.lengthM / 2 < ego.y + ego.lengthM / 2 - 1
            val sideOf = if (dx < 0) "LEFT" else "RIGHT"
            val conflict = blindSpot && egoTowards == sideOf
            // 방향지시등(차량 자신의 진행 방향 기준). 자차 쪽으로 켬 = 오른쪽 차로 차는 LEFT, 왼쪽 차로 차는 RIGHT
            val sig = other.indicator
            val sigToward = sideLane && ahead && dy < 35 && sig == (if (dx > 0) "LEFT" else "RIGHT")
            val noSignal = (cutIn || intruding) && sig == "OFF"
            val mismatch = (sig == "LEFT" && other.vx > 0.35) || (sig == "RIGHT" && other.vx < -0.35)
            val braking = ahead && samePath && decel(other) <= BRAKE_MPS2
            val chain = other.id == chainLead
            // 0m: 두 차량 상자가 겹침 — 실제 충돌이거나 추적 오류. 어느 쪽이든 위험으로 두고 원인을 따로 표시
            val overlap = lateralGap == 0.0 && lengthGap == 0.0
            val headwayM = max(6.0, ego.vy * 1.0)

            val level = when {
                overlap -> RiskLevel.WARNING
                conflict -> RiskLevel.WARNING
                cutIn && lengthGap <= 9 && lateralGap <= 0.8 -> RiskLevel.WARNING
                cutIn && lengthGap <= 30 -> RiskLevel.WATCH
                samePath && ahead && (lengthGap <= 6 || (ttc != null && ttc <= 1.5 && lengthGap < 35)) -> RiskLevel.WARNING
                samePath && !ahead && (lengthGap <= 3 || (closing > 0.5 && (lengthGap <= 5 || (ttc != null && ttc <= 1.5)))) -> RiskLevel.WARNING
                samePath && ahead && (lengthGap < headwayM || (ttc != null && ttc <= 3.0) ||
                    (braking && lengthGap <= max(45.0, ego.vy * 4)) || chain) -> RiskLevel.WATCH
                samePath && !ahead && (lengthGap <= 8 || (closing > 0.5 && (lengthGap <= 15 || (ttc != null && ttc <= 3.0)))) -> RiskLevel.WATCH
                // 사각지대에 있기만 한 차는 등급을 올리지 않는다(실차 BSM 처럼 정보 표시). 자차가 그쪽으로 바꾸려 할 때만 위 conflict 로 경고
                sigToward && lengthGap <= 20 -> RiskLevel.WATCH
                else -> RiskLevel.NORMAL
            }
            val reasons = buildList {
                if (overlap) add("OVERLAP")
                if (conflict) add("LANE_CHANGE_CONFLICT")
                if (cutIn) add("CUT_IN")
                if (intruding) add("LANE_INTRUSION")
                if (sigToward && !cutIn && !intruding) add("SIGNAL_TOWARD_EGO")
                if (blindSpot) add(if (sideOf == "LEFT") "BLIND_SPOT_LEFT" else "BLIND_SPOT_RIGHT")
                if (chain) add("CHAIN_DECEL")
                if (braking) add("SUDDEN_BRAKE")
                if (noSignal) add("NO_SIGNAL")
                if (mismatch) add("SIGNAL_MISMATCH")
                if (level != RiskLevel.NORMAL && samePath && closing > 0.2) add(if (ahead) "FRONT_CLOSING" else "REAR_CLOSING")
                if (level != RiskLevel.NORMAL && isEmpty()) add("CLOSE_GAP")
            }
            RiskObject(other.id, level, reasons, ttc)
        }.toList()
        return RiskFrame("1.0", frame.timestampMs, objects)
    }

    /** 자차가 옮기려는 쪽: 방향지시등이 우선, 없으면 실제 가로 이동(0.3 m/s 초과). 진행 방향(+y) 기준 왼쪽 = -x. */
    fun egoMovingTo(ego: VehicleState): String? = when {
        ego.indicator == "LEFT" || ego.indicator == "RIGHT" -> ego.indicator
        ego.vx < -0.3 -> "LEFT"
        ego.vx > 0.3 -> "RIGHT"
        else -> null
    }

    enum class LaneChangeLevel(val label: String) { SAFE("변경 가능"), CAUTION("추가 확인 필요"), WARNING("변경 위험"), DANGER("충돌 위험 높음") }

    data class LaneChangeCheck(
        val side: String, val level: LaneChangeLevel,
        val frontId: String?, val frontGapM: Double?, val rearId: String?, val rearGapM: Double?,
        val ttcS: Double?, val reasons: List<String>,
    )

    /**
     * 자차 차선 변경 안전성 (시뮬레이션 기반 추정, 실제 안전 보장 아님).
     * 자차가 방향지시등을 켰거나 옆으로 움직일 때만 판정. 목표 차로의 옆·앞·뒤 차량, 간격, 접근속도,
     * 차선 변경에 걸리는 시간(3초) 뒤 남는 간격을 본다. 방향지시등을 켰다는 이유만으로 안전하다고 보지 않는다.
     */
    fun laneChangeCheck(frame: VehicleFrame, laneWidthM: Double = 3.6): LaneChangeCheck? {
        // shortcut: 차로 폭을 3.6m 로 가정 (계약 v1 에 차선 형상이 없음). 차선 형상이 들어오면 그 값으로 교체
        val ego = frame.vehicles.firstOrNull { it.id == frame.egoId }?.takeIf { valid(it) } ?: return null
        val side = egoMovingTo(ego) ?: return null
        val tx = ego.x + if (side == "LEFT") -laneWidthM else laneWidthM
        val inTarget = frame.vehicles.filter { it.id != ego.id && valid(it) && abs(it.x - tx) < laneWidthM / 2 }
        val egoFront = ego.y + ego.lengthM / 2
        val egoRear = ego.y - ego.lengthM / 2
        val alongside = inTarget.filter { it.y - it.lengthM / 2 < egoFront && it.y + it.lengthM / 2 > egoRear }
        val front = inTarget.filter { it.y - it.lengthM / 2 >= egoFront }.minByOrNull { it.y }
        val rear = inTarget.filter { it.y + it.lengthM / 2 <= egoRear }.maxByOrNull { it.y }
        val fGap = front?.let { it.y - it.lengthM / 2 - egoFront }
        val rGap = rear?.let { egoRear - (it.y + it.lengthM / 2) }
        val fClose = front?.let { ego.vy - it.vy }   // > 0: 자차가 앞차에 다가감
        val rClose = rear?.let { it.vy - ego.vy }    // > 0: 뒤차가 자차에 다가옴
        val fTtc = if (fGap != null && fClose != null && fClose > 0.2) fGap / fClose else null
        val rTtc = if (rGap != null && rClose != null && rClose > 0.2) rGap / rClose else null
        val ttc = listOfNotNull(fTtc, rTtc).minOrNull()
        val changeS = 3.0
        val fAfter = fGap?.let { it - maxOf(0.0, fClose ?: 0.0) * changeS }
        val rAfter = rGap?.let { it - maxOf(0.0, rClose ?: 0.0) * changeS }
        val level = when {
            alongside.isNotEmpty() || (ttc != null && ttc < 1.5) || (fAfter != null && fAfter < 2) || (rAfter != null && rAfter < 2) -> LaneChangeLevel.DANGER
            (ttc != null && ttc < 3) || (fGap != null && fGap < max(8.0, 0.8 * ego.vy)) || (rGap != null && rGap < max(8.0, 0.8 * (rear?.vy ?: 0.0))) -> LaneChangeLevel.WARNING
            (ttc != null && ttc < 5) || (fGap != null && fGap < 1.5 * ego.vy) || (rGap != null && rGap < 1.5 * (rear?.vy ?: 0.0)) -> LaneChangeLevel.CAUTION
            else -> LaneChangeLevel.SAFE
        }
        val reasons = buildList {
            alongside.forEach { add("${it.id} 가 목표 차로 옆(사각지대)에 있음") }
            if (front != null) add("앞 ${front.id} 간격 %.0fm · %s".format(fGap, if ((fClose ?: 0.0) > 0.2) "접근 %.1fm/s".format(fClose) else "멀어지거나 같은 속도"))
            else add("목표 차로 앞 차량 없음")
            if (rear != null) add("뒤 ${rear.id} 간격 %.0fm · %s".format(rGap, if ((rClose ?: 0.0) > 0.2) "접근 %.1fm/s".format(rClose) else "멀어지거나 같은 속도"))
            else add("목표 차로 뒤 차량 없음")
            if (fAfter != null || rAfter != null) add("3초 변경 후 남는 간격 앞 %s · 뒤 %s".format(fAfter?.let { "%.0fm".format(it) } ?: "-", rAfter?.let { "%.0fm".format(it) } ?: "-"))
        }
        return LaneChangeCheck(side, level, front?.id, fGap, rear?.id, rGap, ttc, reasons)
    }

    /** 차량 기동 예측(등속·등가속 운동 기준): 직진 / 좌·우 차선 변경 / 감속 / 급제동. 진행 방향(+y) 기준 왼쪽 = -x. */
    fun maneuver(v: VehicleState, previous: VehicleState?, dtS: Double): String {
        val a = if (previous != null && dtS > 0) (v.vy - previous.vy) / dtS else 0.0
        return when {
            a <= -4.0 -> "급제동"
            a <= -1.5 -> "감속"
            v.vx < -0.35 -> "좌측 차선 변경"
            v.vx > 0.35 -> "우측 차선 변경"
            else -> "직진"
        }
    }

    private val REASON_URGENCY = listOf("OVERLAP", "LANE_CHANGE_CONFLICT", "SUDDEN_BRAKE", "LANE_INTRUSION", "CUT_IN", "CHAIN_DECEL",
        "REAR_CLOSING", "FRONT_CLOSING", "BLIND_SPOT_LEFT", "BLIND_SPOT_RIGHT", "SIGNAL_TOWARD_EGO")
    /** 여러 위험이 동시에 있을 때 표시 순서: 등급 → TTC 짧은 순 → 원인 긴급도. */
    val urgency: Comparator<RiskObject> = compareByDescending<RiskObject> { rank(it.riskLevel) }
        .thenBy { it.ttcS ?: Double.MAX_VALUE }
        .thenBy { o -> o.reasonCodes.map { REASON_URGENCY.indexOf(it).takeIf { i -> i >= 0 } ?: 99 }.minOrNull() ?: 99 }

    /** 각 차량이 처음 급감속(SUDDEN_BRAKE)으로 판정된 시각 순서(재생 위치까지). 연쇄 감속 순서 표시용. */
    fun decelOrder(assessments: Map<Long, RiskFrame>, upToMs: Long): List<Pair<String, Long>> =
        assessments.entries.filter { it.key <= upToMs }.sortedBy { it.key }
            .flatMap { (ts, f) -> f.riskObjects.filter { "SUDDEN_BRAKE" in it.reasonCodes }.map { it.vehicleId to ts } }
            .distinctBy { it.first }

    fun clearanceM(ego: VehicleState, other: VehicleState): Double {
        val lateral = max(0.0, abs(other.x - ego.x) - (other.widthM + ego.widthM) / 2)
        val longitudinal = max(0.0, abs(other.y - ego.y) - (other.lengthM + ego.lengthM) / 2)
        return hypot(lateral, longitudinal)
    }

    private fun valid(v: VehicleState) = listOf(v.x, v.y, v.vx, v.vy, v.headingRad, v.lengthM, v.widthM)
        .all { it.isFinite() } && v.lengthM in 1.0..30.0 && v.widthM in 0.5..5.0
}
