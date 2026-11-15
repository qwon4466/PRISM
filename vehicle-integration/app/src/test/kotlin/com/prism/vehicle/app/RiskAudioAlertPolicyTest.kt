package com.prism.vehicle.app

import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.RiskObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAudioAlertPolicyTest {
    private fun risk(id: String, level: RiskLevel) = RiskObject(id, level)

    @Test fun watchAndWarningEachAnnounceOnceForTheSameVehicle() {
        val policy = RiskAudioAlertPolicy()
        assertTrue(policy.next(listOf(risk("a", RiskLevel.WATCH)))!!.startsWith("시뮬레이션 주의."))
        assertNull(policy.next(listOf(risk("a", RiskLevel.WATCH))))
        assertTrue(policy.next(listOf(risk("a", RiskLevel.WARNING)))!!.startsWith("시뮬레이션 경고."))
        assertNull(policy.next(listOf(risk("a", RiskLevel.WARNING))))
    }

    @Test fun warningTakesPriorityAndClearedRiskCanAnnounceAgain() {
        val policy = RiskAudioAlertPolicy()
        val message = policy.next(listOf(risk("watch", RiskLevel.WATCH), risk("warning", RiskLevel.WARNING)))
        assertTrue(message!!.startsWith("시뮬레이션 경고."))
        assertNull(policy.next(listOf(risk("watch", RiskLevel.WATCH), risk("warning", RiskLevel.WARNING))))
        assertNull(policy.next(listOf(risk("warning", RiskLevel.NORMAL))))
        assertEquals("시뮬레이션 주의. 주변 차량의 움직임을 확인하세요.",
            policy.next(listOf(risk("watch", RiskLevel.WATCH))))
    }
}
