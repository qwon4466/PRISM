package com.prism.vehicle.app.ui

import ai.prism.visualization.rendering.DisplayMode
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.floor
import ai.prism.visualization.rendering.RoadOdometer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import com.prism.vehicle.adapters.SdkFeature
import com.prism.vehicle.adapters.SupportStatus
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.vehicle.app.AppMode
import com.prism.vehicle.app.PrismViewModel
import com.prism.vehicle.app.UiState
import com.prism.vehicle.replay.DemoRiskAnalyzer
import com.prism.vehicle.replay.PredictionFrame
import com.prism.vehicle.replay.RiskFrame
import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.RiskObject
import com.prism.vehicle.replay.ScenarioTimeline
import com.prism.vehicle.replay.VehicleState
import com.prism.vehicle.replay.VehicleFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

// ── 테마 토큰 (Theme.kt 의 PrismTheme 이 라이트/다크/자동을 결정). NAVY = 본문 글자색
internal val BG: Color get() = PrismTheme.palette.bg
internal val PANEL: Color get() = PrismTheme.palette.panel
internal val SUBBG: Color get() = PrismTheme.palette.subbg
internal val LINE: Color get() = PrismTheme.palette.line
internal val NAVY: Color get() = PrismTheme.palette.text
internal val GRAY: Color get() = PrismTheme.palette.gray
internal val ACCENT: Color get() = PrismTheme.palette.accent
internal val SAFE: Color get() = PrismTheme.palette.safe
internal val WATCH: Color get() = PrismTheme.palette.watch
internal val WARN: Color get() = PrismTheme.palette.warn
internal val UNK: Color get() = PrismTheme.palette.unk
internal val INK = Color(0xFF151515)   // 노란 배지 위 글자

private fun levelColor(l: RiskLevel?) = when (l) {
    RiskLevel.WARNING -> WARN
    RiskLevel.WATCH -> WATCH
    RiskLevel.UNKNOWN -> UNK
    else -> SAFE
}

private fun RiskLevel?.alerting() = this == RiskLevel.WARNING || this == RiskLevel.WATCH

/** 상단 카드에 올릴 가장 긴급한 위험: 등급 → TTC 짧은 순 → 원인 긴급도 (DemoRiskAnalyzer.urgency). 나머지는 주요 원인 목록에 유지. */
private fun RiskFrame?.top(): RiskObject? = this?.riskObjects?.let { os ->
    os.filter { it.riskLevel.alerting() }.sortedWith(DemoRiskAnalyzer.urgency).firstOrNull()
        ?: os.firstOrNull { it.riskLevel == RiskLevel.UNKNOWN } ?: os.firstOrNull()
}

/** 방향지시등 표시 문구. null = 데이터에 없음(03 엔진·실차) → 만들어내지 않고 '미확인'. */
private fun signalText(ind: String?) = when (ind) {
    "LEFT" -> "◀ 좌측 깜빡이"; "RIGHT" -> "우측 깜빡이 ▶"; "HAZARD" -> "비상등"; "OFF" -> "깜빡이 꺼짐"; else -> "깜빡이 미확인"
}

private fun lcColor(l: DemoRiskAnalyzer.LaneChangeLevel) = when (l) {
    DemoRiskAnalyzer.LaneChangeLevel.SAFE -> SAFE
    DemoRiskAnalyzer.LaneChangeLevel.CAUTION -> WATCH
    DemoRiskAnalyzer.LaneChangeLevel.WARNING -> Color(0xFFFF8A00)
    DemoRiskAnalyzer.LaneChangeLevel.DANGER -> WARN
}

/** 카드: 어두운 반투명 글래스 + 위쪽이 살짝 밝은 그라데이션 + 얇은 테두리. */
private fun Modifier.card(shape: Shape = RoundedCornerShape(20.dp), bg: Color = PANEL, border: Color = LINE, elevation: Dp = 6.dp) =
    shadow(elevation, shape, ambientColor = PrismTheme.palette.shadow, spotColor = PrismTheme.palette.shadow)
        .background(Brush.verticalGradient(listOf(lerpColor(bg, Color.White, 0.05f).copy(alpha = bg.alpha), bg)), shape)
        .border(1.dp, border, shape)

/** 자차 기준 방향. 좌표는 ego_local_m (x 오른쪽+, y 앞쪽+). */
private fun side(ego: VehicleState, v: VehicleState): String {
    val dx = v.x - ego.x
    val dy = v.y - ego.y
    return when {
        abs(dx) < 1.75 -> if (dy >= 0) "전방" else "후방"
        dx > 0 -> if (dy > 3) "우측 전방" else if (dy < -3) "우측 후방" else "우측"
        else -> if (dy > 3) "좌측 전방" else if (dy < -3) "좌측 후방" else "좌측"
    }
}

/** 0.1초 간격 프레임 사이를 보간해 차량이 부드럽게 움직이게 한다. 프레임 공백(누락)은 보간하지 않는다. */
private fun smoothVehicles(t: ScenarioTimeline, posMs: Long): List<VehicleState> {
    val frames = t.vehicleFrames
    val i = frames.indexOfLast { it.timestampMs <= posMs }
    if (i < 0) return frames.firstOrNull()?.vehicles.orEmpty()
    val a = frames[i]
    val b = frames.getOrNull(i + 1) ?: return a.vehicles
    if (b.timestampMs - a.timestampMs > t.manifest.stepMs * 2) return a.vehicles
    val f = ((posMs - a.timestampMs).toDouble() / (b.timestampMs - a.timestampMs)).coerceIn(0.0, 1.0)
    val next = b.vehicles.associateBy { it.id }
    return a.vehicles.map { va ->
        val vb = next[va.id] ?: return@map va
        va.copy(x = va.x + (vb.x - va.x) * f, y = va.y + (vb.y - va.y) * f,
            headingRad = va.headingRad + (vb.headingRad - va.headingRad) * f, vx = vb.vx, vy = vb.vy)
    }
}

// ───────────────────────── 화면 ─────────────────────────

private enum class Tab { LIVE, ANALYSIS, SETTINGS }

/** RISK RADAR 주행 화면 (다크 테마 · 3D 추적 카메라 · 360° 어라운드뷰). 위험 판단은 기존 assessments, 예측 경로는 01 prediction 그대로. */
/** 브레이크등: 0.3초 전보다 2 m/s² 넘게 느려진 차량 (시나리오 속도 데이터 기준). */
private fun brakingIds(t: ScenarioTimeline, positionMs: Long): Set<String> {
    val before = t.snapshotAt(positionMs - 300).vehicle?.vehicles?.associateBy { it.id }.orEmpty()
    return t.snapshotAt(positionMs).vehicle?.vehicles.orEmpty().filter { v -> before[v.id]?.let { v.vy - it.vy < -0.6 } == true }.map { it.id }.toSet()
}

/** 실시간 화면과 같은 3D 추적 시점을 다른 화면(리플레이)에서 그대로 재현. 같은 판정(assessments)·같은 차량 보간을 쓴다. */
@Composable
internal fun ChaseSlot(s: UiState, t: ScenarioTimeline, modifier: Modifier) {
    val snap = t.snapshotAt(s.clock.positionMs)
    val assessment = if (snap.stale) null else s.assessments[snap.vehicle?.timestampMs]
    val vehicles = if (snap.stale) snap.vehicle?.vehicles.orEmpty() else smoothVehicles(t, s.clock.positionMs)
    val ego = vehicles.firstOrNull { it.id == snap.vehicle?.egoId }
    val braking = remember(snap.vehicle?.timestampMs) { brakingIds(t, s.clock.positionMs) }
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(BG)) {
        ChaseView(t, vehicles, ego, assessment, snap.prediction, s.clock.positionMs, braking)
        if (snap.stale) Text("데이터 누락 — 현재 상태 알 수 없음", color = WARN, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.Center).card(RoundedCornerShape(14.dp)).padding(14.dp))
    }
}


@Composable
fun RiskRadarDriveScreen(s: UiState, vm: PrismViewModel) {
    val t = s.timeline ?: return
    val snap = t.snapshotAt(s.clock.positionMs)
    val assessment = if (snap.stale) null else s.assessments[snap.vehicle?.timestampMs]
    val frame = snap.vehicle
    val vehicles = if (snap.stale) frame?.vehicles.orEmpty() else smoothVehicles(t, s.clock.positionMs)
    val ego = vehicles.firstOrNull { it.id == frame?.egoId }
    val braking = remember(frame?.timestampMs) { brakingIds(t, s.clock.positionMs) }
    var tab by remember { mutableStateOf(Tab.LIVE) }
    var avm by remember { mutableStateOf(false) }   // 하단 화면 모드: 주행(3D 추적) / 어라운드뷰
    var details by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PrismTheme.palette.bgTop, BG))).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NavRail(tab, onTab = { tab = it }, onHome = vm::back, onReplay = vm::openReplay, replayEnabled = s.replayBlockedReason == null)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(s, t.manifest.mock)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth().card(RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))) {
                        Crossfade(if (tab == Tab.ANALYSIS) 2 else if (avm) 1 else 0, label = "mode") { m ->
                            when (m) {
                                2 -> RadarSlot(t, snap, assessment, DisplayMode.DETAIL, Modifier.fillMaxSize())
                                1 -> AroundView(t, vehicles, ego, assessment, snap.prediction, s.clock.positionMs)
                                else -> ChaseView(t, vehicles, ego, assessment, snap.prediction, s.clock.positionMs, braking)
                            }
                        }
                        if (tab != Tab.ANALYSIS) DrivingHud(ego, vehicles, t.manifest.mock, Modifier.align(Alignment.TopStart).padding(18.dp))
                        if (snap.stale) Text("데이터 누락 — 현재 상태 알 수 없음", color = WARN, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            modifier = Modifier.align(Alignment.Center).card(RoundedCornerShape(14.dp)).padding(16.dp))
                    }
                    BottomHud(if (tab == Tab.ANALYSIS) 2 else if (avm) 1 else 0) { m ->
                        when (m) { 2 -> tab = Tab.ANALYSIS; else -> { avm = m == 1; if (tab == Tab.ANALYSIS) tab = Tab.LIVE } }
                    }
                }
                Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tab == Tab.SETTINGS) { SettingsCard(s, vm); ThemeCard() }
                    else {
                        WarningCard(s, vm, ego, vehicles, assessment, snap.stale)
                        if (!snap.stale) frame?.let { DemoRiskAnalyzer.laneChangeCheck(it) }?.let { LaneChangeCard(it) }
                        ForecastCard(s, t)
                        CausesCard(s, ego, vehicles, assessment)
                    }
                    PlaybackCard(s, vm, t)
                    // 360° 감지·시스템 상태는 메인 화면을 복잡하게 하지 않도록 접어 둔다 (기능은 그대로)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { details = !details }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("주변 감지 · 시스템 상태", fontSize = 14.sp, color = GRAY, modifier = Modifier.weight(1f))
                        Text(if (details) "접기 ▴" else "펼치기 ▾", fontSize = 14.sp, color = ACCENT, fontWeight = FontWeight.SemiBold)
                    }
                    if (details) {
                        SurroundCard(ego, vehicles, assessment, snap.stale)
                        SystemCard(s, t, snap.stale)
                    }
                }
            }
        }
    }
}

/** 데이터에서 추정한 차선에 최소 4차선을 보장(모자란 차선은 오른쪽에 추가). 차량 위치는 데이터 그대로. */
@Composable
private fun fourLaneRoad(t: ScenarioTimeline, @Suppress("UNUSED_PARAMETER") positionMs: Long) = remember(t) {
    LaneLayout.of(t.vehicleFrames).roadAt(t.vehicleFrames.firstOrNull()).let { it.copy(laneCount = maxOf(4, it.laneCount)) }
}

/**
 * 자차 가로 이동 누적(m, 오른쪽 +). 좌표가 자차 기준이라 자차가 차선을 바꾸면 다른 차량이 반대로 움직인다.
 * 도로선도 같은 만큼 옮겨 그리기 위해 자차 vx 를 적분한다(첫 프레임 차로 기준).
 */
private fun lateralTable(t: ScenarioTimeline): Pair<LongArray, DoubleArray> {
    val ts = LongArray(t.vehicleFrames.size)
    val d = DoubleArray(ts.size)
    var acc = 0.0
    t.vehicleFrames.forEachIndexed { i, f ->
        val vx = f.vehicles.firstOrNull { it.id == f.egoId }?.vx?.takeIf { it.isFinite() } ?: 0.0
        if (i > 0) {
            val pvx = t.vehicleFrames[i - 1].let { p -> p.vehicles.firstOrNull { it.id == p.egoId }?.vx?.takeIf { it.isFinite() } ?: 0.0 }
            acc += (pvx + vx) / 2 * (f.timestampMs - ts[i - 1]) / 1000.0
        }
        ts[i] = f.timestampMs; d[i] = acc
    }
    return ts to d
}

// ───────────────────────── 주행 시점 (자차 뒤 추적 카메라 · 3D 원근) ─────────────────────────
// 모든 도형은 세계 좌표(x 오른쪽 m, y 앞 m, 높이 m)를 p3() 로 투영해 그린다. 차량·위험 영역 위치는 시나리오 데이터 그대로.

private const val CAM_BACK = 14.0  // 카메라: 자차 중심 뒤 14m
private const val CAM_H = 3.3      // 카메라 높이 3.3m

/** 프레임마다 자차 속도를 적분한 누적 주행거리 표(04 RoadOdometer). 재생 위치를 옮겨도 도로 흐름이 이어지게 미리 만든다. */
private fun odometerTable(t: ScenarioTimeline): Pair<LongArray, DoubleArray> {
    val o = RoadOdometer()
    val ts = LongArray(t.vehicleFrames.size)
    val d = DoubleArray(ts.size)
    t.vehicleFrames.forEachIndexed { i, f ->
        ts[i] = f.timestampMs
        d[i] = o.advance(f.timestampMs, f.vehicles.firstOrNull { it.id == f.egoId }?.vy?.toFloat())
    }
    return ts to d
}

private fun Pair<LongArray, DoubleArray>.at(ms: Long): Double {
    val (ts, d) = this
    val i = ts.indexOfLast { it <= ms }
    if (i < 0) return d.firstOrNull() ?: 0.0
    if (i == ts.lastIndex) return d[i]
    return d[i] + (d[i + 1] - d[i]) * ((ms - ts[i]).toDouble() / (ts[i + 1] - ts[i])).coerceIn(0.0, 1.0)
}

