package com.yeopjari.core

/**
 * 연습 사다리(F10). 쉬운 지점부터 단계적으로 넓힌다.
 * 노출 치료의 '쉬운 것부터' 원리를 빌렸지만 치료 효과를 주장하지는 않는다.
 */
class PracticeLadder(
    private val steps: List<Step> = listOf(
        Step(stage = 1, label = "동네길: 회전교차로", type = HardSpotType.ROUNDABOUT, required = 3),
        Step(stage = 2, label = "간선도로 분기", type = HardSpotType.SPLIT, required = 5),
        Step(stage = 3, label = "자동차전용도로 합류", type = HardSpotType.MERGE, required = 5),
    ),
    private val finalLabel: String = "고속도로 IC 합류",
) {
    data class Step(val stage: Int, val label: String, val type: HardSpotType, val required: Int)

    val lastStage: Int get() = steps.size + 1

    /**
     * [passedTotals] 는 종류별 누적 PASSED 횟수(깜빡이까지 확인된 것)다.
     * [TripLog.confirmedPassTotals] 를 누적해 넣는다. PASSED_NO_SIGNAL 을 섞으면 깜빡이 없이 단계가 오른다.
     */
    fun stage(passedTotals: Map<HardSpotType, Int>): Int {
        var stage = 1
        for (step in steps) {
            if ((passedTotals[step.type] ?: 0) >= step.required) stage = step.stage + 1 else break
        }
        return stage
    }

    fun label(stage: Int): String = steps.firstOrNull { it.stage == stage }?.label ?: finalLabel

    /** 다음 단계까지 남은 것. 마지막 단계면 null. */
    fun nextGoal(passedTotals: Map<HardSpotType, Int>): String? {
        val current = stage(passedTotals)
        val step = steps.firstOrNull { it.stage == current } ?: return null
        val left = step.required - (passedTotals[step.type] ?: 0)
        return "다음 단계까지 ${Phrases.typeName(step.type)} ${left}번 남았어요."
    }
}
