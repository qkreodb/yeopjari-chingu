package com.yeopjari.core

/** 한 지점에서 허용되는 안내. */
data class GuidanceLevel(
    val pre1km: Boolean,
    val pre400m: Boolean,
    val afterPass: Boolean,
) {
    companion object {
        val FULL = GuidanceLevel(pre1km = true, pre400m = true, afterPass = true)
        val REDUCED = GuidanceLevel(pre1km = false, pre400m = true, afterPass = false)
        val SILENT = GuidanceLevel(pre1km = false, pre400m = false, afterPass = false)
    }
}

/**
 * 안내 줄이기(F12). 같은 종류를 연속으로 PASSED 하면 그 종류의 안내를 줄인다.
 * 0~4번: 전부, 5~9번: 400m만, 10번 이상: 없음. MISSED 가 나오면 0으로 돌아간다.
 */
class FadingState(
    var enabled: Boolean = true,
    private val reduceAt: Int = 5,
    private val silentAt: Int = 10,
) {
    private val streaks = mutableMapOf<HardSpotType, Int>()

    fun streak(type: HardSpotType): Int = streaks[type] ?: 0

    fun level(type: HardSpotType): GuidanceLevel {
        if (!enabled) return GuidanceLevel.FULL
        val s = streak(type)
        return when {
            s >= silentAt -> GuidanceLevel.SILENT
            s >= reduceAt -> GuidanceLevel.REDUCED
            else -> GuidanceLevel.FULL
        }
    }

    fun record(outcome: SpotOutcome) {
        when (outcome.result) {
            SpotResult.PASSED -> streaks[outcome.type] = streak(outcome.type) + 1
            SpotResult.MISSED -> streaks[outcome.type] = 0
            SpotResult.PASSED_NO_SIGNAL, SpotResult.UNCERTAIN -> Unit
        }
    }

    /** 사용자가 설정에서 되돌릴 때. */
    fun reset(type: HardSpotType? = null) {
        if (type == null) streaks.clear() else streaks.remove(type)
    }
}
