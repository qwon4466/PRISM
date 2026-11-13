package com.prism.vehicle.replay

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Offline scenario display heuristic. This is not Engine 02 or a live ADAS assessment.
 * Positions are ego-local metres (x right, y ahead); velocities are in m/s.
 */
object DemoRiskAnalyzer {
    fun analyze(timeline: ScenarioTimeline): Map<Long, RiskFrame> = buildMap {
        timeline.vehicleFrames.forEachIndexed { index, frame ->
            val previous = timeline.vehicleFrames.getOrNull((index - 5).coerceAtLeast(0))
                ?.takeIf { frame.timestampMs - it.timestampMs in 100..1_000 }
            val rawUnknown = timeline.snapshotAt(frame.timestampMs).risk?.riskObjects
                ?.filter { it.riskLevel == RiskLevel.UNKNOWN }?.map { it.vehicleId }?.toSet().orEmpty()
            put(frame.timestampMs, assess(frame, previous, rawUnknown))
        }
    }

    fun assess(frame: VehicleFrame, previous: VehicleFrame? = null, unknownIds: Set<String> = emptySet()): RiskFrame {
        val ego = frame.vehicles.firstOrNull { it.id == frame.egoId }
        if (ego == null) return RiskFrame("1.0", frame.timestampMs, emptyList())
        val previousById = previous?.vehicles?.associateBy { it.id }.orEmpty()
        val dt = previous?.let { (frame.timestampMs - it.timestampMs) / 1000.0 } ?: 0.0
        val objects = frame.vehicles.asSequence().filter { it.id != ego.id }.map { other ->
            if (other.id in unknownIds || !valid(other) || !valid(ego)) {
                return@map RiskObject(other.id, RiskLevel.UNKNOWN, listOf("UNRELIABLE_OBSERVATION"))
            }
            val dx = other.x - ego.x
            val dy = other.y - ego.y
            val lateralGap = max(0.0, abs(dx) - (other.widthM + ego.widthM) / 2)
            val lengthGap = max(0.0, abs(dy) - (other.lengthM + ego.lengthM) / 2)
            val samePath = lateralGap <= 0.35
            val ahead = dy >= 0
            val closing = if (ahead) ego.vy - other.vy else other.vy - ego.vy
            val ttc = if (samePath && closing > 0.2 && lengthGap > 0) lengthGap / closing else null
            val towardEgo = if (dx > 0) ego.vx - other.vx else other.vx - ego.vx
            val cutIn = ahead && dy < 35 && lateralGap > 0.35 && towardEgo > 0.35 &&
                lateralGap / towardEgo <= 2.5
            val was = previousById[other.id]
            val deceleration = if (dt > 0 && was != null) (other.vy - was.vy) / dt else 0.0
            val braking = ahead && samePath && deceleration <= -2.5

            val level = when {
                cutIn && lengthGap <= 9 && lateralGap <= 0.8 -> RiskLevel.WARNING
                cutIn && lengthGap <= 30 -> RiskLevel.WATCH
                samePath && ahead && (lengthGap <= 6 || (ttc != null && ttc <= 1.5 && lengthGap < 35)) -> RiskLevel.WARNING
                samePath && !ahead && (lengthGap <= 3 || (closing > 0.5 && (lengthGap <= 5 || (ttc != null && ttc <= 1.5)))) -> RiskLevel.WARNING
                samePath && ahead && (lengthGap <= 16 || (ttc != null && ttc <= 3.0) || (braking && lengthGap <= 45)) -> RiskLevel.WATCH
                samePath && !ahead && (lengthGap <= 8 || (closing > 0.5 && (lengthGap <= 15 || (ttc != null && ttc <= 3.0)))) -> RiskLevel.WATCH
                else -> RiskLevel.NORMAL
            }
            val reasons = buildList {
                if (cutIn) add("CUT_IN")
                if (braking) add("SUDDEN_BRAKE")
                if (level != RiskLevel.NORMAL && samePath) add(if (ahead) "FRONT_CLOSING" else "REAR_CLOSING")
                if (level != RiskLevel.NORMAL && isEmpty()) add("CLOSE_GAP")
            }
            RiskObject(other.id, level, reasons, ttc)
        }.toList()
        return RiskFrame("1.0", frame.timestampMs, objects)
    }

    fun clearanceM(ego: VehicleState, other: VehicleState): Double {
        val lateral = max(0.0, abs(other.x - ego.x) - (other.widthM + ego.widthM) / 2)
        val longitudinal = max(0.0, abs(other.y - ego.y) - (other.lengthM + ego.lengthM) / 2)
        return hypot(lateral, longitudinal)
    }

    private fun valid(v: VehicleState) = listOf(v.x, v.y, v.vx, v.vy, v.headingRad, v.lengthM, v.widthM)
        .all { it.isFinite() } && v.lengthM in 1.0..30.0 && v.widthM in 0.5..5.0
}
