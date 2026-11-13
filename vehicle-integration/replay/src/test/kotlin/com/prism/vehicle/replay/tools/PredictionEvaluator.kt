package com.prism.vehicle.replay.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Compares synthetic prediction candidates with future positions, not real road performance. */
object PredictionEvaluator {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of("").toAbsolutePath()
        val assets = root.resolve("app/src/main/assets/scenarios")
        val samples = root.parent.resolve("simulation-engine/samples")
        val ids = Json.parseToJsonElement(Files.readString(assets.resolve("index.json"))).jsonArray
            .map { it.jsonPrimitive.content }.filterNot { it.startsWith("mock_") }
        println("01 예측 vs 03 실제 미래 위치 (후보 궤적 중 최소 거리 오차)")
        for (id in ids) {
            val errors = evaluate(assets.resolve(id), samples.resolve("$id.ego_pose.jsonl"))
            println("${id.padEnd(15)} 1s: ${summary(errors.getValue(1000))} | 3s: ${summary(errors.getValue(3000))}")
        }
    }

    private fun evaluate(scenario: Path, poseFile: Path): Map<Int, List<Double>> {
        val frames = readJsonl(scenario.resolve("vehicle_frames.jsonl")).associateBy { it.number("timestamp_ms").toInt() }
        val predictions = readJsonl(scenario.resolve("prediction_frames.jsonl"))
        val poses = readJsonl(poseFile).associateBy { it.number("timestamp_ms").toInt() }
        val errors = mapOf(1000 to mutableListOf<Double>(), 3000 to mutableListOf())
        for (prediction in predictions) {
            val timestamp = prediction.number("timestamp_ms").toInt()
            val sourcePose = poses[timestamp] ?: continue
            for (vehiclePrediction in prediction.array("predictions")) {
                val predicted = vehiclePrediction.jsonObject
                val id = predicted.string("vehicle_id")
                for ((delta, values) in errors) {
                    val actual = frames[timestamp + delta]?.array("vehicles")?.map { it.jsonObject }
                        ?.firstOrNull { it.string("id") == id } ?: continue
                    val futurePose = poses[timestamp + delta] ?: continue
                    val (worldX, worldY) = rotate(actual.number("x"), actual.number("y"), futurePose.number("heading_rad") - PI / 2)
                    val dx = futurePose.number("x") + worldX - sourcePose.number("x")
                    val dy = futurePose.number("y") + worldY - sourcePose.number("y")
                    val (actualX, actualY) = rotate(dx, dy, -(sourcePose.number("heading_rad") - PI / 2))
                    val best = predicted.array("trajectories").asSequence()
                        .flatMap { it.jsonObject.array("points").asSequence() }
                        .map { it.jsonObject }
                        .filter { it.number("dt_ms").toInt() == delta }
                        .minOfOrNull { hypot(it.number("x") - actualX, it.number("y") - actualY) }
                    if (best != null) values += best
                }
            }
        }
        return errors
    }

    private fun summary(values: List<Double>): String {
        if (values.isEmpty()) return "-"
        val sorted = values.sorted()
        return String.format(Locale.US, "mean %.2f m, p95 %.2f m (n=%d)",
            sorted.average(), sorted[(0.95 * (sorted.size - 1)).toInt()], sorted.size)
    }

    private fun readJsonl(path: Path): List<JsonObject> = Files.readAllLines(path)
        .filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    private fun rotate(x: Double, y: Double, angle: Double): Pair<Double, Double> =
        Pair(cos(angle) * x - sin(angle) * y, sin(angle) * x + cos(angle) * y)

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}