@Composable
private fun ChaseView(t: ScenarioTimeline, vehicles: List<VehicleState>, ego: VehicleState?, assessment: RiskFrame?,
                      prediction: PredictionFrame?, positionMs: Long, braking: Set<String>) {
    val road = fourLaneRoad(t, positionMs)
    val latTable = remember(t) { lateralTable(t) }
    val lat = latTable.at(positionMs)
    val odoTable = remember(t) { odometerTable(t) }
    val odo = odoTable.at(positionMs)
    val byId = assessment?.riskObjects?.associateBy { it.vehicleId }.orEmpty()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "bbox")
    // 방향지시등 점멸: 약 1.5Hz (분당 90회, 일정한 주기)
    val blinkPhase by rememberInfiniteTransition(label = "blink").animateFloat(0f, 1f,
        infiniteRepeatable(tween(667, easing = LinearEasing), RepeatMode.Restart), label = "blink")
    val blinkOn = blinkPhase < 0.5f
    val ex = ego?.x ?: 0.0
    val ey = ego?.y ?: 0.0
    val lcc = ego?.let { e -> DemoRiskAnalyzer.laneChangeCheck(VehicleFrame("1.0", positionMs, null, e.id, vehicles), road.laneWidthM.toDouble()) }
    val prevById = t.snapshotAt(positionMs - 500).vehicle?.vehicles?.associateBy { it.id }.orEmpty()
    val recentEgoMove = abs(lat - latTable.at(positionMs - 1500)) > 0.3 * road.laneWidthM
    // 카메라 앞에 있는 차량만 그림. 같은 차로에서 자차보다 뒤에 있는 차는 자차를 가리므로 후방 알림으로 대신
    val shown = vehicles.filter {
        it.id == ego?.id || (it.y - it.lengthM / 2 - ey + CAM_BACK > 1.0 && !(it.y < ey - 0.5 && abs(it.x - ex) < 1.75))
    }
    val alerting = shown.filter { it.id != ego?.id && byId[it.id]?.riskLevel.alerting() }
    val behind = vehicles.filter { it.id != ego?.id && it !in shown && byId[it.id]?.riskLevel.alerting() }
    val zoneAlpha by animateFloatAsState(if (alerting.isEmpty()) 0f else 1f, tween(450), label = "zone")
    val behindAlpha by animateFloatAsState(if (behind.isEmpty()) 0f else 1f, tween(450), label = "behind")
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val trees = remember(density) { treeSprites(density) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val hy = h * 0.42f                 // 지평선(소실점 높이)
        val f = h * 1.35f                  // 초점 거리(px)
        fun z(y: Double) = maxOf(y - ey + CAM_BACK, 0.8)
        fun k(y: Double) = (f / z(y)).toFloat()                       // 그 깊이에서 1m 당 픽셀
        fun p3(x: Double, y: Double, up: Double) = Offset(w / 2 + ((x - ex) * k(y)).toFloat(), hy + ((CAM_H - up) * k(y)).toFloat())
        fun p(x: Double, y: Double) = p3(x, y, 0.0)
        val lw = road.laneWidthM.toDouble()
        val left = ex - (road.egoLaneIndex + 0.5) * lw - lat
        val right = ex + (road.laneCount - road.egoLaneIndex - 0.5) * lw - lat
        val near = ey - CAM_BACK + 0.9
        val far = ey + 600
        val gantryY = ey + 260 - (odo % 380)   // 380m 마다 지나가는 표지판 구조물 (주행거리에 맞춰 다가옴)

        // 먼 배경(하늘·산·도시)은 크기가 바뀔 때만 다시 굽는다
        val day = !PrismTheme.palette.dark   // 라이트 = 주간 장면, 다크 = 노을·야간 장면
        val sky = remember(w.roundToInt(), hy.roundToInt(), day) { bake(w.roundToInt(), hy.roundToInt(), density) { scenery(size.width, size.height, day) } }
        Canvas(Modifier.fillMaxSize()) {
            drawImage(sky)
            drawRect(Brush.verticalGradient(if (day) listOf(Color(0xFF8FAF7E), Color(0xFF6F8F60)) else listOf(Color(0xFF17221F), Color(0xFF0A0F12)), hy, h), Offset(0f, hy), Size(w, h - hy))
            // 노면: 갓길 포함 → 차로
            quad(p(left - 2.4, near), p(right + 2.4, near), p(right + 2.4, far), p(left - 2.4, far),
                Brush.verticalGradient(if (day) listOf(Color(0xFF9AA0AA), Color(0xFF7E848E), Color(0xFF6A7079)) else listOf(Color(0xFF3B404C), Color(0xFF23272F), Color(0xFF181B22)), hy, h))
            quad(p(left, near), p(right, near), p(right, far), p(left, far),
                Brush.verticalGradient(if (day) listOf(Color(0xFF8B919B), Color(0xFF6C727C), Color(0xFF585E68)) else listOf(Color(0xFF41464F), Color(0xFF2A2E36), Color(0xFF1E2128)), hy, h))
            fun mark(x: Double, y0: Double, y1: Double, wm: Double, c: Color) =
                quad(p(x - wm / 2, y0), p(x + wm / 2, y0), p(x + wm / 2, y1), p(x - wm / 2, y1), SolidColor(c))
            mark(left, near, far, 0.2, Color(0xFFE2B33C))       // 중앙분리대 쪽 황색 실선
            mark(right, near, far, 0.2, Color(0xFFE6E9EF))
            val phase = odo % 12.0
            for (i in 1 until road.laneCount) {
                var y = ey - CAM_BACK - phase
                while (y < ey + 240) { if (y + 5 > near) mark(left + i * lw, maxOf(y, near), y + 5, 0.15, Color(0xDDE6E9EF)); y += 12 }
            }
            // 중앙분리대 (콘크리트 벽: 도로 쪽 면 + 윗면)
            val mx = left - 1.2
            quad(p3(mx, near, 0.0), p3(mx, far, 0.0), p3(mx, far, 0.85), p3(mx, near, 0.85),
                Brush.horizontalGradient(listOf(Color(0xFF5D6370), Color(0xFF3A3F49)), 0f, w / 2))
            quad(p3(mx - 0.5, near, 0.85), p3(mx, near, 0.85), p3(mx, far, 0.85), p3(mx - 0.5, far, 0.85), SolidColor(Color(0xFF8A909C)))
            // 가드레일 (기둥은 주행거리만큼 흐름) + 가로 레일
            val gx = right + 1.4
            var py = ey - CAM_BACK + 1 - (odo % 4.0)
            while (py < ey + 160) {
                if (py > near) drawLine(Color(0xFF7D8594), p3(gx, py, 0.0), p3(gx, py, 0.8), maxOf(1.2f, 0.1f * k(py)))
                py += 4.0
            }
            quad(p3(gx, near, 0.5), p3(gx, far, 0.5), p3(gx, far, 0.8), p3(gx, near, 0.8),
                Brush.horizontalGradient(listOf(Color(0xFF6E7685), Color(0xFFAEB6C3)), w / 2, w))
            // 노면 타이어 자국: 각 차로 바퀴 지나는 자리를 조금 어둡게 (아스팔트 질감)
            for (i in 0 until road.laneCount) {
                val c = left + (i + 0.5) * lw
                listOf(-0.85, 0.85).forEach { o -> mark(c + o, near, far, 0.55, Color(0x1A000000)) }
            }
            // 가로등(중앙분리대) · 길가 나무: 세계 좌표에 고정된 번호로 놓고 주행거리만큼 다가오게. 먼 것부터
            val lampStep = 42.0
            for (i in floor((odo + 260) / lampStep).toInt() downTo floor((odo - 10) / lampStep).toInt()) {
                val ly = ey + i * lampStep - odo
                if (ly < ey - 6) continue
                val lx = mx - 0.25
                val head = p3(lx + 1.4, ly, 8.6)
                drawLine(Color(0xFF4A505C), p3(lx, ly, 0.85), p3(lx, ly, 8.8), maxOf(1f, 0.12f * k(ly)))
                drawLine(Color(0xFF4A505C), p3(lx, ly, 8.8), head, maxOf(1f, 0.1f * k(ly)))
                val gr = maxOf(4f, 1.3f * k(ly))
                if (!day) drawCircle(Brush.radialGradient(listOf(Color(0xCCFFE2B0), Color(0x33FFC070), Color.Transparent), head, gr), gr, head)
                else drawCircle(Color(0xFFE8ECF2), maxOf(2f, 0.25f * k(ly)), head)
            }
            val treeStep = 7.0
            for (i in floor((odo + 230) / treeStep).toInt() downTo floor((odo + 20) / treeStep).toInt()) {
                val yy = ey + i * treeStep - odo
                listOf(right + 9.0 + 6 * rnd(i, 11), left - 12.0 - 8 * rnd(i, 13)).forEachIndexed { s, tx ->
                    if (rnd(i, 17 + s) < 0.35f) return@forEachIndexed       // 듬성듬성
                    val sprite = trees[(rnd(i, 19 + s) * trees.size).toInt().coerceAtMost(trees.size - 1)]
                    val hgt = 6.0 + 5.0 * rnd(i, 23 + s)
                    val base = p3(tx, yy, 0.0); val topY = p3(tx, yy, hgt).y
                    val th = base.y - topY; val tw = th * sprite.width / sprite.height
                    drawImage(sprite, IntOffset.Zero, IntSize(sprite.width, sprite.height),
                        IntOffset((base.x - tw / 2).roundToInt(), topY.roundToInt()), IntSize(maxOf(1, tw.roundToInt()), maxOf(1, th.roundToInt())))
                }
            }
            // 거리 눈금
            listOf(10, 20, 30, 50).forEach { d ->
                val a = p(left, ey + d); val b = p(right, ey + d)
                drawLine(if (day) Color(0x33364A6B) else Color(0x1FFFFFFF), a, b, 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                if (b.x + 60f < size.width) drawText(measurer, "${d}m", Offset(b.x + 12f, b.y - 14f), TextStyle(fontSize = 12.sp, color = GRAY))
            }
            // 자차 전방 감지 영역: 노면에 퍼지는 보라 빛 + 점선 안전 링
            ego?.let { e ->
                val front = e.y + e.lengthM / 2
                val fan = Path().apply {
                    val o1 = p(ex - 0.9, front); moveTo(o1.x, o1.y)
                    val l = p(ex - lw * 0.55, front + 26); lineTo(l.x, l.y)
                    val r = p(ex + lw * 0.55, front + 26); lineTo(r.x, r.y)
                    val o2 = p(ex + 0.9, front); lineTo(o2.x, o2.y); close()
                }
                drawPath(fan, Brush.verticalGradient(listOf(Color.Transparent, ACCENT.copy(alpha = 0.28f)), p(ex, front + 26).y, p(ex, front).y))
                val rx = e.widthM / 2 + 0.8
                val ry = e.lengthM / 2 + 0.8
                val ring = Path().apply {
                    for (i in 0..48) {
                        val a = 2 * PI * i / 48
                        val c = cos(a); val sn = sin(a)
                        val q = p(ex + rx * sign(c) * Math.pow(abs(c), 0.6), ey + ry * sign(sn) * Math.pow(abs(sn), 0.6))
                        if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
                    }
                    close()
                }
                drawPath(ring, ACCENT.copy(alpha = 0.10f))
                drawPath(ring, ACCENT.copy(alpha = 0.6f), style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            }

            // 예상 위험 영역 (노면 위 빗금이 원근에 맞게 좁아짐)
            if (ego != null) alerting.forEach { v ->
                val strength = (1f - DemoRiskAnalyzer.clearanceM(ego, v).toFloat() / 30f).coerceIn(0.3f, 1f) * zoneAlpha
                riskZone(ego, v, ::p, levelColor(byId[v.id]?.riskLevel), strength)
            }
            alerting.forEach { v ->
                val c = levelColor(byId[v.id]?.riskLevel)
                // 01 예측 경로 (점선)
                val tr = prediction?.predictions?.firstOrNull { it.vehicleId == v.id }?.trajectories?.maxByOrNull { it.confidence ?: 0.0 }
                val pts = tr?.points?.filter { z(it.y) > 1.5 }?.map { p(it.x, it.y) }
                if (pts != null && pts.size >= 2) arrowPath(listOf(p(v.x, v.y)) + pts, c, dashed = true)
            }

            // 차량: 먼 차부터 (표지판 구조물은 깊이 순서에 맞춰 사이에 끼워 그림)
            var gantryDrawn = gantryY <= ey + 4 || gantryY > ey + 420
            fun gantryNow() {
                gantryDrawn = true
                val xl = left - 2.2; val xr = right + 2.2
                listOf(xl, xr).forEach { x -> quad(p3(x - 0.2, gantryY, 0.0), p3(x + 0.2, gantryY, 0.0), p3(x + 0.2, gantryY, 7.0), p3(x - 0.2, gantryY, 7.0), SolidColor(Color(0xFF6D7482))) }
                quad(p3(xl, gantryY, 6.5), p3(xr, gantryY, 6.5), p3(xr, gantryY, 7.0), p3(xl, gantryY, 7.0), SolidColor(Color(0xFF5B616D)))
                val a = p3(ex - 2.6, gantryY, 9.0); val b = p3(ex + 2.6, gantryY, 7.0)
                drawRoundRect(Color(0xFF0D6B3C), a, Size(b.x - a.x, b.y - a.y), CornerRadius(4f))
                drawRoundRect(Color.White, a, Size(b.x - a.x, b.y - a.y), CornerRadius(4f), style = Stroke(maxOf(1f, 0.06f * k(gantryY))))
                val px = (b.y - a.y) * 0.42f
                if (px > 9f) drawText(measurer, "↑", Offset(a.x + (b.x - a.x) * 0.22f, a.y + (b.y - a.y) * 0.2f),
                    TextStyle(fontSize = (px / density.density / density.fontScale).sp, color = Color.White, fontWeight = FontWeight.Bold))
            }
            // 사각지대(좌·우): 자차 뒤범퍼 8m 뒤 ~ 앞범퍼 1m 앞, 바로 옆 차로. 차량 있으면 주황, 자차가 그쪽으로 바꾸려 하면 빨강
            ego?.let { e ->
                listOf(-1.0 to "LEFT", 1.0 to "RIGHT").forEach { (sgn, name) ->
                    val occupied = byId.values.any { "BLIND_SPOT_$name" in it.reasonCodes }
                    val conflict = byId.values.any { "BLIND_SPOT_$name" in it.reasonCodes && "LANE_CHANGE_CONFLICT" in it.reasonCodes }
                    val cx = e.x + sgn * lw
                    val y0 = e.y - e.lengthM / 2 - 8; val y1 = e.y + e.lengthM / 2 - 1
                    val q = listOf(p(cx - lw * 0.42, y0), p(cx + lw * 0.42, y0), p(cx + lw * 0.42, y1), p(cx - lw * 0.42, y1))
                    val path = Path().apply { moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    val col = if (conflict) WARN else if (occupied) Color(0xFFFF9800) else ACCENT
                    drawPath(path, col.copy(alpha = if (occupied) 0.26f + 0.14f * pulse else 0.05f))
                    drawPath(path, col.copy(alpha = if (occupied) 0.9f else 0.3f),
                        style = Stroke(2f, pathEffect = if (occupied) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                }
            }
            // 자차 차선 변경: 목표 차로 강조 + 자차 앞에서 목표 차로 중앙으로 휘는 경로 (색 = 차선 변경 안전성)
            if (ego != null && lcc != null) {
                val sgn = if (lcc.side == "LEFT") -1.0 else 1.0
                val centers = (0 until road.laneCount).map { left + (it + 0.5) * lw }
                // 바꾸는 중에는 앞쪽 목표, 막 끝났으면 지금 차로
                val tgt = centers.minBy { abs(it - (ego.x + if (recentEgoMove && abs(ego.vx) < 0.3) 0.0 else sgn * lw * 0.52)) }
                val col = lcColor(lcc.level)
                quad(p(tgt - lw * 0.45, ey + 2), p(tgt + lw * 0.45, ey + 2), p(tgt + lw * 0.45, ey + 45), p(tgt - lw * 0.45, ey + 45),
                    Brush.verticalGradient(listOf(col.copy(alpha = 0.0f), col.copy(alpha = 0.22f)), p(tgt, ey + 45).y, p(tgt, ey + 2).y))
                val path = (0..12).map { i ->
                    val u = i / 12.0; val sm = u * u * (3 - 2 * u)
                    (ego.x + (tgt - ego.x) * sm) to (ego.y + ego.lengthM / 2 + 0.5 + 18 * u)
                }.takeWhile { pt -> vehicles.none { o -> o.id != ego.id && abs(pt.first - o.x) < o.widthM / 2 + 0.4 && abs(pt.second - o.y) < o.lengthM / 2 + 0.4 } }
                if (path.size >= 3) roadArrow(path, 0.6, col, ::p)
            }
            // 차선 변경 경로: 실제 가로 속도와 자차 대비 세로 속도로 1.6초 앞까지, 차 옆면에서 출발.
            // 깊이 버퍼가 없는 Canvas(먼 것부터 그리기)라서, 화살표를 '그 차량의 깊이 순서'에서 노면 위에 그린다.
            // 그러면 그 차보다 카메라에 가까운 차(자차 등)가 나중에 그려져 자연히 가린다. 다른 차 바닥(+0.4m)에 닿으면 거기서 끝낸다.
            val laneArrows = if (ego == null) emptyMap() else alerting.filter { abs(it.vx) > 0.4 }.associate { v ->
                val dir = sign(v.vx)
                val raw = (0..8).map { i -> val s = i * 0.2; (v.x + dir * v.widthM / 2 + v.vx * s) to (v.y + (v.vy - ego.vy) * s) }
                fun blocked(pt: Pair<Double, Double>) = vehicles.any { o ->
                    o.id != v.id && abs(pt.first - o.x) < o.widthM / 2 + 0.4 && abs(pt.second - o.y) < o.lengthM / 2 + 0.4
                }
                v.id to raw.takeWhile { !blocked(it) }
            }
            shown.sortedByDescending { it.y - it.lengthM / 2 }.forEach { v ->
                if (!gantryDrawn && v.y - v.lengthM / 2 < gantryY) gantryNow()
                laneArrows[v.id]?.takeIf { it.size >= 3 }?.let { roadArrow(it, 0.55, WATCH, ::p) }
                val isEgo = v.id == ego?.id
                val r = car3d(v, ::p3, ex, if (isEgo) Color(0xFFF2F4F7) else paintFor(v.id), v.id in braking, suv = !isEgo && isSuv(v.id), indicator = v.indicator, blinkOn = blinkOn)
                val lvl = byId[v.id]?.riskLevel
                if (!isEgo && lvl.alerting()) brackets(r.topLeft, r.size, levelColor(lvl), pulse)
                else if (!isEgo && lvl == RiskLevel.UNKNOWN) unknownMark(r.topLeft, r.size, measurer)
                else if (!isEgo && ego != null)
                    drawText(measurer, "${DemoRiskAnalyzer.clearanceM(ego, v).roundToInt()}m",
                        Offset(r.right + 6f, r.top + r.height * 0.3f), TextStyle(fontSize = 12.sp, color = GRAY))
            }
            if (!gantryDrawn) gantryNow()
            // 화면 뒤쪽 위험 차량: 아래 가장자리 빛 + 그 차 쪽 위로 향하는 접근 화살표
            if (behindAlpha > 0.01f) {
                val c = levelColor(behind.maxByOrNull { if (byId[it.id]?.riskLevel == RiskLevel.WARNING) 1 else 0 }?.let { byId[it.id]?.riskLevel } ?: RiskLevel.WATCH)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = (0.22f + 0.25f * pulse) * behindAlpha)), h * 0.78f, h),
                    Offset(0f, h * 0.78f), Size(w, h * 0.22f))
                behind.forEach { v ->
                    val cx = p(v.x, ey - 2.0).x.coerceIn(40f, w - 40f)
                    for (i in 0..2) {
                        val y = h - 26f - i * 22f - pulse * 8f
                        drawPath(Path().apply { moveTo(cx - 18f, y); lineTo(cx, y - 14f); lineTo(cx + 18f, y) },
                            levelColor(byId[v.id]?.riskLevel).copy(alpha = (0.4f + 0.2f * i) * behindAlpha), style = Stroke(5f, cap = StrokeCap.Round))
                    }
                }
            }
        }

        // 위험 차량 위 상태 표시: 원인 + 상대속도·거리·TTC, 차량 지붕 위에 고정
        // 가까운 차부터 놓고, 먼저 놓인 라벨과 가로로 겹치면 그만큼 위로 쌓는다 (먼 차들이 지평선 근처에 몰릴 때)
        val placed = mutableListOf<Offset>()
        val labelW = with(density) { 260.dp.toPx() }
        val labelH = with(density) { 66.dp.toPx() }
        if (ego != null) alerting.sortedBy { it.y }.forEach { v ->
            val r = byId.getValue(v.id)
            val roofH = if (v.lengthM > 7) 3.4 else 1.45
            var top = p3(v.x, v.y - v.lengthM / 2, roofH + 0.6)
            while (placed.any { abs(it.x - top.x) < labelW && abs(it.y - top.y) < labelH }) top = top.copy(y = top.y - labelH)
            placed += top
            val rel = ((v.vy - ego.vy) * 3.6).roundToInt()
            Callout(reasonText(r.reasonCodes.firstOrNull()) ?: riskLabel(r.riskLevel), levelColor(r.riskLevel),
                Modifier.anchorAbove(top.x, top.y),
                detail = "${v.id} · ${DemoRiskAnalyzer.clearanceM(ego, v).roundToInt()}m · ${if (rel > 0) "+" else ""}$rel km/h" +
                    (r.ttcS?.let { " · TTC %.1f초".format(it) } ?: "") +
                    "\n예측: ${DemoRiskAnalyzer.maneuver(v, prevById[v.id], 0.5)} · ${signalText(v.indicator)}")
        }
        if (ego != null) behind.forEachIndexed { i, v ->
            val r = byId.getValue(v.id)
            val text = "▼ ${side(ego, v)} ${DemoRiskAnalyzer.clearanceM(ego, v).roundToInt()}m · " +
                (reasonText(r.reasonCodes.firstOrNull()) ?: riskLabel(r.riskLevel)) + (r.ttcS?.let { " · %.1f초".format(it) } ?: "")
            val leftSide = v.x < ex - 1.0
            Callout(text, levelColor(r.riskLevel), Modifier.offset {
                IntOffset((if (leftSide) w * 0.04f else w * 0.66f).roundToInt(), (h - 120f - i * 64f).roundToInt())
            }, pointer = false)
        }
    }
}

