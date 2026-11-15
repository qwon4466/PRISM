package com.prism.vehicle.app

import android.app.Application
import android.content.res.AssetManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prism.vehicle.adapters.Gear
import com.prism.vehicle.adapters.engineLoader
import com.prism.vehicle.adapters.LoadResult
import com.prism.vehicle.adapters.SupportStatus
import com.prism.vehicle.adapters.SdkFeature
import com.prism.vehicle.adapters.TextSource
import com.prism.vehicle.adapters.VehicleSdkProvider
import com.prism.vehicle.adapters.VehicleSdkState
import com.prism.vehicle.replay.Issue
import com.prism.vehicle.replay.DemoRiskAnalyzer
import com.prism.vehicle.replay.ReplayClock
import com.prism.vehicle.replay.RiskFrame
import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.ScenarioTimeline
import com.prism.vehicle.sdk.PleosVehicleSdkProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

enum class AppMode(val label: String) {
    SIMULATION("Simulation Mode"),
    PLEOS_CONTEXT("Pleos Context Mode"),
    ADAS("ADAS Mode"),
}

enum class Screen { SELECT, DRIVE, REPLAY, ERROR }

data class UiState(
    val mode: AppMode = AppMode.SIMULATION,
    val screen: Screen = Screen.SELECT,
    val scenarioIds: List<String> = emptyList(),
    val loading: Boolean = false,
    val timeline: ScenarioTimeline? = null,
    val assessments: Map<Long, RiskFrame> = emptyMap(),
    val voiceAlertsEnabled: Boolean = true,
    val failedScenario: String? = null,
    val issues: List<Issue> = emptyList(),
    val clock: ReplayClock = ReplayClock(durationMs = 0),
    /** Simulation Mode 에서는 실제 기어가 없으므로 정차(P)를 화면 버튼으로 가정한다. */
    val simulatedParked: Boolean = false,
    val sdk: VehicleSdkState = VehicleSdkState(),
) {
    val parked: Boolean
        get() = when (mode) {
            AppMode.SIMULATION -> simulatedParked
            AppMode.PLEOS_CONTEXT -> sdk.gear == Gear.PARK
            AppMode.ADAS -> false
        }

    val replayBlockedReason: String?
        get() = when {
            parked -> null
            mode == AppMode.SIMULATION -> "정차(P) 상태에서만 리플레이를 열 수 있습니다. '정차 가정' 을 켜세요."
            sdk.features[SdkFeature.GEAR]?.status != SupportStatus.CONFIRMED ->
                "기어 상태를 읽지 못해 정차 여부를 확인할 수 없습니다 (${sdk.features[SdkFeature.GEAR]?.detail})"
            else -> "현재 기어 ${sdk.gear}. P단에서만 리플레이를 열 수 있습니다."
        }
}

class PrismViewModel(app: Application) : AndroidViewModel(app) {
    private val engines = engineLoader(AssetTextSource(app.assets)) // 파일 + 01·02 엔진
    private val providers = engines.first
    private val loader = engines.second
    private val pleos: VehicleSdkProvider = PleosVehicleSdkProvider(app)
    private val warningAudio = WarningAudio(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var ticker: Job? = null
    private var lastWarningId: String? = null
    private var foreground = false

    init {
        viewModelScope.launch { pleos.state.collect { s -> _ui.update { it.copy(sdk = s) } } }
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { providers.scenarioIds() }
            _ui.update { it.copy(scenarioIds = ids) }
        }
    }

    fun setMode(mode: AppMode) {
        if (mode == AppMode.ADAS) return // 공식 권한 확인 전 비활성
        _ui.update { it.copy(mode = mode) }
        if (mode == AppMode.PLEOS_CONTEXT && foreground) pleos.start() else pleos.stop()
    }

    fun openScenario(id: String, initialPositionMs: Long = 0L, autoPlay: Boolean = false) {
        pause()
        _ui.update { it.copy(loading = true, timeline = null, assessments = emptyMap(), issues = emptyList()) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { loader.load(id) }
            val assessments = if (result is LoadResult.Ready) withContext(Dispatchers.Default) {
                DemoRiskAnalyzer.analyze(result.timeline)
            } else emptyMap()
            _ui.update {
                when (result) {
                    is LoadResult.Ready -> it.copy(
                        loading = false, screen = Screen.DRIVE, timeline = result.timeline, failedScenario = null,
                        issues = result.timeline.issues,
                        clock = ReplayClock(durationMs = result.timeline.durationMs).seek(initialPositionMs),
                        assessments = assessments,
                    )
                    is LoadResult.Failed -> it.copy(
                        loading = false, screen = Screen.ERROR, timeline = null, assessments = emptyMap(),
                        failedScenario = id, issues = result.issues,
                    )
                }
            }
            lastWarningId = null
            if (result is LoadResult.Ready && autoPlay) play()
        }
    }

