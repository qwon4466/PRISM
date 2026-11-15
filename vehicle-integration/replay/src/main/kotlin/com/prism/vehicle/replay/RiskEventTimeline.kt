package com.prism.vehicle.replay

import kotlin.math.abs

/**
 * 위험 이벤트 타임라인: 재생 데이터(VehicleFrame)와 같은 판정(assessments)에서 사건을 시간순으로 뽑는다.
 * 방향지시등 · 차선 변경 의도 · 실제 측면 이동 · 차선 경계 통과 · 진입 완료를 서로 다른 사건으로 기록한다
 * (깜빡이를 켰다고 차선을 바꿨다고 보지 않는다).
 */
object RiskEventTimeline {
    enum class Kind(val label: String) {
        FIRST_DETECTED("최초 감지"), SIGNAL_ON("방향지시등 켬"), LANE_CHANGE_INTENT("차선 변경 의도"),
        LATERAL_MOVE_START("측면 이동 시작"), LANE_BOUNDARY_CROSSED("차선 경계 통과"), LANE_ENTRY_DONE("차선 진입 완료"),
        SIGNAL_OFF("방향지시등 끔"), LEVEL_UP("위험 단계 상승"), RESOLVED("위험 해소"), SCENARIO_END("시나리오 완료"),
    }

    data class Event(val tMs: Long, val vehicleId: String?, val kind: Kind, val detail: String = "")

    private const val MOVE_MPS = 0.3

    fun build(t: ScenarioTimeline, assessments: Map<Long, RiskFrame>): List<Event> {
        val out = mutableListOf<Event>()
        val sig = HashMap<String, String?>()
        val moving = HashMap<String, Boolean>()
        val lane = HashMap<String, String?>()
        val crossed = HashSet<String>()
        val level = HashMap<String, RiskLevel>()
        val intent = HashSet<String>()
        for (f in t.vehicleFrames) {
            val ts = f.timestampMs
            val byId = assessments[ts]?.riskObjects?.associateBy { it.vehicleId }.orEmpty()
            for (v in f.vehicles) {
                val id = v.id
                // 방향지시등 (값이 있는 데이터만. null 은 '확인되지 않음' 이라 사건을 만들지 않음)
                v.indicator?.let { now ->
                    val was = sig[id]
                    if (id in sig && was != now) {
                        if (now == "LEFT" || now == "RIGHT" || now == "HAZARD") out += Event(ts, id, Kind.SIGNAL_ON, side(now))
                        else if (was == "LEFT" || was == "RIGHT" || was == "HAZARD") out += Event(ts, id, Kind.SIGNAL_OFF)
                    }
                    sig[id] = now
                }
                // 실제 측면 이동 · 차선 경계 · 진입 완료
                val isMoving = abs(v.vx) > MOVE_MPS
                val wasMoving = moving[id] ?: false
                if (isMoving && !wasMoving) out += Event(ts, id, Kind.LATERAL_MOVE_START, if (v.vx < 0) "왼쪽" else "오른쪽")
                if (id in lane && lane[id] != v.laneId && v.laneId != null) { out += Event(ts, id, Kind.LANE_BOUNDARY_CROSSED, "${lane[id]} → ${v.laneId}"); crossed += id }
                if (!isMoving && wasMoving && id in crossed) { out += Event(ts, id, Kind.LANE_ENTRY_DONE, v.laneId ?: ""); crossed -= id }
                moving[id] = isMoving
                lane[id] = v.laneId
                // 위험 판정 변화 (자차는 판정 대상이 아님)
                val o = byId[id] ?: continue
                if (id !in intent && ("CUT_IN" in o.reasonCodes || "SIGNAL_TOWARD_EGO" in o.reasonCodes)) {
                    intent += id; out += Event(ts, id, Kind.LANE_CHANGE_INTENT, if ("CUT_IN" in o.reasonCodes) "자차 쪽으로 측면 이동" else "자차 쪽 방향지시등")
                }
                val prev = level[id] ?: RiskLevel.NORMAL
                val now = o.riskLevel
                if (rank(now) > rank(prev)) out += Event(ts, id, if (rank(prev) == 0) Kind.FIRST_DETECTED else Kind.LEVEL_UP, levelText(now) + (o.reasonCodes.firstOrNull()?.let { " · $it" } ?: ""))
                if (rank(now) == 0 && rank(prev) > 0 && now == RiskLevel.NORMAL) out += Event(ts, id, Kind.RESOLVED)
                level[id] = now
            }
        }
        out += Event(t.durationMs, null, Kind.SCENARIO_END)
        return out.sortedBy { it.tMs }
    }

    private fun rank(l: RiskLevel) = when (l) { RiskLevel.WARNING -> 2; RiskLevel.WATCH -> 1; else -> 0 }
    private fun levelText(l: RiskLevel) = when (l) { RiskLevel.WARNING -> "위험"; RiskLevel.WATCH -> "주의"; else -> l.name }
    private fun side(s: String) = when (s) { "LEFT" -> "왼쪽"; "RIGHT" -> "오른쪽"; else -> "비상등" }
}