/** 화면 밖으로 나가지 않게, 기준점(cx, bottom) 바로 위 가운데에 놓는다. */
private fun Modifier.anchorAbove(cx: Float, bottom: Float) = layout { m, c ->
    val pl = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(c.maxWidth, c.maxHeight) {
        pl.place((cx - pl.width / 2f).roundToInt().coerceIn(0, maxOf(0, c.maxWidth - pl.width)),
            (bottom - pl.height).roundToInt().coerceIn(0, maxOf(0, c.maxHeight - pl.height)))
    }
}

private fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset, brush: Brush) =
    drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }, brush)

/** 한 번만 그려 비트맵으로 굳힌다(매 프레임 다시 그리지 않게). 배경·나무 스프라이트용. */
private fun bake(w: Int, h: Int, density: Density, block: DrawScope.() -> Unit): ImageBitmap {
    val img = ImageBitmap(maxOf(1, w), maxOf(1, h))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(img.width.toFloat(), img.height.toFloat()), block)
    return img
}

/** 결정적 의사난수(0..1). 배경 배치가 매번 같게. */
private fun rnd(i: Int, salt: Int): Float = (((i * 73856093) xor (salt * 19349663)) and 0x7fffffff) % 1000 / 1000f

/** 노을 하늘 · 대기 원근이 들어간 산 두 겹 · 세 겹 도시(먼 건물은 흐리게, 가까운 건물은 창 불빛·옥상 경고등). */
private fun DrawScope.scenery(w: Float, hy: Float, day: Boolean) {
    drawRect(Brush.verticalGradient(if (day) listOf(Color(0xFF5C93D6), Color(0xFF8DB8E8), Color(0xFFC4DCF2), Color(0xFFE9F1F8))
        else listOf(Color(0xFF0A1130), Color(0xFF232858), Color(0xFF5E4677), Color(0xFFC98572), Color(0xFFE9A276)), 0f, hy), size = Size(w, hy))
    drawCircle(Brush.radialGradient(if (day) listOf(Color(0x66FFFFFF), Color(0x22FFFFFF), Color.Transparent) else listOf(Color(0x88FFC08A), Color(0x22FFA070), Color.Transparent),
        Offset(w * 0.6f, hy), w * 0.35f), w * 0.35f, Offset(w * 0.6f, hy))
    // 옅은 구름 띠
    for (i in 0 until 5) {
        val cy = hy * (0.25f + 0.1f * i)
        drawOval(Color.White.copy(alpha = if (day) 0.35f else 0.035f), Offset(w * rnd(i, 1) - w * 0.2f, cy), Size(w * (0.4f + 0.3f * rnd(i, 2)), hy * 0.03f))
    }
    fun ridge(base: Float, amp: Float, seed: Double, top: Color, bottom: Color) = drawPath(Path().apply {
        moveTo(0f, hy)
        var x = 0f
        while (x <= w + 1f) {
            val u = (x / w).toDouble()
            val n = sin(u * 7 + seed) * 0.5 + sin(u * 17 + seed * 2) * 0.3 + sin(u * 41 + seed * 3) * 0.2
            lineTo(x, hy - base - amp * (0.5f + 0.5f * n.toFloat()))
            x += w / 120
        }
        lineTo(w, hy); close()
    }, Brush.verticalGradient(listOf(top, bottom), hy - base - amp, hy))
    ridge(hy * 0.10f, hy * 0.22f, 1.3, if (day) Color(0xFF9DB0C8) else Color(0xFF4A4374), if (day) Color(0xFF7F95AF) else Color(0xFF2C2E57))
    // 도시 (세 겹: 먼 → 가까운)
    (if (day) listOf(Triple(0.55f, Color(0xFFB9C7D8), 0), Triple(0.8f, Color(0xFF97A8BE), 1), Triple(1f, Color(0xFF7489A3), 2))
        else listOf(Triple(0.55f, Color(0xFF3A3866), 0), Triple(0.8f, Color(0xFF232646), 1), Triple(1f, Color(0xFF161A33), 2))).forEach { (scale, c, layer) ->
        var bx = w * (0.06f + 0.03f * layer)
        var i = 0
        while (bx < w * (0.62f - 0.04f * layer)) {
            val bw = w * (0.010f + 0.012f * rnd(i, layer + 3)) * scale
            val tall = rnd(i, layer + 7) > 0.88f
            val bh = hy * (0.04f + 0.14f * rnd(i, layer + 5) + if (tall) 0.12f else 0f) * scale
            drawRect(Brush.verticalGradient(listOf(lerpColor(c, Color.White, 0.06f), c), hy - bh, hy), Offset(bx, hy - bh), Size(bw, bh))
            if (layer == 2) {   // 창 불빛 격자
                var wy = hy - bh + 5f
                while (wy < hy - 4f) {
                    var wx = bx + 2f
                    while (wx < bx + bw - 3f) {
                        if (rnd((wx * 7 + wy * 13).toInt(), i) > 0.62f) drawRect(if (day) Color(0x59DCEBFA) else Color(0xB3FFD58A), Offset(wx, wy), Size(2.2f, 1.8f))
                        wx += 5f
                    }
                    wy += 6f
                }
                if (tall) {
                    drawLine(Color(0xFF2A2E4E), Offset(bx + bw / 2, hy - bh), Offset(bx + bw / 2, hy - bh - 14f), 2f)
                    drawCircle(Color(0xFFFF4040), 2.2f, Offset(bx + bw / 2, hy - bh - 14f))
                }
            }
            bx += bw * (1.05f + 0.6f * rnd(i, layer + 9))
            i++
        }
    }
    ridge(hy * 0.005f, hy * 0.06f, 4.1, if (day) Color(0xFF5E7A66) else Color(0xFF141831), if (day) Color(0xFF4D6A55) else Color(0xFF0D1122))
    // 지평선 근처 대기(안개) 빛
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, if (day) Color(0x55FFFFFF) else Color(0x33E9A276)), hy * 0.85f, hy), Offset(0f, hy * 0.85f), Size(w, hy * 0.15f))
}

/** 나무 스프라이트 2종(침엽수 · 활엽수). 잎 덩어리를 여러 겹 그라데이션으로. 256×384 px, 밑동 중앙이 기준점. */
private fun treeSprites(density: Density): List<ImageBitmap> = listOf(
    bake(256, 384, density) {
        drawRect(Color(0xFF2B2018), Offset(118f, 300f), Size(20f, 84f))
        for (layer in 0 until 5) {
            val t = layer / 4f
            val baseY = 330f - layer * 62f
            val half = 120f - layer * 22f
            drawPath(Path().apply { moveTo(128f - half, baseY); lineTo(128f + half, baseY); lineTo(128f, baseY - 120f); close() },
                Brush.horizontalGradient(listOf(Color(0xFF0B2219), lerpColor(Color(0xFF173B2C), Color(0xFF2A5A44), t), Color(0xFF0E281E)), 128f - half, 128f + half))
        }
    },
    bake(256, 384, density) {
        drawRect(Color(0xFF30241B), Offset(116f, 250f), Size(24f, 134f))
        listOf(Triple(128f, 170f, 92f), Triple(80f, 210f, 70f), Triple(176f, 205f, 72f), Triple(105f, 120f, 70f),
            Triple(160f, 125f, 66f), Triple(128f, 80f, 58f), Triple(128f, 230f, 76f)).forEach { (cx, cy, r) ->
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2E5E45), Color(0xFF173B2C), Color(0xFF0A1E16)), Offset(cx + r * 0.25f, cy - r * 0.3f), r * 1.2f), r, Offset(cx, cy))
        }
    },
)

