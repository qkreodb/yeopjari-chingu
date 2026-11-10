package com.yeopjari.core

import kotlin.math.abs

enum class UtteranceKind { PRE_1KM, PRE_400M, AFTER_PASS, REROUTE, CAUTION }

data class Utterance(val timeMs: Long, val kind: UtteranceKind, val text: String)

/** 지금 말해도 되는지 판단하는 데 쓰는 주행 상태. */
data class SpeakGate(
    val steeringDeg: Double?,
    val lastHarshBrakeMs: Long?,
    val navVoiceSpeaking: Boolean,
)

data class BudgetConfig(
    val maxPerSpot: Int = 3,
    val expireMs: Long = 10_000,
    val silenceAfterHarshBrakeMs: Long = 20_000,
    val silenceSteeringDeg: Double = 30.0,
    /** 문장 사이 최소 간격. 한 문장이 3초 안쪽이므로 겹치지 않게 한다. */
    val minGapMs: Long = 3_000,
)

/**
 * 발화 예산(F6). 말할 문장을 큐에 담았다가 말해도 되는 순간에만 하나씩 내보낸다.
 * - 회전 중, 급제동 직후, 기본 내비가 말하는 중에는 말하지 않는다.
 * - 10초가 지난 문장은 버린다(지나간 상황을 늦게 말하면 혼란만 준다).
 * - 한 지점에서 최대 [BudgetConfig.maxPerSpot] 마디.
 */
class VoiceBudget(private val config: BudgetConfig = BudgetConfig()) {
    private data class Pending(val createdMs: Long, val spotId: String?, val kind: UtteranceKind, val text: String)

    private val queue = ArrayDeque<Pending>()
    private val countPerSpot = mutableMapOf<String, Int>()
    private var lastSpokenMs: Long? = null

    /** 넣었으면 true. 지점 예산을 넘으면 넣지 않는다. */
    fun enqueue(timeMs: Long, spotId: String?, kind: UtteranceKind, text: String): Boolean {
        if (spotId != null) {
            val used = countPerSpot[spotId] ?: 0
            if (used >= config.maxPerSpot) return false
            countPerSpot[spotId] = used + 1
        }
        queue.addLast(Pending(timeMs, spotId, kind, text))
        return true
    }

    fun flush(nowMs: Long, gate: SpeakGate): List<Utterance> {
        while (queue.isNotEmpty() && nowMs - queue.first().createdMs > config.expireMs) {
            queue.removeFirst()
        }
        if (queue.isEmpty() || !canSpeak(nowMs, gate)) return emptyList()
        val next = queue.removeFirst()
        lastSpokenMs = nowMs
        return listOf(Utterance(nowMs, next.kind, next.text))
    }

    fun pendingCount(): Int = queue.size

    private fun canSpeak(nowMs: Long, gate: SpeakGate): Boolean {
        if (gate.navVoiceSpeaking) return false
        val steering = gate.steeringDeg
        if (steering != null && abs(steering) > config.silenceSteeringDeg) return false
        val brake = gate.lastHarshBrakeMs
        if (brake != null && nowMs - brake < config.silenceAfterHarshBrakeMs) return false
        val last = lastSpokenMs
        if (last != null && nowMs - last < config.minGapMs) return false
        return true
    }
}
