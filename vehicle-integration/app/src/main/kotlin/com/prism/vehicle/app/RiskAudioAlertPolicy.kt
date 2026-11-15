package com.prism.vehicle.app

import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.RiskObject

/** Selects one audible risk per frame and announces each vehicle/level transition once. */
internal class RiskAudioAlertPolicy {
    private var lastAlert: Pair<String, RiskLevel>? = null

    fun reset() {
        lastAlert = null
    }

    fun next(risks: List<RiskObject>?): String? {
        val risk = risks?.firstOrNull { it.riskLevel == RiskLevel.WARNING }
            ?: risks?.firstOrNull { it.riskLevel == RiskLevel.WATCH }
        if (risk == null) {
            reset()
            return null
        }

        val key = risk.vehicleId to risk.riskLevel
        if (key == lastAlert) return null
        lastAlert = key

        if (risk.riskLevel == RiskLevel.WATCH) {
            return "시뮬레이션 주의. 주변 차량의 움직임을 확인하세요."
        }

        val reasons = risk.reasonCodes
        val situation = when {
            "OVERLAP" in reasons -> "주변 차량과 겹쳐 보입니다. 확인하세요."
            "LANE_CHANGE_CONFLICT" in reasons -> "사각지대에 차량이 있습니다. 차선 변경을 멈추세요."
            "CUT_IN" in reasons || "LANE_INTRUSION" in reasons -> "주변 차량이 내 차 앞으로 끼어들고 있습니다."
            "CHAIN_DECEL" in reasons -> "앞쪽 차량들이 연쇄 감속 중입니다."
            "SUDDEN_BRAKE" in reasons -> "앞 차량이 급정거했습니다."
            "REAR_CLOSING" in reasons -> "뒤 차량이 가까워지고 있습니다."
            else -> "앞 차량과의 거리가 위험하게 가까워졌습니다."
        }
        return "시뮬레이션 경고. $situation"
    }
}
