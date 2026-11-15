package com.prism.vehicle.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/** AUTO 테마는 기기 로컬 시간 07:00~18:59 = LIGHT, 19:00~06:59 = DARK. 수동 선택은 시간과 무관. */
class ThemeTest {
    private fun at(h: Int, m: Int) = LocalTime.of(h, m)

    @Test fun autoSwitchesAtBoundaries() {
        assertEquals(ThemeMode.LIGHT, PrismTheme.applied(ThemeMode.AUTO, at(18, 59)))
        assertEquals(ThemeMode.DARK, PrismTheme.applied(ThemeMode.AUTO, at(19, 0)))
        assertEquals(ThemeMode.DARK, PrismTheme.applied(ThemeMode.AUTO, at(6, 59)))
        assertEquals(ThemeMode.LIGHT, PrismTheme.applied(ThemeMode.AUTO, at(7, 0)))
        assertEquals(ThemeMode.DARK, PrismTheme.applied(ThemeMode.AUTO, at(0, 0)))
    }

    @Test fun manualModesIgnoreTime() {
        listOf(at(3, 0), at(12, 0), at(22, 0)).forEach {
            assertEquals(ThemeMode.LIGHT, PrismTheme.applied(ThemeMode.LIGHT, it))
            assertEquals(ThemeMode.DARK, PrismTheme.applied(ThemeMode.DARK, it))
        }
    }

    @Test fun nextChangeTime() {
        assertEquals(at(19, 0) to ThemeMode.DARK, PrismTheme.nextChange(at(10, 0)))
        assertEquals(at(7, 0) to ThemeMode.LIGHT, PrismTheme.nextChange(at(19, 30)))
        assertEquals(at(7, 0) to ThemeMode.LIGHT, PrismTheme.nextChange(at(2, 0)))
    }

    @Test fun firstLaunchDefaultIsLight() = assertEquals(ThemeMode.LIGHT, PrismTheme.mode)
}
