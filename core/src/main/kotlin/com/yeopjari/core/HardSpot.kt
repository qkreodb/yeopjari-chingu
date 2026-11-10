package com.yeopjari.core

/** 옆자리 친구가 미리 말해 주는 어려운 지점. */
enum class HardSpotType {
    MERGE, IC, SPLIT, ROUNDABOUT;

    /** 깜빡이를 켰는지가 판정 조건에 들어가는 종류. */
    val requiresSignal: Boolean get() = this == MERGE || this == IC

    companion object {
        fun of(kind: ManeuverKind): HardSpotType? = when (kind) {
            ManeuverKind.MERGE -> MERGE
            ManeuverKind.IC -> IC
            ManeuverKind.SPLIT -> SPLIT
            ManeuverKind.ROUNDABOUT -> ROUNDABOUT
            else -> null
        }
    }
}

enum class SpotResult {
    /** 필요한 조건이 모두 확인됨. "지나왔어요"를 말한다. */
    PASSED,

    /** 지점은 지났지만 깜빡이가 확인되지 않음. 말하지 않는다. */
    PASSED_NO_SIGNAL,

    /** 재탐색으로 지점을 놓침. 차분한 재탐색 멘트만 한다. */
    MISSED,

    /** 판정에 필요한 근거가 부족함(예: 통과 직후 급제동). 말하지 않는다. */
    UNCERTAIN,
}

data class SpotOutcome(
    val timeMs: Long,
    val spotId: String,
    val type: HardSpotType,
    val direction: Direction,
    val result: SpotResult,
    /** 같은 방향 깜빡이가 지점 전에 켜졌음이 확인됨. */
    val signalConfirmed: Boolean,
    /** 깜빡이가 처음 켜졌을 때 남은 거리(m). */
    val signalDistanceM: Int?,
)
