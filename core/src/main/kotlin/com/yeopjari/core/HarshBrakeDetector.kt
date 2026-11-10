package com.yeopjari.core

/**
 * 급제동 판정: 최근 [lookbackMs] 안의 어느 시점(최고 속도 포함)과 비교해
 * - [dropKmh] 이상 감속했고
 * - 감속률이 초당 [dropKmh] 이상이며
 * - 비교 시점의 속도가 [minStartKmh] 이상(정체 구간의 저속 제동 제외)이고
 * - 브레이크를 밟은 상태(읽을 수 있을 때)인 경우.
 *
 * 차량 신호 콜백 간격이 1초보다 길어도 감속률로 판정할 수 있게 비교 범위를 넉넉히 둔다.
 */
class HarshBrakeDetector(
    private val lookbackMs: Long = 2_000,
    private val dropKmh: Double = 12.0,
    private val minStartKmh: Double = 30.0,
    private val debounceMs: Long = 3_000,
) {
    private val recent = ArrayDeque<VehicleSample>()
    private var lastDetectedMs: Long? = null

    /** 이번 샘플에서 급제동이 새로 감지되면 true. */
    fun onSample(sample: VehicleSample): Boolean {
        val speed = sample.speedKmh ?: return false // 차속을 모르면 판정하지 않는다.
        val candidates = recent.filter { sample.timeMs - it.timeMs in 1..lookbackMs }
        recent.addLast(sample)
        while (recent.isNotEmpty() && sample.timeMs - recent.first().timeMs > lookbackMs) {
            recent.removeFirst()
        }

        if (sample.brakePressed == false) return false
        val last = lastDetectedMs
        if (last != null && sample.timeMs - last < debounceMs) return false

        val detected = candidates.any { before ->
            val beforeSpeed = before.speedKmh ?: return@any false
            val drop = beforeSpeed - speed
            val seconds = (sample.timeMs - before.timeMs) / 1000.0
            beforeSpeed >= minStartKmh && drop >= dropKmh && drop / seconds >= dropKmh
        }
        if (detected) lastDetectedMs = sample.timeMs
        return detected
    }
}