private val PAINTS = listOf(Color(0xFFE6E8EC), Color(0xFFA8AFBA), Color(0xFF4B515C), Color(0xFF22262E), Color(0xFF2E4C80), Color(0xFF8A1F27), Color(0xFFC9CED6), Color(0xFF3D4A3F))
private fun paintFor(id: String) = PAINTS[(id.hashCode() and 0x7fffffff) % PAINTS.size]
/** 차종 모양 구분(시각용). 데이터 계약에 차종 필드가 없어 길이 7m 초과만 화물차로 보고, 나머지는 ID 로 세단/SUV 모양만 고른다. */
private fun isSuv(id: String) = (id.hashCode() and 0x7fffffff) % 3 == 0

/** 옆면(평면 x = sx)에 놓인 바퀴: 원을 투영한 타원 + 휠 림. */
private fun DrawScope.wheel(p3: (Double, Double, Double) -> Offset, sx: Double, wy: Double, r: Double) {
    fun ring(rr: Double) = Path().apply {
        for (i in 0..20) {
            val a = 2 * PI * i / 20
            val q = p3(sx, wy + rr * cos(a), r + rr * sin(a))
            if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
        }
        close()
    }
    drawPath(ring(r), Color(0xFF0B0D10))
    drawPath(ring(r * 0.62), Brush.linearGradient(listOf(Color(0xFF9AA3AF), Color(0xFF3E444D)), p3(sx, wy, r * 1.6), p3(sx, wy, 0.0)))
    drawPath(ring(r * 0.2), Color(0xFF2A2E35))
}

/**
 * 3D 차량. 승용차(세단/SUV) = 둥근 모서리 하부 차체 + 휠·아치 + 옆유리 + 경사진 뒷유리(반사) + 지붕 하이라이트
 * + 감싸는 후미등·라이트바·번호판, 화물차(길이 7m 초과) = 캡 + 리브 있는 적재함 + 반사 테이프·흙받이.
 * 카메라 쪽 옆면·윗면·후면만 그린다(Canvas 에 깊이 버퍼가 없어 보이는 면만 먼 것부터).
 * 브레이크 중이면 후미등·보조제동등이 밝아지고 번지며 노면에 붉은 반사가 생긴다.
 * 반환: 화면에서 차량이 차지하는 경계 상자(추적 꺾쇠·라벨 위치용).
 */
private fun DrawScope.car3d(v: VehicleState, p3: (Double, Double, Double) -> Offset, camX: Double, body: Color, brake: Boolean, suv: Boolean,
                            indicator: String? = null, blinkOn: Boolean = false): Rect {
    val hw = v.widthM / 2
    val y0 = v.y - v.lengthM / 2
    val y1 = v.y + v.lengthM / 2
    val xl = v.x - hw
    val xr = v.x + hw
    val px = p3(v.x + 1, y0, 0.0).x - p3(v.x, y0, 0.0).x      // 차 뒤 깊이에서 1m 당 픽셀
    fun face(a: Offset, b: Offset, c: Offset, d: Offset, brush: Brush) = quad(a, b, c, d, brush)
    fun poly(pts: List<Offset>, brush: Brush) =
        drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close() }, brush)
    fun solid(c: Color) = SolidColor(c)
    val sideX = when { camX > xr + 0.05 -> xr; camX < xl - 0.05 -> xl; else -> null }   // 카메라 쪽 옆면
    // 방향지시등: 차량 자신의 진행 방향(+y, heading ≈ π/2) 기준 왼쪽 = xl. 화면 좌우가 아니라 차량 기준으로 고른다
    fun turnLamps(rearUp: Double, frontUp: Double) {
        listOf("LEFT" to xl, "RIGHT" to xr).forEach { (name, x) ->
            val on = blinkOn && (indicator == name || indicator == "HAZARD")
            val c = if (on) Color(0xFFFFA726) else Color(0xFF7A531C)
            val inner = if (name == "LEFT") x + 0.32 else x - 0.32
            val a = minOf(x, inner); val b = maxOf(x, inner)
            face(p3(a, y0 - 0.02, rearUp), p3(b, y0 - 0.02, rearUp), p3(b, y0 - 0.02, rearUp + 0.07), p3(a, y0 - 0.02, rearUp + 0.07), solid(c))
            if (sideX == x) face(p3(x, y1 - 0.4, frontUp), p3(x, y1 - 0.06, frontUp), p3(x, y1 - 0.06, frontUp + 0.08), p3(x, y1 - 0.4, frontUp + 0.08), solid(c))
            if (on) {
                val cpos = p3((a + b) / 2, y0, rearUp + 0.035); val r = maxOf(6f, 0.6f * px)
                drawCircle(Brush.radialGradient(listOf(Color(0xCCFFB040), Color.Transparent), cpos, r), r, cpos)
            }
        }
    }

    // 부드러운 그림자: 바깥으로 갈수록 옅게 세 겹 + 바닥 접지 그림자
    listOf(0.75 to 0.08f, 0.4 to 0.14f, 0.12 to 0.30f).forEach { (m, a) ->
        face(p3(xl - m, y0 - m * 1.3, 0.0), p3(xr + m, y0 - m * 1.3, 0.0), p3(xr + m, y1 + m * 0.5, 0.0), p3(xl - m, y1 + m * 0.5, 0.0), solid(Color.Black.copy(alpha = a)))
    }
    if (brake) {
        val a = p3(v.x, y0, 0.0).y; val b = p3(v.x, y0 - 2.5, 0.0).y
        face(p3(xl, y0, 0.0), p3(xr, y0, 0.0), p3(xr + 0.3, y0 - 2.5, 0.0), p3(xl - 0.3, y0 - 2.5, 0.0),
            Brush.verticalGradient(listOf(Color(0x40FF2A2A), Color.Transparent), a, b))
    }

    if (v.lengthM > 7) {   // 화물차
        val base = 0.55; val top = 3.4; val cabY = y1 - 2.3
        sideX?.let { sx ->
            listOf(y0 + 1.0, y0 + 2.1, y1 - 1.0).forEach { wy -> wheel(p3, sx, wy, 0.5) }
            // 적재함 옆면 + 세로 리브
            face(p3(sx, y0, base), p3(sx, cabY, base), p3(sx, cabY, top), p3(sx, y0, top),
                Brush.verticalGradient(listOf(Color(0xFFC3C9D2), Color(0xFF8E96A3)), p3(sx, v.y, top).y, p3(sx, v.y, base).y))
            var ry = y0 + 0.6
            while (ry < cabY - 0.2) { drawLine(Color.Black.copy(alpha = 0.10f), p3(sx, ry, base + 0.1), p3(sx, ry, top - 0.1), maxOf(1f, 0.02f * px)); ry += 0.6 }
            face(p3(sx, y0 + 0.2, base + 0.05), p3(sx, cabY - 0.2, base + 0.05), p3(sx, cabY - 0.2, base + 0.15), p3(sx, y0 + 0.2, base + 0.15),
                SolidColor(Color(0xFFE53935)))   // 측면 반사 테이프
            // 캡 (적재함보다 낮고 앞쪽)
            face(p3(sx, cabY + 0.1, base), p3(sx, y1, base), p3(sx, y1 - 0.2, 2.9), p3(sx, cabY + 0.1, 2.9), SolidColor(lerpColor(body, Color.Black, 0.3f)))
            face(p3(sx, cabY + 0.4, 1.7), p3(sx, y1 - 0.4, 1.7), p3(sx, y1 - 0.5, 2.6), p3(sx, cabY + 0.4, 2.6), SolidColor(Color(0xFF1A2230)))
        }
        face(p3(xl, y0, top), p3(xr, y0, top), p3(xr, cabY, top), p3(xl, cabY, top), solid(Color(0xFFDDE1E7)))
        // 뒷문: 두 짝 + 경첩 + 손잡이 + 반사 테이프(적·백) + 흙받이 + 하부 보호대
        val tl = p3(xl, y0, top); val br = p3(xr, y0, base)
        face(p3(xl, y0, base), p3(xr, y0, base), p3(xr, y0, top), p3(xl, y0, top),
            Brush.verticalGradient(listOf(Color(0xFFF0F2F5), Color(0xFFC0C7D1)), tl.y, br.y))
        drawLine(Color(0xFF8E96A3), p3(v.x, y0, base + 0.05), p3(v.x, y0, top - 0.05), maxOf(1f, 0.03f * px))
        listOf(xl + 0.1, xr - 0.1).forEach { x -> listOf(0.9, 1.9, 2.9).forEach { hz -> drawLine(Color(0xFF6F7784), p3(x - 0.08, y0, hz), p3(x + 0.08, y0, hz), maxOf(1.5f, 0.05f * px)) } }
        listOf(v.x - 0.15, v.x + 0.15).forEach { x -> drawLine(Color(0xFF5E6672), p3(x, y0, 1.2), p3(x, y0, 2.2), maxOf(1.5f, 0.04f * px)) }
        var tx = xl + 0.05
        var red = true
        while (tx < xr - 0.05) {
            face(p3(tx, y0, base + 0.05), p3(minOf(tx + 0.25, xr - 0.05), y0, base + 0.05), p3(minOf(tx + 0.25, xr - 0.05), y0, base + 0.15), p3(tx, y0, base + 0.15),
                solid(if (red) Color(0xFFE53935) else Color(0xFFF5F5F5)))
            red = !red; tx += 0.25
        }
        face(p3(xl + 0.15, y0 - 0.05, 0.28), p3(xr - 0.15, y0 - 0.05, 0.28), p3(xr - 0.15, y0 - 0.05, 0.42), p3(xl + 0.15, y0 - 0.05, 0.42), solid(Color(0xFF1C1F25)))
        listOf(xl + 0.1, xr - 0.5).forEach { x ->
            face(p3(x, y0 + 0.2, 0.0), p3(x + 0.4, y0 + 0.2, 0.0), p3(x + 0.4, y0 + 0.2, 0.9), p3(x, y0 + 0.2, 0.9), solid(Color(0xFF0B0D10)))   // 복륜
            face(p3(x, y0 + 0.05, 0.1), p3(x + 0.4, y0 + 0.05, 0.1), p3(x + 0.4, y0 + 0.05, 0.55), p3(x, y0 + 0.05, 0.55), solid(Color(0xFF15181D)))  // 흙받이
        }
        val lamp = if (brake) Color(0xFFFF3030) else Color(0xFFB0141C)
        listOf(xl + 0.1, xr - 0.45).forEach { x -> face(p3(x, y0 - 0.02, 0.6), p3(x + 0.35, y0 - 0.02, 0.6), p3(x + 0.35, y0 - 0.02, 0.8), p3(x, y0 - 0.02, 0.8), solid(lamp)) }
        if (brake) glow(p3(xl + 0.28, y0, 0.7), p3(xr - 0.28, y0, 0.7), px)
        turnLamps(0.85, 1.0)
        return bounds(listOf(p3(xl, y0, top), p3(xr, y0, top), p3(xl, y0, 0.0), p3(xr, y0, 0.0), sideX?.let { p3(it, y1, top) }))
    }

    // 승용차
    val roof = if (suv) 1.66 else 1.44
    val belt = if (suv) 0.95 else 0.80
    val cy0 = y0 + if (suv) 0.55 else 1.05          // 지붕 뒤끝 (SUV 는 뒷유리가 거의 세워져 있음)
    val cy1 = y1 - 1.7                               // 지붕 앞끝
    val gw = hw * 0.80
    val shade = lerpColor(body, Color.Black, 0.45f)
    val mid = lerpColor(body, Color.Black, 0.18f)
    val hi = lerpColor(body, Color.White, 0.35f)
    val glassTop = Color(0xFF7084A0)
    val glassBot = Color(0xFF121925)
    // 1) 카메라 쪽 바퀴 (차체보다 먼저 → 윗부분은 휠 아치 안으로 가려짐)
    sideX?.let { sx -> listOf(y0 + 0.85, y1 - 0.95).forEach { wy -> wheel(p3, sx, wy, 0.34) } }
    // 2) 옆면: 앞뒤 모서리를 깎은 하부 차체 + 휠 아치 테 + 옆유리 + 캐릭터 라인
    sideX?.let { sx ->
        val gx = v.x + sign(sx - v.x) * gw
        poly(listOf(p3(sx, y0 + 0.1, 0.34), p3(sx, y0 + 0.45, 0.34), p3(sx, y0 + 0.6, 0.62), p3(sx, y0 + 1.1, 0.62), p3(sx, y0 + 1.25, 0.34),
            p3(sx, y1 - 1.3, 0.34), p3(sx, y1 - 1.15, 0.62), p3(sx, y1 - 0.75, 0.62), p3(sx, y1 - 0.6, 0.34), p3(sx, y1 - 0.15, 0.34),
            p3(sx, y1, 0.55), p3(sx, y1 - 0.25, belt), p3(sx, y0 + 0.15, belt), p3(sx, y0, 0.62)),
            Brush.verticalGradient(listOf(mid, shade), p3(sx, v.y, belt).y, p3(sx, v.y, 0.34).y))
        poly(listOf(p3(sx, y0 + 0.45, belt), p3(sx, y1 - 0.9, belt), p3(gx, cy1, roof - 0.05), p3(gx, cy0 + 0.05, roof - 0.05)),
            Brush.verticalGradient(listOf(glassTop, glassBot), p3(gx, v.y, roof).y, p3(sx, v.y, belt).y))
        drawLine(shade, p3(sx, v.y - 0.1, belt), p3(gx, v.y - 0.25, roof - 0.05), maxOf(1.5f, 0.06f * px))   // B 필러
        drawLine(hi.copy(alpha = 0.55f), p3(sx, y0 + 0.2, belt - 0.12), p3(sx, y1 - 0.25, belt - 0.12), maxOf(1f, 0.02f * px))
    }
    // 3) 트렁크·후드 윗면
    poly(listOf(p3(xl + 0.06, y0 + 0.18, belt), p3(xr - 0.06, y0 + 0.18, belt), p3(xr - 0.08, y1 - 0.2, belt), p3(xl + 0.08, y1 - 0.2, belt)),
        solid(lerpColor(body, Color.White, 0.10f)))
    // 4) 지붕: 가운데가 밝은 곡면 반사
    val r0 = p3(v.x - gw, cy0, roof); val r1 = p3(v.x + gw, cy0, roof)
    poly(listOf(r0, r1, p3(v.x + gw * 0.94, cy1, roof), p3(v.x - gw * 0.94, cy1, roof)), Brush.horizontalGradient(listOf(mid, hi, mid), r0.x, r1.x))
    // 5) 뒷유리 + 사선 반사
    val rw = listOf(p3(xl + 0.14, y0 + 0.28, belt), p3(xr - 0.14, y0 + 0.28, belt), p3(v.x + gw, cy0, roof), p3(v.x - gw, cy0, roof))
    poly(rw, Brush.verticalGradient(listOf(glassTop, glassBot), rw[2].y, rw[0].y))
    fun lerpO(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    drawLine(Color.White.copy(alpha = 0.16f), lerpO(rw[3], rw[2], 0.25f), lerpO(rw[0], rw[1], 0.55f), maxOf(2f, 0.12f * px))
    // 6) 후면 패널: 모서리를 깎아 둥글게 + 위→아래 음영
    val rf = listOf(p3(xl + 0.15, y0, 0.24), p3(xr - 0.15, y0, 0.24), p3(xr, y0 + 0.06, 0.4), p3(xr, y0 + 0.14, belt - 0.06),
        p3(xr - 0.12, y0 + 0.2, belt), p3(xl + 0.12, y0 + 0.2, belt), p3(xl, y0 + 0.14, belt - 0.06), p3(xl, y0 + 0.06, 0.4))
    poly(rf, Brush.verticalGradient(listOf(hi, body, mid), rf[5].y, rf[0].y))
    drawLine(hi.copy(alpha = 0.6f), p3(xl + 0.1, y0 + 0.08, 0.5), p3(xr - 0.1, y0 + 0.08, 0.5), maxOf(1f, 0.015f * px))
    // 7) 범퍼 하단·디퓨저
    poly(listOf(p3(xl + 0.12, y0 - 0.03, 0.12), p3(xr - 0.12, y0 - 0.03, 0.12), p3(xr - 0.02, y0, 0.3), p3(xl + 0.02, y0, 0.3)), solid(Color(0xFF14171C)))
    // 8) 후미등: 좌우 램프 + 가로 라이트바 (+ 옆으로 감싸는 부분)
    val lampOn = if (brake) Color(0xFFFF3B3B) else Color(0xFFC0161F)
    val lampDim = if (brake) Color(0xFFFF8A8A) else Color(0xFF7A0E15)
    face(p3(xl + 0.1, y0 + 0.03, 0.63), p3(xr - 0.1, y0 + 0.03, 0.63), p3(xr - 0.1, y0 + 0.03, 0.67), p3(xl + 0.1, y0 + 0.03, 0.67), solid(lampOn))
    listOf(xl + 0.03 to xl + 0.46, xr - 0.46 to xr - 0.03).forEach { (a, b) ->
        val top = p3(a, y0 + 0.04, 0.72).y; val bot = p3(a, y0 + 0.04, 0.55).y
        poly(listOf(p3(a, y0 + 0.04, 0.55), p3(b, y0 + 0.04, 0.57), p3(b, y0 + 0.04, 0.71), p3(a, y0 + 0.04, 0.72)), Brush.verticalGradient(listOf(lampOn, lampDim), top, bot))
    }
    sideX?.let { sx -> face(p3(sx, y0 + 0.04, 0.56), p3(sx, y0 + 0.4, 0.6), p3(sx, y0 + 0.4, 0.68), p3(sx, y0 + 0.04, 0.72), solid(lampOn)) }
    face(p3(v.x - 0.27, y0 - 0.01, 0.33), p3(v.x + 0.27, y0 - 0.01, 0.33), p3(v.x + 0.27, y0 - 0.01, 0.47), p3(v.x - 0.27, y0 - 0.01, 0.47), solid(Color(0xFF2A2E36)))
    face(p3(v.x - 0.25, y0 - 0.02, 0.345), p3(v.x + 0.25, y0 - 0.02, 0.345), p3(v.x + 0.25, y0 - 0.02, 0.455), p3(v.x - 0.25, y0 - 0.02, 0.455), solid(Color(0xFFE6E9EE)))
    if (brake) {
        glow(p3(xl + 0.24, y0, 0.63), p3(xr - 0.24, y0, 0.63), px)
        face(p3(v.x - 0.3, cy0, roof - 0.05), p3(v.x + 0.3, cy0, roof - 0.05), p3(v.x + 0.3, cy0, roof), p3(v.x - 0.3, cy0, roof), solid(Color(0xFFFF3434)))
    }
    turnLamps(0.46, 0.62)
    return bounds(listOf(r0, r1, p3(xl, y0, 0.0), p3(xr, y0, 0.0), p3(v.x - gw, cy1, roof), sideX?.let { p3(it, y1, 0.34) }))
}


