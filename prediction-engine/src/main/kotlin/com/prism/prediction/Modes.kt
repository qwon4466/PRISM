package com.prism.prediction

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** 차선 변경 경로의 최대 평균 기울기(횡이동 m / 전진 m). 전진 없이 옆으로만 움직이는(대각선으로 파고드는) 경로를 막는다. */
private const val MAX_LANE_CHANGE_SLOPE = 0.2
/** 이보다 느린 횡속도(m/s)는 차선 변경 중이 아니라 차선 안의 흔들림으로 본다. */
private const val LATERAL_MOVING_MPS = 0.3

object Modes {
    private fun wrapToPi(a: Double): Double {
        val m = (a + PI) % (2 * PI)
        return (if (m < 0) m + 2 * PI else m) - PI
    }

    /** 도로 축(왼쪽 +) 기준 현재 횡위치·횡속도와, 차선 유지 시 도착할 차선 중앙. */
    private class Lateral(val pos: Double, val vLat: Double, val vLong: Double, val slope0: Double, val keepTarget: Double)

    private fun lateralState(current: VehicleState, config: PredictionConfig): Lateral {
        val road = config.roadHeadingRad
        val ux = cos(road); val uy = sin(road)
        val pos = current.x * -uy + current.y * ux
        val vLong = current.vx * ux + current.vy * uy
        val vLat = current.vx * -uy + current.vy * ux
        val w = config.laneWidthM
        // shortcut: 차선 중앙을 원점(자차)을 지나는 차선 폭 격자로 본다(ego_local_m 에서 자차는 차선 중앙). 자차가 차선을 바꾸는 중이면 어긋난다 → 계약에 차선 중심이 생기면 그것을 쓴다.
        val nearest = Math.round(pos / w) * w
        val keepTarget = if (abs(vLat) < LATERAL_MOVING_MPS || (nearest - pos) * vLat > 0) nearest else nearest + sign(vLat) * w
        val slope0 = if (vLong > config.stationarySpeedMps) (vLat / vLong).coerceIn(-1.0, 1.0) else 0.0
        return Lateral(pos, vLat, max(vLong, 0.0), slope0, keepTarget)
    }

    /**
     * 전진 거리 s(m) 에서의 횡위치와 경로 기울기(횡/종).
     * 남은 횡이동을 전진 거리 D 동안 3차 곡선(시작 기울기 = 현재 횡속도/종속도, 끝 기울기 0)으로 옮기고, 목표 차선 중앙을 넘지 않는다.
     * D 는 시간이 아니라 거리라서, 느려지거나 멈추는 차량은 옆으로도 거의 움직이지 않는다.
     */
    private fun lateralAt(s: Double, lat: Lateral, to: Double, config: PredictionConfig): Pair<Double, Double> {
        val delta = to - lat.pos
        if (abs(delta) < 1e-9) return lat.pos to 0.0
        val w = config.laneWidthM
        val d = max(lat.vLong * config.laneChangeDurationS, w / MAX_LANE_CHANGE_SLOPE) * abs(delta) / w
        val u = (s / d).coerceIn(0.0, 1.0)
        val raw = lat.pos + delta * (3 * u * u - 2 * u * u * u) + lat.slope0 * d * (u - 2 * u * u + u * u * u)
        val p = raw.coerceIn(min(lat.pos, to), max(lat.pos, to))
        val slope = if (u >= 1.0 || p != raw) 0.0 else delta * (6 * u - 6 * u * u) / d + lat.slope0 * (1 - 4 * u + 3 * u * u)
        return p to slope
    }

    /**
     * 차선 유지 예측(도로 축 기준).
     * - 종방향: 등가속. 감속 중이면 속도 0 에서 멈춘다(등가속 외삽이 후진으로 넘어가지 않게). 최대 속도에서 더 가속하지 않는다.
     * - 횡방향: 차선 변경 중이면 진입 중인 차선 중앙까지, 아니면 지금 차선 중앙으로 전진 거리에 따라 부드럽게 정렬([lateralAt]).
     *   (차선 변경 중인 차량의 경로가 여러 차선을 가로질러 뻗지 않는다)
     * 도로 방향과 45° 이상 어긋나게 움직이거나 실제로 후진 중인 차량은 null → 기존 등가속 예측을 쓴다.
     */
    private fun laneBounded(
        observations: List<TimedVehicle>,
        current: VehicleState,
        config: PredictionConfig,
    ): List<TrajectoryPoint>? {
        val road = config.roadHeadingRad
        val ux = cos(road); val uy = sin(road)   // 도로 전방
        val lx = -uy; val ly = ux                 // 도로 왼쪽
        val speed = hypot(current.vx, current.vy)
        if (speed >= config.stationarySpeedMps && abs(wrapToPi(atan2(current.vy, current.vx) - road)) > PI / 4) return null
        val vLong = (current.vx * ux + current.vy * uy).coerceAtMost(config.maxSpeedMps)
        if (vLong < 0.0) return null
        val lateral = lateralState(current, config)
        val aLong = Baseline.estimateAcceleration(observations, config)?.let { it.first * ux + it.second * uy } ?: 0.0
        return config.horizonsMs.map { dtMs ->
            val t = dtMs / 1000.0
            val (s, v) = when {
                aLong < 0.0 -> {
                    val tt = minOf(t, vLong / -aLong)
                    Pair(vLong * tt + 0.5 * aLong * tt * tt, vLong + aLong * tt)
                }
                aLong > 0.0 && vLong < config.maxSpeedMps -> {
                    val tt = minOf(t, (config.maxSpeedMps - vLong) / aLong)
                    Pair(vLong * tt + 0.5 * aLong * tt * tt + config.maxSpeedMps * (t - tt), vLong + aLong * tt)
                }
                else -> Pair(vLong * t, vLong)
            }
            val (p, slope) = lateralAt(s, lateral, lateral.keepTarget, config)
            val lat = p - lateral.pos
            val heading = if (v < config.stationarySpeedMps) current.headingRad else road + atan(slope)
            TrajectoryPoint(dtMs, current.x + ux * s + lx * lat, current.y + uy * s + ly * lat, heading)
        }
    }

