package com.prism.vehicle.app.ui

import ai.prism.visualization.model.RoadConfig
import ai.prism.visualization.rendering.VehicleGeometry
import ai.prism.visualization.rendering.ViewportTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.vehicle.replay.DemoRiskAnalyzer
import com.prism.vehicle.replay.RiskFrame
import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.Snapshot
import com.prism.vehicle.replay.VehicleState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Light radar: current car bodies and their boxes share one ego-local projection. */
@Composable
fun EngineRadar(snapshot: Snapshot, assessment: RiskFrame?, detail: Boolean, laneWidthM: Float, modifier: Modifier = Modifier) {
    val frame = snapshot.vehicle
    val text = rememberTextMeasurer(cacheSize = 128)
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFF3F7FB))) {
        Canvas(Modifier.fillMaxSize()) {
            val road = RoadConfig(laneCount = 3, laneWidthM = laneWidthM, egoLaneIndex = 1)
            val t = ViewportTransform(size.width, size.height, road)
            drawRect(Color(0xFFDDE5ED), Offset(t.roadLeftPx, 0f), Size(t.roadRightPx - t.roadLeftPx, size.height))
            for (lane in 1 until road.laneCount) {
                val x = t.toScreen((lane - road.egoLaneIndex - 0.5f) * laneWidthM, 0f).x
                drawLine(Color(0xFF9EADBD), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15.dp.toPx(), 11.dp.toPx())))
            }
            if (frame == null) return@Canvas
            val ego = frame.vehicles.firstOrNull { it.id == frame.egoId } ?: return@Canvas
            if (detail) snapshot.prediction?.predictions?.forEach { prediction ->
                val car = frame.vehicles.firstOrNull { it.id == prediction.vehicleId } ?: return@forEach
                prediction.trajectories.take(2).forEach { path ->
                    val first = t.toScreen(car.x.toFloat(), car.y.toFloat())
                    val points = listOf(Offset(first.x, first.y)) + path.points.map { p ->
                        val seconds = p.dtMs / 1000f
                        val projected = t.toScreen((p.x - ego.vx * seconds).toFloat(), (p.y - ego.vy * seconds).toFloat())
                        Offset(projected.x, projected.y)
                    }
                    for (i in 0 until points.lastIndex) drawLine(Color(0xFF4B7193).copy(alpha = 0.4f), points[i], points[i + 1], 2.dp.toPx())
                }
            }
            val levels = assessment?.riskObjects?.associate { it.vehicleId to it.riskLevel }.orEmpty()
            frame.vehicles.forEach { car ->
                val isEgo = car.id == frame.egoId
                val level = if (isEgo) null else if (snapshot.stale) RiskLevel.UNKNOWN else levels[car.id] ?: RiskLevel.UNKNOWN
                val color = if (isEgo) Color(0xFF1766B1) else riskColor(level)
                val box = radarVehicleBox(car, t, 6.dp.toPx())
                if (box.bottom < 0 || box.top > size.height || box.right < 0 || box.left > size.width) return@forEach
                val displayWidth = VehicleGeometry.displayWidthM(car.lengthM.toFloat(), car.widthM.toFloat(), t)
                val corners = VehicleGeometry.corners(car.x.toFloat(), car.y.toFloat(), car.headingRad.toFloat(),
                    car.lengthM.toFloat(), displayWidth).map { t.toScreen(it) }
                val body = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    corners.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(body, if (isEgo) Color(0xFF70ACDF) else Color.White)
                drawPath(body, color, style = Stroke(2.dp.toPx()))
                drawRoundRect(color, Offset(box.left, box.top), Size(box.right - box.left, box.bottom - box.top),
                    CornerRadius(5.dp.toPx()), style = Stroke(if (level == RiskLevel.WARNING) 3.dp.toPx() else 2.dp.toPx(),
                        pathEffect = if (level == RiskLevel.UNKNOWN) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null))
                val gap = if (isEgo) "" else "  ${DemoRiskAnalyzer.clearanceM(ego, car).roundToInt()}m"
                val label = if (isEgo) "내 차" else "${car.id}  ${riskLabel(level)}$gap"
                val layout = text.measure(label, TextStyle(color = Color(0xFF172536), fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                val x = (box.left - 2.dp.toPx()).coerceIn(2.dp.toPx(), max(2.dp.toPx(), size.width - layout.size.width - 5.dp.toPx()))
                val above = box.top - layout.size.height - 5.dp.toPx()
                val y = if (above >= 2.dp.toPx()) above else min(size.height - layout.size.height - 2.dp.toPx(), box.bottom + 3.dp.toPx())
                drawRoundRect(Color.White.copy(alpha = 0.95f), Offset(x - 2.dp.toPx(), y - 1.dp.toPx()),
                    Size(layout.size.width + 4.dp.toPx(), layout.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawText(layout, topLeft = Offset(x, y))
            }
        }
    }
}

data class RadarBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

fun radarVehicleBox(car: VehicleState, t: ViewportTransform, paddingPx: Float = 6f): RadarBox {
    val width = VehicleGeometry.displayWidthM(car.lengthM.toFloat(), car.widthM.toFloat(), t)
    val corners = VehicleGeometry.corners(car.x.toFloat(), car.y.toFloat(), car.headingRad.toFloat(),
        car.lengthM.toFloat(), width).map { t.toScreen(it) }
    return RadarBox(corners.minOf { it.x } - paddingPx, corners.minOf { it.y } - paddingPx,
        corners.maxOf { it.x } + paddingPx, corners.maxOf { it.y } + paddingPx)
}

fun riskColor(level: RiskLevel?): Color = when (level) {
    RiskLevel.WARNING -> Color(0xFFC62828)
    RiskLevel.WATCH -> Color(0xFF9A6700)
    RiskLevel.NORMAL -> Color(0xFF526579)
    RiskLevel.UNKNOWN, null -> Color(0xFF6A7684)
}

fun riskLabel(level: RiskLevel?): String = when (level) {
    RiskLevel.WARNING -> "위험"
    RiskLevel.WATCH -> "주의"
    RiskLevel.NORMAL -> "보통"
    RiskLevel.UNKNOWN, null -> "판단 보류"
}
