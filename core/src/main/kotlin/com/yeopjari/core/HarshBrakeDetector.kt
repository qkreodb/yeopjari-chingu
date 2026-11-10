package com.yeopjari.core

/**
 * 급제동 판정: 1초 안에 [dropKmh] 이상 감속했고, 감속 전 속도가 [minStartKmh] 이상이며,
 * 브레이크를 밟은 상태(읽을 수 있을 때)인 경우.
 * 정체 구간의 저속 제동을 빼기 위해 시작 속도 조건을 둔다.
 */
class HarshBrakeDetector(
    private val windowMs: Long = 1_000,
    private val dropKmh: Double = 12.0,
    private val minStartKmh: Double = 30.0,
    private val debounceMs: Long = 3_000,
) {
    private val recent = ArrayDeque<VehicleSample>()
    private var lastDetectedMs: Long? = null

    /** 이번 샘플에서 급제동이 새로 감지되면 true. */
    fun onSample(sample: VehicleSample): Boolean {
        recent.addLast(sample)
        while (recent.isNotEmpty() && sample.timeMs - recent.first().timeMs > windowMs) {
            recent.removeFirst()
        }
        if (sample.brakePressed == false) return false
        val last = lastDetectedMs
        if (last != null && sample.timeMs - last < debounceMs) return false

        val start = recent.firstOrNull { it.speedKmh >= minStartKmh } ?: return false
        val detected = start.speedKmh - sample.speedKmh >= dropKmh
        if (detected) lastDetectedMs = sample.timeMs
        return detected
    }
}
