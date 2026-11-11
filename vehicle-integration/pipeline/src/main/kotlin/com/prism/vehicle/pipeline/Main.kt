package com.prism.vehicle.pipeline

import com.prism.prediction.ContractJson as PredictionJson
import com.prism.prediction.PredictionEngine
import com.prism.vehicle.replay.ContractParser
import com.prism.vehicle.replay.EventAnnotation
import com.prism.vehicle.replay.ScenarioManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/*
 * PRISM 통합 파이프라인 (PART 05).
 *   03 simulation-engine/samples/<name>.jsonl  → 그대로 복사 (VehicleFrame v1)
 *   01 prediction-engine PredictionEngine.predict(과거 5초 창) → prediction_frames.jsonl (시점마다 한 줄)
 *   02 risk-engine: RiskFrame 생성기가 아직 없음 → risk_frames 파일을 만들지 않는다 (05 는 위험을 계산하지 않는다)
 * 03 의 묶음 manifest 를 앱이 읽는 시나리오별 manifest 로 바꾸는 일만 여기서 한다.
 */

@Serializable
private data class SimManifest(val scenarios: List<SimScenario>)

@Serializable
private data class SimScenario(
    val scenario: String,
    val file: String,
    val description: String = scenario,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("step_ms") val stepMs: Long,
    val seed: Long? = null,
    @SerialName("ego_id") val egoId: String,
    @SerialName("event_annotation") val events: List<SimEvent> = emptyList(),
)

@Serializable
private data class SimEvent(
    @SerialName("t_ms") val tMs: Long,
    @SerialName("vehicle_id") val vehicleId: String? = null,
    val note: String? = null,
    val type: String? = null,
)

const val HISTORY_WINDOW_MS = 5_000L // 01 README: 과거 2~5초 기록

/**
 * 01 의 ContractJson.encodePredictionFrame 은 encodeDefaults=false 라 기본값 "1.0" 인 schema_version 을 출력에서 뺀다
 * (계약 v1 필수 필드 누락, 2026-11-12 확인, 01 담당자에게 보고). 01 객체의 값은 그대로 두고 기본값까지 직렬화한다.
 */
val ENGINE01_OUTPUT = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = true }

data class ScenarioResult(val id: String, val frames: Int, val predicted: Int, val skipped: List<String>)

/**
 * 계약 v1 VehicleFrame(05 파서) → 01 의 입력 객체.
 * 01 의 ContractJson 은 ignoreUnknownKeys=false 이고 입력 모델에 schema_version·coordinate_system 이 없어
 * 계약대로 schema_version 을 담은 03 출력 JSON 을 그대로 읽지 못한다(2026-11-12 통합 시 확인, 01 담당자에게 보고).
 * 01 소스를 고치지 않고 여기서 객체로 옮겨 PredictionEngine.predict() 에 직접 넘긴다.
 */
fun toEngine01(f: com.prism.vehicle.replay.VehicleFrame) = com.prism.prediction.VehicleFrame(
    timestampMs = f.timestampMs,
    egoId = f.egoId,
    vehicles = f.vehicles.map { v ->
        com.prism.prediction.VehicleState(v.id, v.x, v.y, v.vx, v.vy, v.headingRad, v.lengthM, v.widthM, v.laneId ?: "UNKNOWN")
    },
)

/** 한 시나리오의 VehicleFrame JSONL 로 시점별 PredictionFrame JSONL 을 만든다. 실패한 시점은 건너뛰고 이유를 남긴다. */
fun predictEveryFrame(vehicleJsonl: String): Pair<String, List<String>> {
    val parsed = ContractParser.parseVehicleFrames(vehicleJsonl)
    require(parsed.issues.isEmpty()) { "03 VehicleFrame 계약 위반: ${parsed.issues}" }
    val frames = parsed.value!!.map(::toEngine01).sortedBy { it.timestampMs }
    val skipped = mutableListOf<String>()
    val lines = frames.mapNotNull { now ->
        val window = frames.filter { it.timestampMs in (now.timestampMs - HISTORY_WINDOW_MS)..now.timestampMs }
        runCatching { ENGINE01_OUTPUT.encodeToString(com.prism.prediction.PredictionFrame.serializer(), PredictionEngine.predict(window)) }
            .onFailure { skipped += "t=${now.timestampMs}: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrNull()
    }
    // 01 이 pretty-print 하더라도 JSONL 은 한 줄에 한 프레임
    val compact = lines.joinToString("\n") { ContractParser.json.parseToJsonElement(it).toString() } + "\n"
    return compact to skipped
}

fun main(args: Array<String>) {
    require(args.size == 4) { "usage: <03 samples dir> <assets scenarios dir> <provenance> <report file>" }
    val samples = File(args[0])
    val out = File(args[1])
    val provenance = args[2]
    val report = File(args[3])
    val json = ContractParser.json

    val sim = json.decodeFromString(SimManifest.serializer(), File(samples, "scenario_manifest.json").readText())
    val results = sim.scenarios.map { sc ->
        val vehicleJsonl = File(samples, sc.file).readText()
        val (predictionJsonl, skipped) = predictEveryFrame(vehicleJsonl)
        val dir = File(out, sc.scenario).apply { deleteRecursively(); mkdirs() }
        File(dir, "vehicle_frames.jsonl").writeText(vehicleJsonl)
        File(dir, "prediction_frames.jsonl").writeText(predictionJsonl)
        val manifest = ScenarioManifest(
            scenarioId = sc.scenario,
            title = sc.description,
            durationMs = sc.durationMs,
            stepMs = sc.stepMs,
            seed = sc.seed,
            egoId = sc.egoId,
            eventAnnotation = sc.events.map { e ->
                EventAnnotation(e.tMs, listOfNotNull(e.vehicleId, e.note ?: e.type).joinToString(" "))
            },
            provenance = provenance,
            mock = false,
        )
        File(dir, "scenario_manifest.json").writeText(json.encodeToString(ScenarioManifest.serializer(), manifest))
        val frames = vehicleJsonl.lines().count { it.isNotBlank() }
        ScenarioResult(sc.scenario, frames, predictionJsonl.lines().count { it.isNotBlank() }, skipped)
    }

    writeIndex(out, sim.scenarios.map { it.scenario })
    report.parentFile?.mkdirs()
    report.writeText(buildString {
        appendLine("PRISM 통합 파이프라인 결과")
        appendLine("provenance: $provenance")
        appendLine("history window: ${HISTORY_WINDOW_MS}ms")
        results.forEach { r ->
            appendLine("${r.id}: VehicleFrame ${r.frames}, PredictionFrame ${r.predicted}, 건너뜀 ${r.skipped.size}")
            r.skipped.forEach { appendLine("  - $it") }
        }
    })
    print(report.readText())
}

/** index.json = 통합 시나리오(03 순서) 다음에 mock_* 시나리오. */
fun writeIndex(out: File, integrated: List<String>) {
    val mocks = out.listFiles { f -> f.isDirectory && f.name.startsWith("mock_") }.orEmpty().map { it.name }.sorted()
    val ids = integrated + mocks
    File(out, "index.json").writeText(ContractParser.json.encodeToString(ListSerializer(String.serializer()), ids))
}