private fun DrawScope.glow(a: Offset, b: Offset, px: Float) = listOf(a, b).forEach { c ->
    val r = maxOf(6f, 0.7f * px)
    drawCircle(Brush.radialGradient(listOf(Color(0x99FF2A2A), Color.Transparent), c, r), r, c)
}

private fun bounds(pts: List<Offset?>): Rect {
    val q = pts.filterNotNull()
    return Rect(q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y })
}

/** 노면 위 띠 화살표: 세계 좌표 점들을 폭 width(m) 띠로 투영하고 끝에 화살촉. */
private fun DrawScope.roadArrow(pts: List<Pair<Double, Double>>, width: Double, c: Color, p: (Double, Double) -> Offset) {
    if (pts.size < 2) return
    fun normal(i: Int): Pair<Double, Double> {
        val a = pts[maxOf(0, i - 1)]; val b = pts[minOf(pts.lastIndex, i + 1)]
        val dx = b.first - a.first; val dy = b.second - a.second
        val len = maxOf(1e-6, Math.hypot(dx, dy))
        return (-dy / len) to (dx / len)
    }
    val body = pts.dropLast(1)
    val leftSide = body.indices.map { i -> val n = normal(i); p(body[i].first + n.first * width / 2, body[i].second + n.second * width / 2) }
    val rightSide = body.indices.map { i -> val n = normal(i); p(body[i].first - n.first * width / 2, body[i].second - n.second * width / 2) }
    val path = Path().apply {
        moveTo(leftSide[0].x, leftSide[0].y)
        leftSide.drop(1).forEach { lineTo(it.x, it.y) }
        rightSide.reversed().forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, c.copy(alpha = 0.75f))
    val end = pts.last(); val base = body.last(); val n = normal(pts.lastIndex)
    drawPath(Path().apply {
        val a = p(base.first + n.first * width * 1.3, base.second + n.second * width * 1.3); moveTo(a.x, a.y)
        val b = p(end.first, end.second); lineTo(b.x, b.y)
        val d = p(base.first - n.first * width * 1.3, base.second - n.second * width * 1.3); lineTo(d.x, d.y); close()
    }, c)
}


/** 판단 보류 차량: 파란 점선 테두리 + "?" (색만으로 구분하지 않도록 기호 병기). */
private fun DrawScope.unknownMark(tl: Offset, sz: Size, measurer: androidx.compose.ui.text.TextMeasurer) {
    val a = tl - Offset(6f, 6f)
    val b = Size(sz.width + 12f, sz.height + 12f)
    drawRoundRect(UNK.copy(alpha = 0.08f), a, b, CornerRadius(10f))
    drawRoundRect(UNK, a, b, CornerRadius(10f), style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
    val r = 15f
    val c = Offset(a.x + b.width, a.y)
    drawCircle(Color.White, r + 2f, c); drawCircle(UNK, r, c)
    val tl2 = measurer.measure("?", TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White))
    drawText(tl2, topLeft = c - Offset(tl2.size.width / 2f, tl2.size.height / 2f))
}

/** 화면 사각형 둘레 애니메이션 꺾쇠(바운딩 박스). */
private fun DrawScope.brackets(tl: Offset, sz: Size, c: Color, pulse: Float) {
    val pad = 6f + 6f * pulse
    val a = tl - Offset(pad, pad)
    val b = Size(sz.width + pad * 2, sz.height + pad * 2)
    drawRoundRect(c.copy(alpha = 0.08f + 0.10f * pulse), a, b, CornerRadius(10f))
    val kk = minOf(b.width, b.height) * 0.28f
    listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f), Offset(1f, 1f)).forEach { d ->
        val corner = a + Offset(b.width * d.x, b.height * d.y)
        val sx = if (d.x == 0f) 1f else -1f
        val sy = if (d.y == 0f) 1f else -1f
        drawLine(c, corner, corner + Offset(kk * sx, 0f), 5f, StrokeCap.Round)
        drawLine(c, corner, corner + Offset(0f, kk * sy), 5f, StrokeCap.Round)
    }
}

// ───────────────────────── 360° 어라운드뷰 ─────────────────────────

private const val BEHIND_M = 13f

@Composable
private fun AroundView(t: ScenarioTimeline, vehicles: List<VehicleState>, ego: VehicleState?, assessment: RiskFrame?,
                       prediction: PredictionFrame?, positionMs: Long) {
    val road = fourLaneRoad(t, positionMs)
    val latTable = remember(t) { lateralTable(t) }
    val lat = latTable.at(positionMs)
    val byId = assessment?.riskObjects?.associateBy { it.vehicleId }.orEmpty()
    val measurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "bbox")
    // 방향지시등 점멸: 약 1.5Hz (분당 90회, 일정한 주기)
    val blinkPhase by rememberInfiniteTransition(label = "blink").animateFloat(0f, 1f,
        infiniteRepeatable(tween(667, easing = LinearEasing), RepeatMode.Restart), label = "blink")
    val blinkOn = blinkPhase < 0.5f
    val alerting = vehicles.filter { it.id != ego?.id && byId[it.id]?.riskLevel.alerting() }
    val zoneAlpha by animateFloatAsState(if (alerting.isEmpty()) 0f else 1f, tween(450), label = "zone")
    val density = LocalDensity.current
    // 위험 차량이 항상 보이도록 앞쪽 범위를 자동으로 넓힘(부드럽게)
    val needAhead = alerting.maxOfOrNull { (it.y - (ego?.y ?: 0.0) + it.lengthM).toFloat() + 6f } ?: 0f
    val ahead by animateFloatAsState(needAhead.coerceIn(26f, 60f), tween(600), label = "zoom")

    // 위에서 똑바로 내려다보는 탑뷰 (원근 기울임은 차선이 비스듬해 보여 뺐음)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val s = h / (ahead + BEHIND_M)           // 1m 당 픽셀
        val cy = ahead * s
        val ex = ego?.x ?: 0.0
        val ey = ego?.y ?: 0.0
        val lw = road.laneWidthM.toDouble()
        // shortcut: 자차가 차선 중앙이라고 가정. 차선 형상이 계약에 들어오면 그 값으로 교체
        val left = ex - (road.egoLaneIndex + 0.5) * lw - lat
        val right = ex + (road.laneCount - road.egoLaneIndex - 0.5) * lw - lat
        // 기울임이 화면 중앙 기준이라 도로 중앙을 화면 중앙에 둬야 좌우로 쏠려 보이지 않음
        val cx = w / 2 - (((left + right) / 2 - ex) * s).toFloat()
        fun p(x: Double, y: Double) = Offset(cx + ((x - ex) * s).toFloat(), cy - ((y - ey) * s).toFloat())

        Canvas(Modifier.fillMaxSize()) {
            // 바깥: 밝은 갓길·녹지
            drawRect(Brush.horizontalGradient(if (PrismTheme.palette.dark) listOf(Color(0xFF0E1714), Color(0xFF14201B), Color(0xFF0E1714)) else listOf(Color(0xFFE3EBE0), Color(0xFFEDF2EA), Color(0xFFE3EBE0))))
            val l = p(left - 1.2, 0.0).x
            val r = p(right + 1.2, 0.0).x
            drawRect(Color(0xFF6B7380), Offset(l - 10f, 0f), Size(8f, size.height))        // 가드레일
            drawRect(Color(0xFF6B7380), Offset(r + 2f, 0f), Size(8f, size.height))
            drawRect(Brush.horizontalGradient(if (PrismTheme.palette.dark) listOf(Color(0xFF262A33), Color(0xFF30353F), Color(0xFF262A33)) else listOf(Color(0xFFCDD4DD), Color(0xFFD9DFE6), Color(0xFFCDD4DD)), l, r),
                Offset(l, 0f), Size(r - l, size.height))
            // 차선: 좌측 황색 실선, 우측 백색 실선, 사이 점선(주행거리만큼 흐름)
            drawLine(Color(0xFFF2C14E), Offset(p(left, 0.0).x, 0f), Offset(p(left, 0.0).x, size.height), 0.16f * s)
            drawLine(Color.White, Offset(p(right, 0.0).x, 0f), Offset(p(right, 0.0).x, size.height), 0.16f * s)
            val odo = ((positionMs / 1000.0) * (ego?.vy ?: 0.0)) % 12.0
            for (i in 1 until road.laneCount) {
                val x = p(left + i * lw, 0.0).x
                var y = ey - BEHIND_M - 12 - odo
                while (y < ey + ahead + 12) {
                    drawLine(Color.White, Offset(x, p(0.0, y).y), Offset(x, p(0.0, y + 5).y), 0.14f * s, StrokeCap.Round)
                    y += 12
                }
            }
            // 거리 눈금
            listOf(-10, 10, 20, 30, 40, 50).filter { it < ahead }.forEach { d ->
                val y = p(0.0, ey + d).y
                drawLine(if (PrismTheme.palette.dark) Color(0x1FFFFFFF) else Color(0x33364A6B), Offset(l, y), Offset(r, y), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                drawText(measurer, "${abs(d)}m", Offset(r + 16f, y - 14f), TextStyle(fontSize = 12.sp, color = GRAY))
            }

            // 예상 위험 영역 (거리가 가까울수록 진하게)
            if (ego != null) alerting.forEach { v ->
                val gap = DemoRiskAnalyzer.clearanceM(ego, v)
                val strength = (1f - gap.toFloat() / 30f).coerceIn(0.25f, 1f) * zoneAlpha
                riskZone(ego, v, ::p, levelColor(byId[v.id]?.riskLevel), strength)
            }
            // 자차 감지 영역 (센서 부채꼴 + 안전 링)
            ego?.let { safetyZone(p(it.x, it.y), it, s) }

            // 예측 경로 (01 prediction) - 위험 차량만
            alerting.forEach { v ->
                val tr = prediction?.predictions?.firstOrNull { it.vehicleId == v.id }?.trajectories?.maxByOrNull { it.confidence ?: 0.0 }
                val pts = tr?.points?.map { p(it.x, it.y) }
                if (pts != null && pts.size >= 2) arrowPath(listOf(p(v.x, v.y)) + pts, levelColor(byId[v.id]?.riskLevel), dashed = true)
                // 차선 변경: 실제 가로 속도 방향·1.5초 이동량으로 휘는 화살표
                if (abs(v.vx) > 0.4) {
                    val dir = sign(v.vx)
                    val from = p(v.x + dir * v.widthM * 0.7, v.y)
                    val to = p(v.x + v.vx * 1.5 + dir * v.widthM * 0.5, v.y + 6)
                    arrowPath(listOf(from, Offset((from.x + to.x) / 2, from.y - 0.8f * s), to), WATCH, dashed = false, width = 0.35f * s)
                }
            }

            // 차량 (자차 마지막)
            vehicles.filter { it.id != ego?.id }.forEach { v ->
                val lvl = byId[v.id]?.riskLevel
                topCar(p(v.x, v.y), v, s, bodyFor(v.id), false)
                turnDots(v, ::p, s, blinkOn)
                if (lvl.alerting()) bbox(p(v.x, v.y), v, s, levelColor(lvl), pulse)
                else if (lvl == RiskLevel.UNKNOWN) {
                    val c = p(v.x, v.y)
                    val hw = v.widthM.toFloat() * s / 2 + 0.4f * s
                    val hl = v.lengthM.toFloat() * s / 2 + 0.4f * s
                    unknownMark(c - Offset(hw, hl), Size(hw * 2, hl * 2), measurer)
                }
                else if (ego != null) {
                    val gap = DemoRiskAnalyzer.clearanceM(ego, v).roundToInt()
                    val c = p(v.x, v.y)
                    drawText(measurer, "${gap}m", Offset(c.x + v.widthM.toFloat() * s * 0.65f, c.y - 10f), TextStyle(fontSize = 12.sp, color = GRAY))
                }
            }
            ego?.let { topCar(p(it.x, it.y), it, s, Color(0xFFFDFDFE), true); turnDots(it, ::p, s, blinkOn) }
        }

        // 위험 차량 말풍선: 방향 · 거리 · 원인
        if (ego != null) alerting.forEach { v ->
            val r = byId.getValue(v.id)
            val c = p(v.x, v.y)
            val gap = DemoRiskAnalyzer.clearanceM(ego, v).roundToInt()
            val text = "${side(ego, v)} ${gap}m · " + (reasonText(r.reasonCodes.firstOrNull()) ?: riskLabel(r.riskLevel)) +
                (r.ttcS?.let { " · %.1f초".format(it) } ?: "")
            Callout(text, levelColor(r.riskLevel), Modifier.offset {
                IntOffset((c.x + v.widthM.toFloat() * s * 0.9f).roundToInt().coerceIn(0, (w - 360f).roundToInt()),
                    (c.y - v.lengthM.toFloat() * s * 0.5f - 34f).roundToInt().coerceIn(150, (h - 60f).roundToInt()))
            })
        }
    }
}