    fun back() {
        pause()
        _ui.update {
            if (it.screen == Screen.REPLAY) it.copy(screen = Screen.DRIVE)
            else it.copy(screen = Screen.SELECT, timeline = null, assessments = emptyMap(), clock = ReplayClock(durationMs = 0))
        }
    }

    fun setSimulatedParked(parked: Boolean) {
        _ui.update { it.copy(simulatedParked = parked) }
        if (!parked && _ui.value.screen == Screen.REPLAY) back()
    }

    fun openReplay() {
        if (_ui.value.replayBlockedReason != null) return
        pause()
        _ui.update { it.copy(screen = Screen.REPLAY) }
    }

    fun play() {
        _ui.update { it.copy(clock = it.clock.play()) }
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive && _ui.value.clock.playing) {
                delay(FRAME_MS)
                val now = SystemClock.elapsedRealtime()
                _ui.update { it.copy(clock = it.clock.advance(now - last)) }
                last = now
                announceWarningIfNeeded()
            }
        }
    }

    fun pause() {
        ticker?.cancel()
        _ui.update { it.copy(clock = it.clock.pause()) }
        warningAudio.stop()
    }

    fun seek(ms: Long) = _ui.update { it.copy(clock = it.clock.seek(ms)) }
    fun setSpeed(s: Float) = _ui.update { it.copy(clock = it.clock.withSpeed(s)) }
    fun setVoiceAlertsEnabled(enabled: Boolean) {
        _ui.update { it.copy(voiceAlertsEnabled = enabled) }
        if (!enabled) warningAudio.stop()
    }
    fun speakTest() = warningAudio.announce("PRISM 시뮬레이션 음성 안내 테스트입니다", pleos, chime = false)

    /**
     * 위험(WARNING) 한 건당 한 번만 안내한다. 같은 차량의 경고가 이어지는 동안은 반복하지 않고,
     * 경고가 풀렸다가 다시 생기거나 다른 차량이 경고 대상이 되면 다시 안내한다(등급 해제는 분석기 히스테리시스로 안정화됨).
     */
    private fun announceWarningIfNeeded() {
        val s = _ui.value
        if (!foreground || !s.voiceAlertsEnabled || s.screen != Screen.DRIVE) return
        val snap = s.timeline?.snapshotAt(s.clock.positionMs) ?: return
        if (snap.stale) { lastWarningId = null; return }
        val warning = s.assessments[snap.vehicle?.timestampMs]?.riskObjects
            ?.firstOrNull { it.riskLevel == RiskLevel.WARNING }
        if (warning == null) { lastWarningId = null; return }
        if (warning.vehicleId == lastWarningId) return
        val r = warning.reasonCodes
        val situation = when {
            "OVERLAP" in r -> "주변 차량과 겹쳐 보입니다. 확인하세요."
            "LANE_CHANGE_CONFLICT" in r -> "사각지대에 차량이 있습니다. 차선 변경을 멈추세요."
            "CUT_IN" in r || "LANE_INTRUSION" in r -> "주변 차량이 내 차 앞으로 끼어들고 있습니다."
            "CHAIN_DECEL" in r -> "앞쪽 차량들이 연쇄 감속 중입니다."
            "SUDDEN_BRAKE" in r -> "앞 차량이 급정거했습니다."
            "REAR_CLOSING" in r -> "뒤 차량이 가까워지고 있습니다."
            else -> "앞 차량과의 거리가 위험하게 가까워졌습니다."
        }
        warningAudio.announce("시뮬레이션 경고. $situation", if (s.mode == AppMode.PLEOS_CONTEXT) pleos else null)
        lastWarningId = warning.vehicleId
    }

    /** Activity onStart/onStop 에 맞춰 SDK 세션을 열고 닫는다(백그라운드에서 리스너가 남지 않게). */
    fun onForeground() {
        foreground = true
        if (_ui.value.mode == AppMode.PLEOS_CONTEXT) pleos.start()
    }

    fun restartSdk() {
        pleos.stop()
        if (_ui.value.mode == AppMode.PLEOS_CONTEXT && foreground) pleos.start()
    }

    fun onBackground() {
        foreground = false
        pause()
        pleos.stop()
    }

    override fun onCleared() {
        pleos.stop()
        warningAudio.release()
    }

    private companion object { const val FRAME_MS = 33L }
}

class AssetTextSource(private val assets: AssetManager) : TextSource {
    override fun read(path: String): String? = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }
}
