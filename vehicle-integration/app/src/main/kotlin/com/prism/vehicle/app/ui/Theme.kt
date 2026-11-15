package com.prism.vehicle.app.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.time.LocalTime

enum class ThemeMode(val label: String, val detail: String) {
    LIGHT("라이트 모드", "밝고 선명한 테마 (기본값)"),
    DARK("다크 모드", "야간 주행에 맞춘 어두운 테마"),
    AUTO("자동 모드", "기기 시간에 따라 자동 전환 (07:00~18:59 라이트)"),
}

/** 화면 색 토큰 한 벌. 실시간·리플레이·목록·설정·3D 장면이 모두 이 값을 쓴다. */
class Palette(
    val dark: Boolean, val bg: Color, val bgTop: Color, val panel: Color, val subbg: Color, val line: Color,
    val text: Color, val gray: Color, val accent: Color, val safe: Color, val watch: Color, val warn: Color,
    val unk: Color, val shadow: Color,
)

val LightPalette = Palette(
    dark = false, bg = Color(0xFFEEF2F8), bgTop = Color(0xFFF8FAFD), panel = Color(0xF7FFFFFF), subbg = Color(0xFFE8EEF6),
    line = Color(0xFFDCE4EE), text = Color(0xFF16233A), gray = Color(0xFF66768A), accent = Color(0xFF7057EE),
    safe = Color(0xFF14A06C), watch = Color(0xFFE5A400), warn = Color(0xFFE53935), unk = Color(0xFF4F7FE0), shadow = Color(0x22364A6B),
)

val DarkPalette = Palette(
    dark = true, bg = Color(0xFF070B16), bgTop = Color(0xFF0B1124), panel = Color(0xE6131A2C), subbg = Color(0xFF1C2438),
    line = Color(0x33FFFFFF), text = Color(0xFFEFF2FA), gray = Color(0xFF8D97B0), accent = Color(0xFF8B6CFF),
    safe = Color(0xFF2BD48F), watch = Color(0xFFFFC928), warn = Color(0xFFFF4757), unk = Color(0xFF5B8DEF), shadow = Color(0x66000000),
)

/**
 * 앱 전역 테마 상태. Compose 스냅샷 상태라 값이 바뀌면 이를 읽는 모든 화면·Canvas 가 다시 그려진다.
 * 사용자 선택은 SharedPreferences 에 저장한다. 저장된 값이 없을 때(최초 실행)만 LIGHT.
 * AUTO 는 시뮬레이션 재생 시간이 아니라 기기(에뮬레이터)의 로컬 시스템 시간을 쓴다.
 */
object PrismTheme {
    private const val PREFS = "prism_ui"
    private const val KEY = "theme_mode"
    private val DAY_START: LocalTime = LocalTime.of(7, 0)
    private val NIGHT_START: LocalTime = LocalTime.of(19, 0)

    var mode by mutableStateOf(ThemeMode.LIGHT)
        private set
    /** AUTO 판정에 쓰는 기기 시각. 분 단위 틱·시간 변경·시간대 변경·앱 복귀 때 갱신된다. */
    var now: LocalTime by mutableStateOf(LocalTime.now())
        private set

    val applied: ThemeMode get() = applied(mode, now)
    val palette: Palette get() = if (applied == ThemeMode.DARK) DarkPalette else LightPalette

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        saved?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }?.let { mode = it }
        refreshClock()
    }

    fun select(context: Context, m: ThemeMode) {
        mode = m
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }

    fun refreshClock() { now = LocalTime.now() }

    fun isDay(t: LocalTime) = !t.isBefore(DAY_START) && t.isBefore(NIGHT_START)
    fun applied(mode: ThemeMode, t: LocalTime) = when (mode) {
        ThemeMode.AUTO -> if (isDay(t)) ThemeMode.LIGHT else ThemeMode.DARK
        else -> mode
    }
    /** AUTO 에서 다음으로 테마가 바뀌는 시각과 바뀐 뒤 테마. */
    fun nextChange(t: LocalTime): Pair<LocalTime, ThemeMode> =
        if (isDay(t)) NIGHT_START to ThemeMode.DARK else DAY_START to ThemeMode.LIGHT

    fun colorScheme(): ColorScheme = palette.let { p ->
        if (p.dark) darkColorScheme(
            primary = p.accent, onPrimary = Color.White, secondary = p.accent, onSecondary = Color.White,
            background = p.bg, onBackground = p.text, surface = Color(0xFF131A2C), onSurface = p.text,
            surfaceVariant = p.subbg, onSurfaceVariant = p.gray, outline = Color(0x55FFFFFF), outlineVariant = p.line,
            error = p.warn, onError = Color.White,
        ) else lightColorScheme(
            primary = p.accent, onPrimary = Color.White, secondary = p.accent, onSecondary = Color.White,
            background = p.bg, onBackground = p.text, surface = Color.White, onSurface = p.text,
            surfaceVariant = p.subbg, onSurfaceVariant = p.gray, outline = Color(0xFFB8C4D2), outlineVariant = p.line,
            error = p.warn, onError = Color.White,
        )
    }
}