private val BODIES = listOf(Color(0xFFE9EDF2), Color(0xFFB9C3CF), Color(0xFF7C8796), Color(0xFFD8DEE6), Color(0xFF55606E), Color(0xFFC7CED8))
private fun bodyFor(id: String) = BODIES[(id.hashCode() and 0x7fffffff) % BODIES.size]

/** 탑뷰 방향지시등: 앞·뒤 모서리(차량 진행 방향 기준 왼쪽 = -x)를 같은 상태로 점멸. */
private fun DrawScope.turnDots(v: VehicleState, p: (Double, Double) -> Offset, s: Float, blinkOn: Boolean) {
    val ind = v.indicator ?: return
    if (!blinkOn || ind == "OFF") return
    listOf("LEFT" to -1.0, "RIGHT" to 1.0).filter { ind == it.first || ind == "HAZARD" }.forEach { (_, sx) ->
        listOf(-1.0, 1.0).forEach { sy ->
            val c = p(v.x + sx * v.widthM * 0.45, v.y + sy * v.lengthM * 0.45)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB040), Color.Transparent), c, 0.7f * s), 0.7f * s, c)
            drawCircle(Color(0xFFFFA726), 0.18f * s, c)
        }
    }
}

/** 위에서 본 차량. 앞(진행 방향)이 화면 위. 트럭(길이 7m 초과)은 캡 + 적재함. */
private fun DrawScope.topCar(center: Offset, v: VehicleState, s: Float, body: Color, isEgo: Boolean) {
    val len = v.lengthM.toFloat() * s
    val wid = v.widthM.toFloat() * s
    val deg = 90f - (v.headingRad * 180 / PI).toFloat()
    translate(center.x, center.y) {
        rotate(deg, Offset.Zero) {
            // 그림자 (두 겹으로 부드럽게)
            drawRoundRect(Color.Black.copy(alpha = 0.06f), Offset(-wid * 0.62f, -len * 0.47f), Size(wid * 1.34f, len * 1.04f), CornerRadius(wid * 0.5f))
            drawRoundRect(Color.Black.copy(alpha = 0.14f), Offset(-wid * 0.44f, -len * 0.47f), Size(wid * 1.02f, len), CornerRadius(wid * 0.4f))
            if (len > 7f * s) truck(len, wid, body) else sedan(len, wid, body, isEgo)
        }
    }
}

private fun DrawScope.sedan(len: Float, wid: Float, body: Color, isEgo: Boolean) {
    val shade = lerpColor(body, Color.Black, 0.18f)
    val light = lerpColor(body, Color.White, 0.35f)
    val tl = Offset(-wid / 2, -len / 2)
    if (isEgo) drawRoundRect(ACCENT.copy(alpha = 0.55f), tl - Offset(4f, 4f), Size(wid + 8f, len + 8f), CornerRadius(wid * 0.42f), style = Stroke(3f))
    drawRoundRect(Brush.horizontalGradient(listOf(shade, body, light, body, shade), tl.x, tl.x + wid), tl, Size(wid, len), CornerRadius(wid * 0.4f))
    // 앞유리 · 지붕 · 뒷유리
    val glass = Brush.verticalGradient(listOf(Color(0xFF4B5B70), Color(0xFF2B3647)))
    trapezoid(-len * 0.20f, -len * 0.04f, wid * 0.80f, wid * 0.66f, glass)
    drawRoundRect(lerpColor(body, Color.White, 0.12f), Offset(-wid * 0.33f, -len * 0.04f), Size(wid * 0.66f, len * 0.27f), CornerRadius(wid * 0.1f))
    trapezoid(len * 0.23f, len * 0.33f, wid * 0.66f, wid * 0.74f, glass)
    // 사이드미러
    listOf(-1f, 1f).forEach { d -> drawOval(shade, Offset(d * wid * 0.54f - wid * 0.09f, -len * 0.2f), Size(wid * 0.18f, len * 0.05f)) }
    // 전조등 · 미등
    listOf(-1f, 1f).forEach { d ->
        drawRoundRect(Color(0xFFFFF7D6), Offset(d * wid * 0.3f - wid * 0.12f, -len * 0.495f), Size(wid * 0.24f, len * 0.035f), CornerRadius(4f))
        drawRoundRect(Color(0xFFE53935), Offset(d * wid * 0.3f - wid * 0.13f, len * 0.465f), Size(wid * 0.26f, len * 0.03f), CornerRadius(4f))
    }
}

private fun DrawScope.truck(len: Float, wid: Float, body: Color) {
    val cab = len * 0.22f
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFCBD3DD), Color(0xFFF4F6F9), Color(0xFFCBD3DD)), -wid / 2, wid / 2),
        Offset(-wid / 2, -len / 2), Size(wid, cab), CornerRadius(wid * 0.18f))
    trapezoid(-len / 2 + cab * 0.15f, -len / 2 + cab * 0.42f, wid * 0.86f, wid * 0.86f, Brush.verticalGradient(listOf(Color(0xFF4B5B70), Color(0xFF2B3647))))
    val boxTop = -len / 2 + cab + len * 0.015f
    drawRoundRect(Brush.horizontalGradient(listOf(lerpColor(body, Color.Black, 0.15f), body, lerpColor(body, Color.Black, 0.15f)), -wid / 2, wid / 2),
        Offset(-wid / 2, boxTop), Size(wid, len / 2 - boxTop), CornerRadius(wid * 0.06f))
    var y = boxTop + len * 0.08f
    while (y < len / 2 - len * 0.04f) { drawLine(Color.Black.copy(alpha = 0.08f), Offset(-wid * 0.45f, y), Offset(wid * 0.45f, y), 2f); y += len * 0.08f }
    listOf(-1f, 1f).forEach { d -> drawRoundRect(Color(0xFFE53935), Offset(d * wid * 0.32f - wid * 0.1f, len * 0.47f), Size(wid * 0.2f, len * 0.02f), CornerRadius(3f)) }
}

private fun DrawScope.trapezoid(y0: Float, y1: Float, w0: Float, w1: Float, brush: Brush) {
    drawPath(Path().apply { moveTo(-w0 / 2, y0); lineTo(w0 / 2, y0); lineTo(w1 / 2, y1); lineTo(-w1 / 2, y1); close() }, brush)
}

private fun lerpColor(a: Color, b: Color, f: Float) = Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)

/** 애니메이션 바운딩 박스: 모서리 꺾쇠 + 숨쉬는 외곽. */
private fun DrawScope.bbox(center: Offset, v: VehicleState, s: Float, c: Color, pulse: Float) {
    val pad = (0.5f + 0.25f * pulse) * s
    val hw = v.widthM.toFloat() * s / 2 + pad
    val hl = v.lengthM.toFloat() * s / 2 + pad
    val tl = center - Offset(hw, hl)
    drawRoundRect(c.copy(alpha = 0.10f + 0.10f * pulse), tl, Size(hw * 2, hl * 2), CornerRadius(10f))
    val k = minOf(hw, hl) * 0.55f
    listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f), Offset(1f, 1f)).forEach { d ->
        val corner = center + Offset(hw * d.x, hl * d.y)
        drawLine(c, corner, corner - Offset(k * d.x, 0f), 5f, StrokeCap.Round)
        drawLine(c, corner, corner - Offset(0f, k * d.y), 5f, StrokeCap.Round)
    }
}

/** 자차 주변 감지 영역: 앞뒤 센서 부채꼴 + 안전 링. */
private fun DrawScope.safetyZone(center: Offset, ego: VehicleState, s: Float) {
    val hl = ego.lengthM.toFloat() * s / 2
    val hw = ego.widthM.toFloat() * s / 2
    val r = 9f * s
    listOf(-1f to 230f, 1f to 50f).forEach { (dir, start) ->
        val o = center + Offset(0f, dir * hl)
        drawArc(Brush.radialGradient(listOf(ACCENT.copy(alpha = 0.16f), Color.Transparent), o, r), start, 80f, true, o - Offset(r, r), Size(r * 2, r * 2))
    }
    val ring = 1.4f * s
    val tl = center - Offset(hw + ring, hl + ring)
    val sz = Size((hw + ring) * 2, (hl + ring) * 2)
    drawRoundRect(ACCENT.copy(alpha = 0.06f), tl, sz, CornerRadius(hw + ring))
    drawRoundRect(ACCENT.copy(alpha = 0.45f), tl, sz, CornerRadius(hw + ring), style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
}

/** 자차와 위험 차량 사이 공간을 반투명 빗금으로. 앞뒤 간격이면 세로 띠, 옆이면 가로 띠. */
private fun DrawScope.riskZone(ego: VehicleState, v: VehicleState, p: (Double, Double) -> Offset, c: Color, strength: Float) {
    if (strength <= 0.01f) return
    val dy = v.y - ego.y
    val longGap = abs(dy) - (v.lengthM + ego.lengthM) / 2
    // 세계 좌표 네 꼭짓점 (a-b 가 한쪽 끝, d-c 가 반대쪽 끝)
    val w = if (longGap > 0.5) {
        val sy = sign(dy)
        val y0 = ego.y + sy * (ego.lengthM / 2 + 0.3)
        val y1 = v.y - sy * v.lengthM / 2
        listOf(ego.x - 1.6 to y0, ego.x + 1.6 to y0, v.x + 1.6 to y1, v.x - 1.6 to y1)
    } else {
        val sx = sign(v.x - ego.x)
        val x0 = ego.x + sx * (ego.widthM / 2 + 0.2)
        val x1 = v.x - sx * v.widthM / 2
        val yTop = maxOf(ego.y + ego.lengthM / 2, v.y + v.lengthM / 2)
        val yBot = minOf(ego.y - ego.lengthM / 2, v.y - v.lengthM / 2)
        listOf(x0 to yTop, x1 to yTop, x1 to yBot, x0 to yBot)
    }
    fun lerp(a: Pair<Double, Double>, b: Pair<Double, Double>, t: Double) = p(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
    fun band(t0: Double, t1: Double) = Path().apply {
        val q = listOf(lerp(w[0], w[3], t0), lerp(w[1], w[2], t0), lerp(w[1], w[2], t1), lerp(w[0], w[3], t1))
        moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) }; close()
    }
    val whole = band(0.0, 1.0)
    // 자차 쪽이 진하고 멀어질수록 옅어지는 바닥 빛
    val a = lerp(w[0], w[1], 0.5); val d = lerp(w[3], w[2], 0.5)
    drawPath(whole, Brush.linearGradient(listOf(c.copy(alpha = 0.32f * strength), c.copy(alpha = 0.12f * strength)), a, d))
    // 빗금 띠를 세계 좌표에서 1m 간격으로 나눠 투영 → 멀수록 촘촘해져 노면에 붙어 보인다
    val lenM = maxOf(1.0, abs(w[3].second - w[0].second) + abs(w[3].first - w[0].first))
    val n = (lenM / 1.0).toInt().coerceIn(2, 60)
    for (i in 0 until n step 2) drawPath(band(i.toDouble() / n, (i + 1.0) / n), c.copy(alpha = 0.38f * strength))
    drawPath(whole, c.copy(alpha = 0.85f * strength), style = Stroke(2.5f))
}

/** 점들을 잇는 선 + 끝 화살촉. 3점 실선은 곡선. */
private fun DrawScope.arrowPath(pts: List<Offset>, c: Color, dashed: Boolean, width: Float = 4f) {
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        if (pts.size == 3 && !dashed) quadraticTo(pts[1].x, pts[1].y, pts[2].x, pts[2].y) else pts.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, c, style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(16f, 12f)) else null))
    val end = pts.last()
    val prev = pts[pts.size - 2]
    val ang = atan2(end.y - prev.y, end.x - prev.x)
    val len = maxOf(18f, width * 3.2f)
    drawPath(Path().apply {
        moveTo(end.x, end.y)
        lineTo(end.x - len * cos(ang - 0.45f), end.y - len * sin(ang - 0.45f))
        lineTo(end.x - len * cos(ang + 0.45f), end.y - len * sin(ang + 0.45f))
        close()
    }, c)
}