    private fun laneKeepWithCurvature(
        base: List<TrajectoryPoint>,
        current: VehicleState,
        config: PredictionConfig,
    ): List<TrajectoryPoint> {
        val curvature = config.roadCurvaturePerM
        if (kotlin.math.abs(curvature) < 1e-12) return base
        val speed = hypot(current.vx, current.vy)
        val initialHeading = if (speed < config.stationarySpeedMps) {
            current.headingRad
        } else {
            atan2(current.vy, current.vx)
        }
        return base.map { point ->
            val distance = hypot(point.x - current.x, point.y - current.y)
            val endHeading = initialHeading + curvature * distance
            point.copy(
                x = current.x + (sin(endHeading) - sin(initialHeading)) / curvature,
                y = current.y - (cos(endHeading) - cos(initialHeading)) / curvature,
                headingRad = endHeading,
            )
        }
    }

    private fun braking(current: VehicleState, config: PredictionConfig): List<TrajectoryPoint> {
        val rawSpeed = hypot(current.vx, current.vy)
        val road = config.roadHeadingRad
        if (rawSpeed < config.stationarySpeedMps || abs(wrapToPi(atan2(current.vy, current.vx) - road)) <= PI / 4) {
            // 도로를 따라 감속: 차선 변경 중이면 진입 중인 차선 중앙까지만 옆으로 간다(속도 방향 직선이면 목표 차선을 지나친다)
            val ux = cos(road); val uy = sin(road)
            val lateral = lateralState(current, config)
            val v0 = lateral.vLong.coerceAtMost(config.maxSpeedMps)
            val stopTime = v0 / config.brakingMps2
            return config.horizonsMs.map { dtMs ->
                val mt = (dtMs / 1000.0).coerceAtMost(stopTime)
                val s = v0 * mt - 0.5 * config.brakingMps2 * mt * mt
                val (p, slope) = lateralAt(s, lateral, lateral.keepTarget, config)
                val lat = p - lateral.pos
                val heading = if (mt < stopTime) road + atan(slope) else current.headingRad
                TrajectoryPoint(dtMs, current.x + ux * s - uy * lat, current.y + uy * s + ux * lat, heading)
            }
        }
        val speed = rawSpeed.coerceAtMost(config.maxSpeedMps)
        val direction = if (speed < config.stationarySpeedMps || rawSpeed == 0.0) {
            Pair(0.0, 0.0)
        } else {
            Pair(current.vx / rawSpeed, current.vy / rawSpeed)
        }
        val stopTime = speed / config.brakingMps2
        return config.horizonsMs.map { dtMs ->
            val movingTime = (dtMs / 1000.0).coerceAtMost(stopTime)
            val distance = speed * movingTime - 0.5 * config.brakingMps2 * movingTime * movingTime
            TrajectoryPoint(
                dtMs,
                current.x + direction.first * distance,
                current.y + direction.second * distance,
                current.headingRad,
            )
        }
    }

    /**
     * 차선 유지 경로에 옆 차선 중앙까지의 횡이동을 더한다(direction 1 = 왼쪽, -1 = 오른쪽).
     * 이미 그 방향으로 차선 변경 중이면 진입 중인 차선이 목표(차선 유지와 같음), 반대 방향이면 원래 차선 복귀(중단)다.
     * 횡이동은 전진 거리에 따라 진행하므로 멈춘 차량의 경로가 옆으로 눕지 않고, 목표 차선 중앙을 지나치지 않는다.
     */
    private fun laneChange(
        laneKeep: List<TrajectoryPoint>,
        current: VehicleState,
        config: PredictionConfig,
        direction: Int,
    ): List<TrajectoryPoint> {
        val ux = cos(config.roadHeadingRad); val uy = sin(config.roadHeadingRad)
        val lateral = lateralState(current, config)
        val alreadyMoving = abs(lateral.vLat) >= LATERAL_MOVING_MPS && sign(lateral.vLat).toInt() == direction
        val target = if (alreadyMoving) lateral.keepTarget else lateral.keepTarget + direction * config.laneWidthM
        return laneKeep.map { point ->
            val s = max((point.x - current.x) * ux + (point.y - current.y) * uy, 0.0)
            val (pT, slopeT) = lateralAt(s, lateral, target, config)
            val (pK, slopeK) = lateralAt(s, lateral, lateral.keepTarget, config)
            val offset = pT - pK
            point.copy(
                x = point.x - uy * offset,
                y = point.y + ux * offset,
                headingRad = point.headingRad + atan(slopeT) - atan(slopeK),
            )
        }
    }

    fun candidates(
        observations: List<TimedVehicle>,
        config: PredictionConfig = PredictionConfig(),
    ): List<CandidateTrajectory> {
        require(observations.isNotEmpty()) { "at least one observation is required" }
        val current = observations.last().vehicle
        val base = laneBounded(observations, current, config) ?: Baseline.constantAcceleration(observations, config)
        val laneKeep = laneKeepWithCurvature(base, current, config)
        return listOf(
            CandidateTrajectory("lane_keep", null, laneKeep),
            CandidateTrajectory("braking", null, braking(current, config)),
            CandidateTrajectory("lane_change_left", null, laneChange(laneKeep, current, config, 1)),
            CandidateTrajectory("lane_change_right", null, laneChange(laneKeep, current, config, -1)),
        )
    }
}
