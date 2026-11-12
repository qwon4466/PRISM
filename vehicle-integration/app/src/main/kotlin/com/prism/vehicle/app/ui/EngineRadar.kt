package com.prism.vehicle.app.ui

import ai.prism.visualization.model.ContractJson as VizJson
import ai.prism.visualization.model.RoadConfig
import ai.prism.visualization.rendering.DisplayMode
import ai.prism.visualization.ui.RadarConfig
import ai.prism.visualization.ui.RadarScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.prism.vehicle.replay.ContractParser
import com.prism.vehicle.replay.PredictionFrame
import com.prism.vehicle.replay.RiskFrame
import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.Snapshot
import com.prism.vehicle.replay.VehicleFrame

/*
 * 04 visualization-engine 의 RadarScreen 연결 (04 HANDOFF_INTEGRATION.md 2·3절).
 * 05 는 그리지 않는다. 05 가 검증한 계약 v1 프레임을 JSON 으로 넘기고 04 의 파서·렌더러를 그대로 쓴다.
 */
@Composable
fun EngineRadar(snapshot: Snapshot, nowMs: Long, detail: Boolean, laneWidthM: Float, modifier: Modifier = Modifier) {
    val json = ContractParser.json
    val vf = remember(snapshot.vehicle) {
        snapshot.vehicle?.let { runCatching { VizJson.parseVehicleFrame(json.encodeToString(VehicleFrame.serializer(), it)).value }.getOrNull() }
    }
    val pf = remember(snapshot.prediction) {
        snapshot.prediction?.let { runCatching { VizJson.parsePredictionFrame(json.encodeToString(PredictionFrame.serializer(), it)).value }.getOrNull() }
    }
    val rf = remember(snapshot.risk) {
        snapshot.risk?.let { runCatching { VizJson.parseRiskFrame(json.encodeToString(RiskFrame.serializer(), it)).value }.getOrNull() }
    }
    RadarScreen(
        vehicleFrame = vf,
        modifier = modifier,
        predictionFrame = pf,
        riskFrame = rf,
        // 주행 중에는 04 가 위험 상위 차량만 강조, 정차 후 리플레이에서는 상세 표시
        mode = if (detail) DisplayMode.DETAIL else DisplayMode.DRIVING,
        config = RadarConfig(road = RoadConfig(laneCount = 3, laneWidthM = laneWidthM, egoLaneIndex = 1)),
        nowMs = nowMs, // VehicleFrame.timestamp_ms 와 같은 재생 시계 → 데이터가 늦으면 04 가 판단 보류
    )
}

/** 05 자체 위험 배너용 색(04 레이더와 별개). */
fun riskColor(level: RiskLevel?): Color = when (level) {
    RiskLevel.WARNING -> Color(0xFFE53935)
    RiskLevel.WATCH -> Color(0xFFFDD835)
    RiskLevel.NORMAL, null -> Color(0xFF9E9E9E)
    RiskLevel.UNKNOWN -> Color(0xFFB0BEC5)
}