@Composable
private fun Callout(text: String, color: Color, modifier: Modifier, detail: String? = null, pointer: Boolean = true) {
    Column(modifier.wrapContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.background(PANEL, RoundedCornerShape(12.dp)).border(1.5.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text("  $text", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NAVY)
            }
            detail?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color) }
        }
        if (pointer) Canvas(Modifier.size(width = 16.dp, height = 9.dp)) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, color)
        }
    }
}

// ───────────────────────── 상단 HUD ─────────────────────────

@Composable
private fun DrivingHud(ego: VehicleState?, vehicles: List<VehicleState>, mock: Boolean, modifier: Modifier) {
    val blinkPhase by rememberInfiniteTransition(label = "hudBlink").animateFloat(0f, 1f,
        infiniteRepeatable(tween(667, easing = LinearEasing), RepeatMode.Restart), label = "hudBlink")
    val front = ego?.let { e -> vehicles.filter { it.id != e.id && abs(it.x - e.x) < 1.75 && it.y > e.y }.minByOrNull { it.y } }
    val rel = if (ego != null && front != null) ((front.vy - ego.vy) * 3.6).roundToInt() else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                // 속도는 시나리오(자차 vy) 값. 실차 계기판 속도가 아님을 바로 옆에 표시
                Text("${((ego?.vy ?: 0.0) * 3.6).roundToInt()}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = NAVY)
                Column(Modifier.padding(start = 6.dp, bottom = 10.dp)) {
                    Text("km/h", fontSize = 18.sp, color = NAVY)
                    Text(if (mock) "MOCK" else "SIM", fontSize = 11.sp, color = WATCH, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.size(46.dp).background(Color.White, CircleShape).border(5.dp, WARN, CircleShape), contentAlignment = Alignment.Center) {
                Text("110", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = INK)
            }
            // 자차 방향지시등 (데이터에 없으면 회색 + 미확인)
            val ind = ego?.indicator
            Row(Modifier.hud().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("LEFT" to "◀", "RIGHT" to "▶").forEach { (name, glyph) ->
                    val on = (ind == name || ind == "HAZARD") && blinkPhase < 0.5f
                    Text(glyph, fontSize = 26.sp, fontWeight = FontWeight.Black, color = if (on) Color(0xFF22C55E) else GRAY.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 4.dp).testTag("ego_signal_$name"))
                }
                Text(if (ind == null) " 미확인" else if (mock) " MOCK" else " SIM", fontSize = 11.sp, color = WATCH, fontWeight = FontWeight.Bold)
            }
        }
        HudChip("🛣 고속도로", example = true)
        HudChip("☀ 맑음 14°C", example = true)
        HudChip("↑ 직진 (내비 미연동)", example = true)
        if (rel != null && rel <= -3) HudChip("전방 감속 $rel km/h", color = WARN)
        else if (front != null && ego != null) HudChip("전방 차량 ${DemoRiskAnalyzer.clearanceM(ego, front).roundToInt()}m")
    }
}

private fun Modifier.hud() = card(RoundedCornerShape(50), bg = PANEL.copy(alpha = 0.82f), border = LINE, elevation = 0.dp)

/** example = 시나리오에 없는 값(예시). 실제·시뮬레이션 데이터와 구분해 표시. */
@Composable
private fun HudChip(text: String, example: Boolean = false, color: Color = NAVY) {
    Row(Modifier.hud().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
        if (example) Text("MOCK", fontSize = 11.sp, color = WATCH, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp).background(SUBBG, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp))
    }
}

// ───────────────────────── 네비 · 헤더 · 하단 ─────────────────────────

@Composable
private fun NavRail(tab: Tab, onTab: (Tab) -> Unit, onHome: () -> Unit, onReplay: () -> Unit, replayEnabled: Boolean) {
    Column(Modifier.width(104.dp).fillMaxHeight().card(RoundedCornerShape(24.dp)).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("◠", fontSize = 30.sp, color = ACCENT, fontWeight = FontWeight.Black)
        Text("RISK\nRADAR", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = NAVY, lineHeight = 13.sp)
        Spacer(Modifier.height(8.dp))
        NavItem("⌂", "홈", false, onClick = onHome)
        NavItem("◉", "실시간", tab == Tab.LIVE) { onTab(Tab.LIVE) }
        NavItem("▶", "리플레이", false, enabled = replayEnabled, onClick = onReplay)
        NavItem("▥", "주행 분석", tab == Tab.ANALYSIS) { onTab(Tab.ANALYSIS) }
        NavItem("⚙", "설정", tab == Tab.SETTINGS) { onTab(Tab.SETTINGS) }
    }
}

@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) ACCENT else Color.Transparent, label = "nav")
    val fg = when { selected -> Color.White; enabled -> NAVY; else -> GRAY.copy(alpha = 0.4f) }
    Column(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(icon, fontSize = 26.sp, color = fg)
        Text(label, fontSize = 13.sp, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Header(s: UiState, mock: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(34.dp)) {   // 레이더 로고
            listOf(0.95f, 0.65f, 0.35f).forEach { r ->
                drawArc(ACCENT, 200f, 140f, false, Offset(size.width * (1 - r) / 2, size.height * (1 - r) / 2 + size.height * 0.15f),
                    Size(size.width * r, size.height * r), style = Stroke(size.width * 0.09f, cap = StrokeCap.Round))
            }
        }
        Text("RISK RADAR", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = NAVY)
        Row(Modifier.card(RoundedCornerShape(50), elevation = 0.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(SAFE, CircleShape))
            // 팀 규칙: 실시간 감지·회피로 보이지 않게 데이터 출처를 명시
            Text(if (mock) "  위험 예측 · MOCK 데이터" else "  위험 예측 · 03 시뮬레이션 재생 (실차 감지 아님)", fontSize = 14.sp, color = NAVY)
        }
        Spacer(Modifier.weight(1f))
        // Pleos 연결은 SDK 상태를 그대로. Simulation Mode 는 SDK 를 부르지 않으므로 '미사용'. 통신(LTE) 상태는 읽을 API 가 없어 표시하지 않음
        val vehicleSdk = s.sdk.features[SdkFeature.VEHICLE_SERVICE]?.status
        val (pleos, ok) = when {
            s.mode != AppMode.PLEOS_CONTEXT -> "Pleos 미사용 (Simulation)" to false
            vehicleSdk == SupportStatus.CONFIRMED -> "Pleos 연결됨" to true
            else -> "Pleos ${vehicleSdk ?: "확인 중"}" to false
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (ok) SAFE else GRAY, CircleShape))
            Text("  $pleos", fontSize = 15.sp, color = if (ok) NAVY else GRAY)
        }
        // 네트워크: 기기의 실제 연결 종류만 (가짜 LTE 막대 없음). 읽지 못하면 표시 안 함
        val context = androidx.compose.ui.platform.LocalContext.current
        val net = remember(s.clock.positionMs / 5000) {
            runCatching {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                when {
                    caps == null -> "네트워크 없음"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "셀룰러"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "유선"
                    else -> "연결됨"
                }
            }.getOrNull()
        }
        net?.let { Text(it, fontSize = 15.sp, color = if (it == "네트워크 없음") GRAY else NAVY) }
        Text(java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = NAVY)
    }
}

@Composable
private fun BottomHud(mode: Int, onMode: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.card(RoundedCornerShape(18.dp), elevation = 3.dp).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("위험 등급", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NAVY)
            listOf("안전" to SAFE, "주의" to WATCH, "위험" to WARN, "판단 보류(?)" to UNK).forEach { (l, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).background(c, CircleShape)); Text(" $l", fontSize = 14.sp, color = NAVY) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 30.dp, height = 14.dp).clip(RoundedCornerShape(4.dp))) {
                    drawRoundRect(WARN.copy(alpha = 0.2f), cornerRadius = CornerRadius(6f))
                    var x = -size.height
                    while (x < size.width) { drawLine(WARN.copy(alpha = 0.7f), Offset(x, size.height), Offset(x + size.height, 0f), 2f); x += 7f }
                }
                Text("  예상 위험 영역", fontSize = 14.sp, color = NAVY)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 28.dp, height = 3.dp).background(ACCENT))
                Text(" 예측 경로", fontSize = 14.sp, color = NAVY)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.card(RoundedCornerShape(18.dp), elevation = 3.dp).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("화면 모드", fontSize = 14.sp, color = NAVY, modifier = Modifier.padding(horizontal = 10.dp))
            listOf(0 to "🚘 주행", 2 to "▥ 분석", 1 to "◎ 어라운드뷰").forEach { (m, l) ->
                val bg by animateColorAsState(if (m == mode) ACCENT else Color.Transparent, label = "mode")
                Text(l, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (m == mode) Color.White else NAVY,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable { onMode(m) }.padding(horizontal = 22.dp, vertical = 9.dp))
            }
        }
    }
}

// ───────────────────────── 오른쪽 패널 ─────────────────────────

