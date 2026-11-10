package com.yeopjari.core

/** 한 번의 주행에서 나온 지점 판정. 주차 후 '내 기록'(F8)에 쓴다. 점수나 순위는 만들지 않는다. */
class TripLog {
    private val items = mutableListOf<SpotOutcome>()

    val outcomes: List<SpotOutcome> get() = items

    fun add(outcome: SpotOutcome) {
        items += outcome
    }

    /** 지점을 지나온 횟수(깜빡이 여부와 무관). */
    fun passedCount(type: HardSpotType): Int = items.count {
        it.type == type && (it.result == SpotResult.PASSED || it.result == SpotResult.PASSED_NO_SIGNAL)
    }

    fun count(type: HardSpotType, result: SpotResult): Int = items.count { it.type == type && it.result == result }

    /** 주차 후 한 문장 요약. 사실만 말한다. */
    fun summary(): String {
        val parts = HardSpotType.entries.mapNotNull { type ->
            val n = passedCount(type)
            if (n > 0) "${Phrases.typeName(type)} ${n}번" else null
        }
        return if (parts.isEmpty()) {
            "오늘은 미리 안내한 어려운 구간 없이 다녀왔어요."
        } else {
            "오늘 ${parts.joinToString(", ")} 지나왔어요."
        }
    }
}
