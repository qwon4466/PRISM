package com.prism.vehicle.replay.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign

/** Deterministic integration fixtures. Future points copy scripted truth; risk levels are scripted. */
@OptIn(ExperimentalSerializationApi::class)
object MockScenarioGenerator {
    private const val STEP_MS = 100
    private const val DT = 0.1
    private val lanes = linkedMapOf("L1" to -3.6, "L2" to 0.0, "L3" to 3.6)
    private const val PROVENANCE = "MOCK: vehicle-integration/replay/src/test/kotlin/com/prism/vehicle/replay/tools/MockScenarioGenerator.kt (03/01/02 엔진 산출물 아님)"
    private val compact = Json
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** 차선 변경. signal=true 면 시작 signalLead 초 전부터 끝난 뒤 0.3초까지 이동 방향 방향지시등(차량 진행 방향 기준 좌/우). */
    private data class LaneMove(val start: Double, val lane: String, val duration: Double, val signalLead: Double = 1.5, val signal: Boolean = true)
    /** 차선 변경 없이 켜는 방향지시등 구간(깜빡이만 켜고 이동하지 않는 차량 테스트용). */
    private data class Signal(val start: Double, val end: Double, val side: String)
    private data class RiskPeriod(val id: String, val start: Int, val end: Int, val level: String, val reasons: List<String>)
    private data class Car(
        val id: String, val lane: String, var y: Double, var v: Double,
        val length: Double = 4.6, val width: Double = 1.8,
        val acceleration: (Double) -> Double = { 0.0 },
        val moves: List<LaneMove> = emptyList(), val follow: Boolean = false,
        val followAfter: Double = 0.0, // 이 시각(초) 전에는 앞차에 반응하지 않음(운전자 반응 지연)
        val signals: List<Signal> = emptyList(),
    ) {
        var x = lanes.getValue(lane)
        var a = 0.0
        var vx = 0.0
        var targetX = x
        var laneChangeRate = 0.0
        fun laneId(): String = lanes.minBy { abs(it.value - x) }.key
    }
    private data class State(val x: Double, val y: Double, val vx: Double, val v: Double,
                             val length: Double, val width: Double, val lane: String)
    private data class Scenario(val manifest: Map<String, Any?>, val vehicles: List<Map<String, Any?>>,
                                val predictions: List<Map<String, Any?>>, val risks: List<Map<String, Any?>>)

    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of("").toAbsolutePath()
        val output = if (args.isNotEmpty()) Path.of(args[0]) else root.resolve("app/src/main/assets/scenarios")
        val fixtures = if (args.size > 1) Path.of(args[1]) else root.resolve("tests/fixtures/scenarios")
        Files.createDirectories(output)
        val scenarios = listOf(
            run(output, "normal", "정상 주행", listOf(
                ego(), car("car_01", "L2", 45.0, 25.0), car("car_02", "L1", 15.0, 26.0),
                car("car_03", "L3", -10.0, 24.0), car("car_04", "L1", -25.0, 25.0),
                car("car_05", "L3", 30.0, 25.0)), listOf(0 to "정상 주행, 5대 일정 속도"), emptyList()),
            // car_r 는 같은 차로 뒤에서 접근 → 좌측 차선으로 옮겨 자차 옆을 지나 앞으로 간다(추월).
            // 이전 값(6.5초에 차선 변경)은 car_r 가 4초쯤 자차를 그대로 관통해 '후방 0m → 전방 0m' 로 보였다.
            run(output, "rear_approach", "뒤차 급접근", listOf(
                ego(), car("car_r", "L2", -40.0, 33.0, moves = listOf(LaneMove(3.0, "L1", 2.0))),
                car("car_02", "L1", 25.0, 25.0), car("car_03", "L3", 10.0, 24.0),
                car("car_04", "L3", -20.0, 25.0), car("car_05", "L1", -45.0, 24.0)),
                listOf(0 to "후방 car_r 33 m/s 로 접근", 3000 to "car_r 좌측 차선으로 추월 시작",
                    5000 to "car_r 자차 좌측 통과", 6000 to "car_r 좌측 전방으로 이동"),
                listOf(risk("car_r", 1000, 2500, "WATCH", "REAR_CLOSING"),
                    risk("car_r", 2500, 4200, "WARNING", "REAR_CLOSING"),
                    risk("car_r", 4200, 5000, "WATCH", "LANE_CHANGE"))),
            // 자차는 앞차 급제동 1.2초 뒤(운전자 반응 시간)부터 감속한다. 그 전에는 같은 속도로 차간 1.0초 남짓을 유지.
            // 이전 값은 자차가 0초부터 이상적인 추종으로 감속해 급제동 전 속도가 먼저 떨어졌다.
            run(output, "sudden_brake", "앞차 급감속", listOf(
                ego().copy(followAfter = 4.2), car("car_01", "L2", 31.0, 25.0, acceleration = brake(3.0, 5.5, -7.0)),
                car("car_02", "L1", 10.0, 25.0), car("car_03", "L3", -5.0, 25.0),
                car("car_04", "L1", -25.0, 25.0), car("car_05", "L3", 35.0, 25.0)),
                listOf(3000 to "car_01 급감속 -7 m/s²", 5500 to "car_01 감속 종료"),
                listOf(risk("car_01", 3000, 3800, "WATCH", "HARD_BRAKING"),
                    risk("car_01", 3800, 6000, "WARNING", "HARD_BRAKING", "FRONT_CLOSING"),
                    risk("car_01", 6000, 7000, "WATCH", "FRONT_CLOSING")), onsetOf = listOf("ego")),
            run(output, "lane_change", "옆 차선 끼어들기", listOf(
                ego(), car("car_02", "L3", 14.0, 23.0, moves = listOf(LaneMove(3.0, "L2", 2.5, signal = false))),  // 방향지시등 없이 끼어들기
                car("car_01", "L2", 50.0, 25.0), car("car_03", "L1", 5.0, 25.0),
                car("car_04", "L1", -20.0, 25.0), car("car_05", "L3", -15.0, 25.0)),
                listOf(3000 to "car_02 자차 차선으로 차선 변경 시작", 5500 to "차선 변경 완료"),
                listOf(risk("car_02", 3000, 4000, "WATCH", "LANE_CHANGE_INTRUSION"),
                    risk("car_02", 4000, 5500, "WARNING", "LANE_CHANGE_INTRUSION", "PATH_OVERLAP"),
                    risk("car_02", 5500, 6500, "WATCH", "FRONT_CLOSING"))),
            // 화물차 → car_00 → car_01 → 자차 순으로 감속이 전파된다. 모두 추종 거리 밖에서 같은 속도로 시작해
            // 화물차보다 먼저 감속하는 차가 없다(이전 값은 자차·car_01 이 0초부터 감속했다).
            // 뒤차들의 감속 시작 시각 이벤트는 시뮬레이션 결과에서 계산한다(onsetOf).
            run(output, "chain_reaction", "화물차 급감속 연쇄 위험", listOf(
                ego(), car("car_01", "L2", 42.0, 25.0, follow = true),
                car("car_00", "L2", 84.0, 25.0, follow = true),
                car("truck", "L2", 130.0, 25.0, length = 12.0, width = 2.5, acceleration = brake(2.0, 4.5, -6.5)),
                car("car_02", "L1", 8.0, 25.0), car("car_03", "L3", 30.0, 25.0),
                car("car_04", "L1", -20.0, 25.0)),
                listOf(2000 to "truck 급감속 시작 -6.5 m/s²"),
                listOf(risk("truck", 2000, 4500, "WATCH", "HARD_BRAKING"),
                    risk("car_00", 2500, 5500, "WATCH", "CHAIN_DECEL"),
                    risk("car_01", 3000, 4000, "WATCH", "CHAIN_DECEL"),
                    risk("car_01", 4000, 6500, "WARNING", "CHAIN_DECEL", "FRONT_CLOSING")),
                onsetOf = listOf("car_00", "car_01", "ego")),
            run(output, "dropout", "센서 누락·노이즈", listOf(
                ego(), car("car_01", "L2", 35.0, 25.0, acceleration = brake(3.0, 4.5, -3.0)),
                car("car_02", "L1", 12.0, 25.0), car("car_03", "L3", 20.0, 24.0),
                car("car_04", "L1", -20.0, 25.0), car("car_05", "L3", -30.0, 25.0)),
                listOf(2000 to "car_03 센서 노이즈 (UNKNOWN)", 4000 to "전체 프레임 누락 4.0~5.5s",
                    7000 to "car_02 관측 누락 7.0~8.0s"),
                listOf(risk("car_03", 2000, 3000, "UNKNOWN", "SENSOR_DROPOUT"),
                    risk("car_01", 3000, 4000, "WATCH", "HARD_BRAKING")),
                dropFrames = listOf(4000 until 5500), dropVehicle = Triple("car_02", 7000, 8000)),
            // ── 방향지시등·사각지대 테스트 (같은 엔진·같은 계약) ──
            run(output, "cut_in_left_signal", "좌측 차량 끼어들기 (방향지시등 켬)", listOf(
                ego(), car("car_11", "L1", 16.0, 23.0, moves = listOf(LaneMove(3.0, "L2", 2.5))),
                car("car_01", "L2", 55.0, 25.0), car("car_03", "L3", 5.0, 25.0), car("car_04", "L3", -20.0, 25.0)),
                listOf(0 to "car_11 좌측 차선, 1.5초 뒤 오른쪽 방향지시등", 3000 to "car_11 자차 차선으로 이동 시작"),
                listOf(risk("car_11", 3000, 5500, "WARNING", "LANE_CHANGE_INTRUSION"))),
            run(output, "signal_no_change", "방향지시등만 켜고 차선 유지", listOf(
                ego(), car("car_12", "L3", 10.0, 25.0, signals = listOf(Signal(2.0, 8.0, "LEFT"))),
                car("car_01", "L2", 45.0, 25.0), car("car_04", "L1", -15.0, 25.0)),
                listOf(2000 to "car_12 왼쪽 방향지시등 켬 (차선은 유지)", 8000 to "car_12 방향지시등 끔"),
                listOf(risk("car_12", 2000, 8000, "WATCH", "SIGNAL_ONLY"))),
            run(output, "ego_lc_blind_left", "자차 좌측 차선 변경 · 좌측 사각지대 차량", listOf(
                ego().copy(follow = false, moves = listOf(LaneMove(6.0, "L1", 3.0, signalLead = 4.0))),
                car("car_bl", "L1", -20.0, 30.0), car("car_01", "L2", 45.0, 25.0), car("car_03", "L3", 0.0, 25.0)),
                listOf(2000 to "자차 왼쪽 방향지시등 켬", 2000 to "car_bl 좌측 사각지대", 6000 to "사각지대 해소 뒤 자차 좌측 차선 이동"),
                listOf(risk("car_bl", 2000, 4700, "WARNING", "BLIND_SPOT"))),
            run(output, "ego_lc_blind_right", "자차 우측 차선 변경 · 우측 사각지대 차량", listOf(
                ego().copy(follow = false, moves = listOf(LaneMove(6.0, "L3", 3.0, signalLead = 4.0))),
                car("car_br", "L3", -20.0, 30.0), car("car_01", "L2", 45.0, 25.0), car("car_02", "L1", 0.0, 25.0)),
                listOf(2000 to "자차 오른쪽 방향지시등 켬", 2000 to "car_br 우측 사각지대", 6000 to "사각지대 해소 뒤 자차 우측 차선 이동"),
                listOf(risk("car_br", 2000, 4700, "WARNING", "BLIND_SPOT"))),
            run(output, "brake_and_cut_in", "전방 급제동 + 우측 끼어들기 동시", listOf(
                ego().copy(followAfter = 4.2), car("car_01", "L2", 31.0, 25.0, acceleration = brake(3.0, 5.5, -7.0)),
                car("car_02", "L3", 12.0, 23.0, moves = listOf(LaneMove(3.2, "L2", 2.5))),
                car("car_04", "L1", -15.0, 25.0)),
                listOf(3000 to "car_01 급감속", 3200 to "car_02 자차 차선으로 끼어들기"),
                listOf(risk("car_01", 3000, 6000, "WARNING", "HARD_BRAKING"), risk("car_02", 3200, 5700, "WARNING", "LANE_CHANGE_INTRUSION")),
                onsetOf = listOf("ego")),
        )
        val indexPath = output.resolve("index.json")
        val originalIndex = if (Files.exists(indexPath)) Files.readString(indexPath)
            else Files.readString(root.resolve("app/src/main/assets/scenarios/index.json"))
        val kept = Json.parseToJsonElement(originalIndex).jsonArray.map { it.jsonPrimitive.content }
            .filterNot { it.startsWith("mock_") }
        Files.writeString(indexPath, encode(kept + scenarios.map { it.manifest.getValue("scenario_id") }))
        makeFixtures(fixtures, scenarios.first())
        for (scenario in scenarios) {
            val levels = scenario.risks.flatMap { it.getValue("risk_objects") as List<*> }
                .groupingBy { (it as Map<*, *>)["risk_level"] }.eachCount()
            println("${scenario.manifest.getValue("scenario_id")} frames=${scenario.vehicles.size} levels=$levels")
        }
    }

    private fun car(id: String, lane: String, y: Double, v: Double, length: Double = 4.6, width: Double = 1.8,
                    acceleration: (Double) -> Double = { 0.0 }, moves: List<LaneMove> = emptyList(), follow: Boolean = false,
                    signals: List<Signal> = emptyList()) =
        Car(id, lane, y, v, length, width, acceleration, moves, follow, signals = signals)
    private fun ego() = car("ego", "L2", 0.0, 25.0, follow = true)
    private fun brake(start: Double, end: Double, amount: Double): (Double) -> Double =
        { time -> if (time >= start && time < end) amount else 0.0 }
    private fun risk(id: String, start: Int, end: Int, level: String, vararg reasons: String) =
        RiskPeriod(id, start, end, level, reasons.toList())

    private fun step(cars: List<Car>, time: Double, cruise: Map<String, Double>) {
        for (car in cars) {
            var acceleration = car.acceleration(time)
            if (car.follow && time >= car.followAfter) {
                val lead = cars.filter { it !== car && abs(it.x - car.x) < 1.8 && it.y > car.y }
                    .minByOrNull { it.y }
                acceleration = 0.8 * (cruise.getOrDefault(car.id, 25.0) - car.v)
                if (lead != null) {
                    val gap = lead.y - car.y - (lead.length + car.length) / 2
                    val desired = 5 + 1.2 * car.v
                    if (gap < desired * 1.5) acceleration = min(acceleration,
                        0.6 * (gap - desired) + 1.2 * (lead.v - car.v))
                }
                acceleration = max(-7.0, min(1.5, acceleration))
            }
            car.a = acceleration
            for (move in car.moves) if (abs(time - move.start) < DT / 2) {
                car.targetX = lanes.getValue(move.lane)
                car.laneChangeRate = abs(car.targetX - car.x) / move.duration
            }
        }
        for (car in cars) {
            car.v = max(0.0, car.v + car.a * DT)
            val dx = car.targetX - car.x
            car.vx = if (abs(dx) > 1e-6) sign(dx) * min(abs(dx) / DT, car.laneChangeRate) else 0.0
            car.x += car.vx * DT
            car.y += car.v * DT
        }
    }

    private fun run(output: Path, id: String, title: String, cars: List<Car>, events: List<Pair<Int, String>>,
                    script: List<RiskPeriod>, cruise: Map<String, Double> = emptyMap(),
                    dropFrames: List<IntRange> = emptyList(), dropVehicle: Triple<String, Int, Int>? = null,
                    durationSeconds: Double = 10.0, onsetOf: List<String> = emptyList()): Scenario {
        val count = (durationSeconds / DT).toInt()
        val states = mutableListOf<Map<String, State>>()
        for (i in 0..count) {
            if (i > 0) step(cars, i * DT, cruise)
            states += cars.associate { it.id to State(it.x, it.y, it.vx, it.v, it.length, it.width, it.laneId()) }
        }
        // 감속 시작 = 처음으로 -2 m/s² 이하로 줄어든 프레임. 사람이 시각을 적지 않고 시뮬레이션 결과에서 뽑는다.
        val onsets = onsetOf.mapNotNull { vehicleId ->
            (1..count).firstOrNull { (states[it].getValue(vehicleId).v - states[it - 1].getValue(vehicleId).v) / DT <= -2.0 }
                ?.let { it * STEP_MS to "${if (vehicleId == "ego") "자차" else vehicleId} 감속 시작" }
        }
        val allEvents = (events + onsets).sortedBy { it.first }
        fun present(i: Int, vehicleId: String): Boolean {
            val ts = i * STEP_MS
            return dropVehicle == null || vehicleId != dropVehicle.first || ts !in dropVehicle.second until dropVehicle.third
        }
        // 방향지시등: 차량 진행 방향(+y) 기준 왼쪽 = -x. 목표 차선이 출발 차선보다 왼쪽이면 LEFT
        fun indicatorAt(car: Car, i: Int): String {
            val t = i * DT
            for (m in car.moves) if (m.signal && t >= m.start - m.signalLead - 1e-9 && t <= m.start + m.duration + 0.3 + 1e-9) {
                val originX = states[((m.start / DT).roundToInt() - 1).coerceIn(0, count)].getValue(car.id).x
                return if (lanes.getValue(m.lane) < originX) "LEFT" else "RIGHT"
            }
            return car.signals.firstOrNull { t >= it.start - 1e-9 && t < it.end - 1e-9 }?.side ?: "OFF"
        }
        val carById = cars.associateBy { it.id }
        fun local(source: Int, vehicleId: String, at: Int): Pair<Double, Double> {
            val egoState = states[source].getValue("ego")
            val vehicle = states[at].getValue(vehicleId)
            return Pair(vehicle.x - egoState.x, vehicle.y - egoState.y)
        }
        val vehicles = mutableListOf<Map<String, Any?>>()
        val predictions = mutableListOf<Map<String, Any?>>()
        val risks = mutableListOf<Map<String, Any?>>()
        for (i in 0..count) {
            val ts = i * STEP_MS
            if (dropFrames.any { ts in it }) continue
            val ids = states[i].keys.filter { present(i, it) }
            val observed = ids.map { vehicleId ->
                val state = states[i].getValue(vehicleId)
                val (x, y) = local(i, vehicleId, i)
                linkedMapOf<String, Any?>("id" to vehicleId, "x" to r3(x), "y" to r3(y),
                    "vx" to r3(state.vx), "vy" to r3(state.v),
                    // 자차 좌표계는 도로 방향에 맞춘다(03 계약: 자차 heading = π/2). 자차 차선 변경은 heading 이 아니라 vx 로 나타낸다
                    "heading_rad" to r3(if (vehicleId == "ego") PI / 2 else heading(state)), "length_m" to state.length,
                    "width_m" to state.width, "lane_id" to state.lane, "indicator" to indicatorAt(carById.getValue(vehicleId), i))
            }
            vehicles += linkedMapOf("schema_version" to "1.0", "timestamp_ms" to ts,
                "coordinate_system" to "ego_local_m", "ego_id" to "ego", "vehicles" to observed)
            val others = ids.filterNot { it == "ego" }
            val predicted = others.mapNotNull { vehicleId ->
                val points = (1..5).mapNotNull { k ->
                    val at = i + k * 1000 / STEP_MS
                    if (at > count) null else {
                        val (x, y) = local(i, vehicleId, at)
                        linkedMapOf<String, Any?>("dt_ms" to k * 1000, "x" to r3(x), "y" to r3(y),
                            "heading_rad" to r3(heading(states[at].getValue(vehicleId))))
                    }
                }
                if (points.isEmpty()) null else linkedMapOf<String, Any?>("vehicle_id" to vehicleId,
                    "trajectories" to listOf(linkedMapOf("mode" to "mock_future_truth",
                        "confidence" to null, "points" to points)))
            }
            if (predicted.isNotEmpty()) predictions += linkedMapOf("schema_version" to "1.0",
                "timestamp_ms" to ts, "predictions" to predicted)
            val objects = others.map { vehicleId ->
                val period = script.firstOrNull { it.id == vehicleId && ts >= it.start && ts < it.end }
                val level = period?.level ?: "NORMAL"
                val at = i + 1000 / STEP_MS
                val zones = if (level in listOf("WATCH", "WARNING") && at <= count) {
                    val (x, y) = local(i, vehicleId, at)
                    listOf(mapOf("dt_ms" to 1000,
                        "polygon" to box(x, y, 2.4, states[i].getValue(vehicleId).length + 2.4)))
                } else emptyList()
                linkedMapOf<String, Any?>("vehicle_id" to vehicleId, "risk_level" to level,
                    "reason_codes" to (period?.reasons ?: emptyList<String>()), "ttc_s" to null,
                    "risk_zones" to zones)
            }
            risks += linkedMapOf("schema_version" to "1.0", "timestamp_ms" to ts, "risk_objects" to objects)
        }
        val manifest = linkedMapOf<String, Any?>("scenario_id" to "mock_$id", "title" to "[MOCK] $title",
            "duration_ms" to count * STEP_MS, "step_ms" to STEP_MS, "seed" to 0, "ego_id" to "ego",
            "event_annotation" to allEvents.map { mapOf("t_ms" to it.first, "label" to it.second) },
            "provenance" to PROVENANCE, "mock" to true)
        val scenario = Scenario(manifest, vehicles, predictions, risks)
        write(output.resolve("mock_$id"), scenario)
        return scenario
    }

    private fun heading(state: State) = if (state.v != 0.0 || state.vx != 0.0) atan2(state.v, state.vx) else PI / 2
    private fun r3(value: Double) = round(value * 1000.0) / 1000.0
    private fun box(x: Double, y: Double, width: Double, length: Double) = listOf(
        mapOf("x" to r3(x - width / 2), "y" to r3(y - length / 2)),
        mapOf("x" to r3(x + width / 2), "y" to r3(y - length / 2)),
        mapOf("x" to r3(x + width / 2), "y" to r3(y + length / 2)),
        mapOf("x" to r3(x - width / 2), "y" to r3(y + length / 2)))

    @Suppress("UNCHECKED_CAST")
    private fun makeFixtures(fixtures: Path, normal: Scenario) {
        val m = normal.manifest + ("duration_ms" to 400)
        val v = normal.vehicles.take(5)
        val p = normal.predictions.take(5)
        val r = normal.risks.take(5)
        fun fixture(name: String, manifest: Map<String, Any?> = m,
                    vehicles: List<Map<String, Any?>>? = v, predictions: List<Map<String, Any?>>? = p,
                    risks: List<Map<String, Any?>>? = r, raw: Map<String, String> = emptyMap()) {
            val directory = fixtures.resolve(name)
            write(directory, Scenario(manifest, vehicles ?: emptyList(), predictions ?: emptyList(), risks ?: emptyList()),
                vehicles != null, predictions != null, risks != null)
            raw.forEach { (file, text) -> Files.writeString(directory.resolve(file), text) }
        }
        fixture("valid")
        fixture("missing_vehicle_file", vehicles = null)
        fixture("missing_prediction_file", predictions = null)
        fixture("bad_schema_version", vehicles = v.map { it + ("schema_version" to "2.0") })
        val firstRiskObjects = r[0].getValue("risk_objects") as List<*>
        val ghost = (firstRiskObjects[0] as Map<String, Any?>) + ("vehicle_id" to "ghost")
        fixture("unknown_vehicle_ref", risks = listOf(r[0] + ("risk_objects" to listOf(ghost))) + r.drop(1))
        val firstVehicles = v[0].getValue("vehicles") as List<*>
        fixture("ego_missing", vehicles = listOf(v[0] + ("vehicles" to firstVehicles.drop(1))) + v.drop(1))
        fixture("stale_prediction", predictions = p + (p[0] + ("timestamp_ms" to 9999)))
        val badRisk = r[0] + ("risk_objects" to (listOf((firstRiskObjects[0] as Map<String, Any?>) + ("risk_level" to "DANGER")) + firstRiskObjects.drop(1)))
        fixture("bad_risk_level", raw = mapOf("risk_frames.jsonl" to encode(badRisk) + "\n"))
        fixture("broken_json", raw = mapOf("vehicle_frames.jsonl" to encode(v[0]) + "\n{\"schema_version\": \"1.0\", \"timestamp_ms\": \n"))
        fixture("gap", vehicles = listOf(v[0], v[4]), predictions = listOf(p[0], p[4]), risks = listOf(r[0], r[4]))
        fixture("single_json", predictions = null, risks = null, raw = mapOf(
            "prediction_frame.json" to pretty.encodeToString(JsonElement.serializer(), toJson(p[2])),
            "risk_frames.json" to pretty.encodeToString(JsonElement.serializer(), toJson(r))))
        Files.createDirectories(fixtures.parent)
        Files.writeString(fixtures.parent.resolve("index.json"), "[]")
    }

    private fun write(directory: Path, scenario: Scenario, vehicles: Boolean = true,
                      predictions: Boolean = true, risks: Boolean = true) {
        Files.createDirectories(directory)
        Files.list(directory).use { files -> files.filter { Files.isRegularFile(it) }.forEach { Files.delete(it) } }
        Files.writeString(directory.resolve("scenario_manifest.json"),
            pretty.encodeToString(JsonElement.serializer(), toJson(scenario.manifest)))
        fun rows(file: String, data: List<Map<String, Any?>>) = Files.writeString(directory.resolve(file),
            data.joinToString("\n") { encode(it) } + "\n")
        if (vehicles) rows("vehicle_frames.jsonl", scenario.vehicles)
        if (predictions) rows("prediction_frames.jsonl", scenario.predictions)
        if (risks) rows("risk_frames.jsonl", scenario.risks)
    }

    private fun encode(value: Any?): String = compact.encodeToString(JsonElement.serializer(), toJson(value))
    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
        is Iterable<*> -> JsonArray(value.map(::toJson))
        else -> error("Unsupported JSON value: ${value::class}")
    }
}