@Composable
private fun WarningCard(s: UiState, vm: PrismViewModel, ego: VehicleState?, vehicles: List<VehicleState>, assessment: RiskFrame?, stale: Boolean) {
    val top = assessment.top()
    // 데이터 누락은 '안전' 이 아니라 판단 보류. 시나리오가 끝나면 마지막 결과를 실시간 경고처럼 보이지 않게 회색으로.
    val lvl = if (stale) RiskLevel.UNKNOWN else top?.riskLevel ?: RiskLevel.NORMAL
    val done = s.clock.finished
    val car = vehicles.firstOrNull { it.id == top?.vehicleId }
    val c = if (done) GRAY else levelColor(lvl)
    val bg by animateColorAsState(if (lvl.alerting() && !done) lerpColor(PANEL, c, 0.10f) else PANEL, tween(400), label = "warnBg")
    val border by animateColorAsState(if (lvl.alerting() && !done) c else LINE, tween(400), label = "warnBorder")
    Row(Modifier.fillMaxWidth().card(bg = bg, border = border).padding(18.dp).testTag("level_banner"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                done -> "■ 시나리오 완료"
                lvl == RiskLevel.WARNING -> "⚠ 위험"; lvl == RiskLevel.WATCH -> "⚠ 주의"; lvl == RiskLevel.UNKNOWN -> "? 판단 보류"; else -> "✓ 안전" },
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (lvl == RiskLevel.WATCH && !done) INK else Color.White,
                modifier = Modifier.background(c, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp))
            val msg = if (stale) "데이터가 갱신되지 않아 현재 위험을 판단할 수 없습니다." else warningMessage(ego, car, top)
            Text(if (done) "마지막 감지 결과 · $msg" else msg, fontSize = if (done) 18.sp else 22.sp, fontWeight = FontWeight.Bold,
                color = if (done) GRAY else NAVY, lineHeight = 30.sp)
            if (!stale && ego != null && car != null && (lvl.alerting() || lvl == RiskLevel.UNKNOWN)) Text(
                "${car.id} · ${side(ego, car)} ${DemoRiskAnalyzer.clearanceM(ego, car).roundToInt()}m" + (top?.ttcS?.let { " · 접근 %.1f초".format(it) } ?: ""),
                fontSize = 14.sp, color = GRAY)
        }
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(if (s.voiceAlertsEnabled) ACCENT.copy(alpha = 0.12f) else SUBBG)
            .clickable { vm.setVoiceAlertsEnabled(!s.voiceAlertsEnabled) }.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (s.voiceAlertsEnabled) "🔊" else "🔇", fontSize = 26.sp)
            Text("음성 안내", fontSize = 12.sp, color = if (s.voiceAlertsEnabled) ACCENT else GRAY, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ForecastCard(s: UiState, t: ScenarioTimeline) {
    val points = listOf(0L to "현재", 1000L to "1초", 3000L to "3초", 5000L to "5초").map { (dt, label) ->
        val pos = s.clock.positionMs + dt
        val snapAt = t.snapshotAt(pos)
        val ended = pos > t.durationMs
        // 그 시점 프레임이 누락이면 '안전' 이 아니라 판단 보류(UNKNOWN)
        val top = when {
            ended -> null
            snapAt.stale -> RiskObject("", RiskLevel.UNKNOWN, listOf("UNRELIABLE_OBSERVATION"))
            else -> s.assessments[snapAt.vehicle?.timestampMs].top()
        }
        Triple(label, top, ended)
    }
    val colors = points.map { (_, top, ended) -> animateColorAsState(if (ended) LINE else levelColor(top?.riskLevel ?: RiskLevel.NORMAL), tween(350), label = "tl").value }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("위험 예측", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NAVY)
            // 1·3·5초 값은 예측 모델이 아니라 재생 중인 시나리오의 미래 프레임을 같은 규칙으로 판정한 것
            Text("  향후 5초 · 시나리오 미래 프레임 기준", fontSize = 13.sp, color = GRAY)
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val y = size.height / 2
            val xs = points.indices.map { size.width * (0.125f + 0.75f * it / (points.size - 1)) }
            drawLine(SUBBG, Offset(xs.first(), y), Offset(xs.last(), y), 8f, StrokeCap.Round)
            for (i in 0 until xs.size - 1) drawLine(colors[i + 1].copy(alpha = 0.75f), Offset(xs[i], y), Offset(xs[i + 1], y), 8f, StrokeCap.Round)
            xs.forEachIndexed { i, x ->
                if (i == 0) drawCircle(ACCENT.copy(alpha = 0.25f), 22f, Offset(x, y))   // 현재 시점 강조
                drawCircle(BG, 11f, Offset(x, y)); drawCircle(colors[i], 8f, Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            points.forEachIndexed { i, (label, top, ended) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, fontSize = 13.sp, color = if (i == 0) ACCENT else GRAY, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
                    Text(forecastLabel(top, ended), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (ended) GRAY else colors[i], lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun CausesCard(s: UiState, ego: VehicleState?, vehicles: List<VehicleState>, assessment: RiskFrame?) {
    // 차량별 위험: 가장 긴급한 것부터, 다른 위험도 함께 나열
    val causes = assessment?.riskObjects.orEmpty().filter { it.riskLevel.alerting() }.sortedWith(DemoRiskAnalyzer.urgency)
    val chain = causes.any { "CHAIN_DECEL" in it.reasonCodes }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ⓘ 주요 원인", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NAVY)
        if (causes.isEmpty()) Text("가까운 위험 차량 없음", fontSize = 15.sp, color = GRAY)
        if (chain) {
            val order = DemoRiskAnalyzer.decelOrder(s.assessments, s.clock.positionMs)
            Text("연쇄 감속 순서: " + order.joinToString(" → ") { "${it.first} (%.1f초)".format(it.second / 1000.0) } + " → 자차",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = WARN)
        }
        causes.forEach { o ->
            val v = vehicles.firstOrNull { it.id == o.vehicleId }
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(10.dp).background(levelColor(o.riskLevel), CircleShape))
                Column(Modifier.padding(start = 10.dp)) {
                    Text("${o.vehicleId} · ${if (ego != null && v != null) side(ego, v) else ""} · ${o.reasonCodes.mapNotNull { reasonText(it) }.joinToString().ifEmpty { "간격 확인" }}",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NAVY)
                    if (ego != null && v != null) Text(
                        "거리 ${DemoRiskAnalyzer.clearanceM(ego, v).roundToInt()}m · 상대속도 ${((v.vy - ego.vy) * 3.6).roundToInt()} km/h" +
                            (o.ttcS?.let { " · TTC %.1f초".format(it) } ?: "") + " · ${signalText(v.indicator)}", fontSize = 13.sp, color = GRAY)
                }
            }
        }
    }
}

@Composable
private fun SystemCard(s: UiState, t: ScenarioTimeline, stale: Boolean) {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("시스템 상태", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NAVY)
        StatusRow("위험 예측", if (s.assessments.isNotEmpty()) "작동 중" else "대기", s.assessments.isNotEmpty())
        StatusRow("데이터 연결", when {
            stale -> "프레임 누락"
            s.mode == AppMode.PLEOS_CONTEXT -> "Pleos SDK + 시뮬레이션"
            t.manifest.mock -> "MOCK 재생"
            else -> "시뮬레이션 재생"
        }, !stale)
        // 속도·위치는 시나리오 값이다. Simulation Mode 는 SDK 를 부르지 않으므로 계기판(0km/h·P)과 다를 수 있다.
        StatusRow("차량 상태 출처", if (s.mode == AppMode.PLEOS_CONTEXT) "기어·브레이크: SDK / 속도: 시나리오" else "시나리오 값 (SDK 미호출)", true)
        StatusRow("주변 차량 객체", (if (t.manifest.mock) "MOCK" else "SIMULATION") + " · 실차 API UNSUPPORTED", !t.manifest.mock)
        val hasSignal = t.snapshotAt(s.clock.positionMs).vehicle?.vehicles?.any { it.indicator != null } == true
        StatusRow("방향지시등 데이터", if (!hasSignal) "확인되지 않음 (데이터에 없음)" else if (t.manifest.mock) "MOCK" else "SIMULATION", hasSignal)
        StatusRow("음성 안내", if (s.voiceAlertsEnabled) "켜짐" else "꺼짐", s.voiceAlertsEnabled)
        StatusRow("재생 상태", when { s.clock.finished -> "시나리오 완료"; s.clock.playing -> "재생 중"; else -> "일시정지" }, !s.clock.finished)
        StatusRow("분석 경과", "%02d:%04.1f".format(s.clock.positionMs / 60000, (s.clock.positionMs % 60000) / 1000.0), true)
    }
}

/** 360° 주변 감지: 방향별 가장 가까운 차량과 위험 등급. 좌우는 차선 변경 공간 여부도 표시(변경을 권하지 않음). */
/** 자차 차선 변경 안전성 (4단계). 시뮬레이션 기반 추정이며 실제 안전을 보장하지 않음을 함께 표시. */
@Composable
private fun LaneChangeCard(c: DemoRiskAnalyzer.LaneChangeCheck) {
    val col = lcColor(c.level)
    Column(Modifier.fillMaxWidth().card(border = col).padding(18.dp).testTag("lane_change_card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (c.side == "LEFT") "◀ 자차 좌측 차선 변경" else "자차 우측 차선 변경 ▶", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = NAVY, modifier = Modifier.weight(1f))
            Text("${c.level.name} · ${c.level.label}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (c.level == DemoRiskAnalyzer.LaneChangeLevel.CAUTION) INK else Color.White,
                modifier = Modifier.background(col, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
        }
        c.reasons.forEach { Text("· $it", fontSize = 13.sp, color = NAVY) }
        c.ttcS?.let { Text("가장 짧은 TTC %.1f초".format(it), fontSize = 13.sp, color = GRAY) }
        Text("시뮬레이션 기반 위험 추정 · 실제 안전을 보장하지 않음 · 차량을 제어하지 않음", fontSize = 11.sp, color = GRAY)
    }
}

@Composable
private fun SurroundCard(ego: VehicleState?, vehicles: List<VehicleState>, assessment: RiskFrame?, stale: Boolean) {
    val byId = assessment?.riskObjects?.associateBy { it.vehicleId }.orEmpty()
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("주변 감지 (360°)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NAVY)
        if (ego == null || stale) { Text("데이터 누락 — 판단 보류", fontSize = 14.sp, color = UNK); return@Column }
        val others = vehicles.filter { it.id != ego.id }
        listOf("전방", "후방", "좌측", "우측").forEach { zone ->
            val inZone = others.filter { side(ego, it).startsWith(zone) }
            val near = inZone.minByOrNull { DemoRiskAnalyzer.clearanceM(ego, it) }
            val lvl = near?.let { byId[it.id]?.riskLevel } ?: RiskLevel.NORMAL
            val space = if (zone == "좌측" || zone == "우측") {
                if (inZone.any { abs(it.y - ego.y) < (it.lengthM + ego.lengthM) / 2 + 10 }) " · 차선 변경 공간 부족" else " · 공간 있음"
            } else ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(levelColor(lvl), CircleShape))
                Text("  $zone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NAVY, modifier = Modifier.width(56.dp))
                Text((near?.let { "${it.id} ${DemoRiskAnalyzer.clearanceM(ego, it).roundToInt()}m · ${riskLabel(lvl)}" } ?: "감지 차량 없음") + space,
                    fontSize = 13.sp, color = GRAY)
            }
        }
        listOf("LEFT" to "좌측 사각지대", "RIGHT" to "우측 사각지대").forEach { (name, label) ->
            val o = assessment?.riskObjects?.firstOrNull { "BLIND_SPOT_$name" in it.reasonCodes }
            val conflict = o != null && "LANE_CHANGE_CONFLICT" in o.reasonCodes
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (conflict) WARN else if (o != null) Color(0xFFFF9800) else SAFE, CircleShape))
                Text("  $label", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NAVY, modifier = Modifier.width(110.dp))
                Text(o?.let { "${it.vehicleId} 감지" + if (conflict) " · 차선 변경 충돌 위험" else "" } ?: "비어 있음", fontSize = 13.sp, color = GRAY)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = GRAY, modifier = Modifier.weight(1f))
        Box(Modifier.size(8.dp).background(if (ok) SAFE else WATCH, CircleShape))
        Text("  $value", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NAVY)
    }
}

@Composable
private fun PlaybackCard(s: UiState, vm: PrismViewModel, t: ScenarioTimeline) {
    Row(Modifier.fillMaxWidth().card().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val label = when { s.clock.playing -> "❚❚ 일시정지"; s.clock.finished -> "↺ 처음부터"; else -> "▶ 재생" }
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(ACCENT)
            .clickable { if (s.clock.playing) vm.pause() else vm.play() }.padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag(if (s.clock.playing) "pause" else "play"))
        if (!s.clock.finished) Text("↺ 처음부터", color = ACCENT, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50))
            .border(1.5.dp, ACCENT, RoundedCornerShape(50)).clickable { vm.seek(0); vm.play() }.padding(horizontal = 16.dp, vertical = 9.dp)
            .testTag("restart"))
        Text("%.1f / %.1f s".format(s.clock.positionMs / 1000.0, t.durationMs / 1000.0), fontSize = 15.sp, color = GRAY)
    }
}

/** 설정 > 화면 테마: 라이트/다크/자동 선택 + 기기 시간·선택·적용 테마·다음 전환 시각. 선택은 앱을 다시 켜도 유지. */
@Composable
internal fun ThemeCard(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now = PrismTheme.now
    Column(modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("설정 › 화면 테마", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NAVY)
        ThemeMode.entries.forEach { m ->
            val sel = PrismTheme.mode == m
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) ACCENT.copy(alpha = 0.12f) else SUBBG)
                .border(1.5.dp, if (sel) ACCENT else LINE, RoundedCornerShape(14.dp)).clickable { PrismTheme.select(context, m) }
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag("theme_${m.name}"), verticalAlignment = Alignment.CenterVertically) {
                Text(when (m) { ThemeMode.LIGHT -> "☀"; ThemeMode.DARK -> "☾"; ThemeMode.AUTO -> "◐" }, fontSize = 22.sp, color = if (m == ThemeMode.LIGHT) WATCH else ACCENT)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(m.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NAVY)
                    Text(m.detail, fontSize = 12.sp, color = GRAY)
                }
                Box(Modifier.size(18.dp).border(2.dp, if (sel) ACCENT else GRAY, CircleShape).padding(4.dp).background(if (sel) ACCENT else Color.Transparent, CircleShape))
            }
        }
        val (next, nextMode) = PrismTheme.nextChange(now)
        StatusRow("현재 기기 시간", "%02d:%02d".format(now.hour, now.minute), true)
        StatusRow("선택 모드", PrismTheme.mode.label, true)
        StatusRow("적용 중인 테마", PrismTheme.applied.label, true)
        StatusRow("다음 전환", if (PrismTheme.mode == ThemeMode.AUTO) "%02d:%02d (%s)".format(next.hour, next.minute, nextMode.label) else "자동 모드에서만", PrismTheme.mode == ThemeMode.AUTO)
    }
}

/** 기존 주행 화면에 있던 설정(음성, 정차 가정, 리플레이)을 그대로 옮김. */
@Composable
private fun SettingsCard(s: UiState, vm: PrismViewModel) {
    val sw = SwitchDefaults.colors(checkedTrackColor = ACCENT)
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("⚙ 설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NAVY)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("위험 음성·알림음 (주의·경고마다 1회)", fontSize = 15.sp, color = NAVY, modifier = Modifier.weight(1f))
            Switch(s.voiceAlertsEnabled, vm::setVoiceAlertsEnabled, colors = sw)
        }
        if (s.mode == AppMode.SIMULATION) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("정차(P) 가정 — Simulation 전용", fontSize = 15.sp, color = NAVY, modifier = Modifier.weight(1f))
            Switch(s.simulatedParked, vm::setSimulatedParked, colors = sw, modifier = Modifier.testTag("parked"))
        }
        Text("정차 후 리플레이 열기", color = if (s.replayBlockedReason == null) Color.White else GRAY, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (s.replayBlockedReason == null) ACCENT else SUBBG)
                .clickable(enabled = s.replayBlockedReason == null, onClick = vm::openReplay).padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("open_replay"))
        s.replayBlockedReason?.let { Text(it, fontSize = 13.sp, color = GRAY) }
    }
}

// ───────────────────────── 문구 ─────────────────────────

private fun forecastLabel(top: RiskObject?, ended: Boolean): String = when {
    ended -> "종료"
    top == null || top.riskLevel == RiskLevel.NORMAL -> "안전"
    top.riskLevel == RiskLevel.UNKNOWN -> "판단 보류"
    top.riskLevel == RiskLevel.WARNING -> "위험 증가"
    "CUT_IN" in top.reasonCodes -> "차선 변경\n가능성"
    "LANE_INTRUSION" in top.reasonCodes -> "차선 진입"
    "CHAIN_DECEL" in top.reasonCodes -> "연쇄 감속"
    "SUDDEN_BRAKE" in top.reasonCodes -> "급감속"
    top.reasonCodes.any { it.startsWith("BLIND_SPOT") } -> "사각지대"
    "SIGNAL_TOWARD_EGO" in top.reasonCodes -> "깜빡이"
    else -> "접근"
}

private fun warningMessage(ego: VehicleState?, car: VehicleState?, top: RiskObject?): String {
    if (top == null || top.riskLevel == RiskLevel.NORMAL) return "주변에 가까운 위험이 없습니다."
    if (top.riskLevel == RiskLevel.UNKNOWN) return "관측이 불확실해 판단을 보류합니다."
    val where = if (ego != null && car != null) side(ego, car) else "주변"
    return when (top.reasonCodes.firstOrNull()) {
        "OVERLAP" -> "$where 차량과 겹쳐 보입니다. 충돌 또는 추적 오류일 수 있습니다."
        "LANE_CHANGE_CONFLICT" -> "$where 사각지대에 차량이 있습니다. 차선 변경을 멈추고 기다리세요."
        "BLIND_SPOT_LEFT", "BLIND_SPOT_RIGHT" -> "$where 사각지대에 차량이 있습니다."
        "SIGNAL_TOWARD_EGO" -> "$where 차량이 방향지시등을 켰습니다. 끼어들 수 있으니 간격을 유지하세요."
        "CUT_IN" -> "$where 차량이 차선을 변경할 가능성이 있습니다. 차선을 유지하고 간격을 확보하세요."
        "LANE_INTRUSION" -> "$where 차량이 내 차로로 들어오고 있습니다. 감속해 간격을 확보하세요."
        "CHAIN_DECEL" -> "앞쪽 여러 차량이 연쇄 감속 중입니다. 미리 감속하세요."
        "SUDDEN_BRAKE" -> "$where 차량이 급감속하고 있습니다."
        "FRONT_CLOSING" -> "전방 차량과 가까워지고 있습니다."
        "REAR_CLOSING" -> "후방 차량이 빠르게 다가옵니다."
        "CLOSE_GAP" -> "$where 차량과 간격이 좁습니다."
        else -> "$where 차량을 확인하세요."
    }
}

private fun reasonText(code: String?): String? = when (code) {
    null -> null
    "CUT_IN" -> "차선 변경 가능"
    "LANE_INTRUSION" -> "차선 진입 중"
    "CHAIN_DECEL" -> "연쇄 감속"
    "OVERLAP" -> "객체 겹침"
    "LANE_CHANGE_CONFLICT" -> "차선 변경 충돌 위험"
    "BLIND_SPOT_LEFT" -> "좌측 사각지대"
    "BLIND_SPOT_RIGHT" -> "우측 사각지대"
    "SIGNAL_TOWARD_EGO" -> "자차 쪽 방향지시등"
    "NO_SIGNAL" -> "깜빡이 없이 이동"
    "SIGNAL_MISMATCH" -> "깜빡이·이동 방향 불일치"
    "SUDDEN_BRAKE" -> "급감속"
    "FRONT_CLOSING" -> "전방 접근"
    "REAR_CLOSING" -> "후방 접근"
    "CLOSE_GAP" -> "간격 감소"
    "UNRELIABLE_OBSERVATION" -> "관측 불확실"
    else -> code
}
