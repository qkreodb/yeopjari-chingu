package com.yeopjari.core.sim

import com.yeopjari.core.CoachEngine
import com.yeopjari.core.SpotOutcome
import com.yeopjari.core.Utterance

data class ReplayResult(
    val utterances: List<Utterance>,
    val outcomes: List<SpotOutcome>,
    val summary: String,
) {
    /** "00:12 곧 오른쪽 합류예요." 형식의 대본. 시연 영상 자막과 테스트에 쓴다. */
    fun transcript(): List<String> = utterances.map {
        val sec = it.timeMs / 1000
        "%02d:%02d %s".format(sec / 60, sec % 60, it.text)
    }
}

/**
 * 시나리오를 엔진에 시간 순서대로 넣는다. 이벤트 사이에는 [tickMs] 간격으로 tick 을 넣어
 * 통과 후 10초 판정 같은 시간 조건이 실제처럼 진행되게 한다.
 */
object Replayer {
    fun replay(
        scenario: Scenario,
        engine: CoachEngine = CoachEngine(),
        tickMs: Long = 250,
        tailMs: Long = 15_000,
    ): ReplayResult {
        val spoken = mutableListOf<Utterance>()
        val events = scenario.events.sortedBy { it.timeMs }
        var clock = events.firstOrNull()?.timeMs ?: 0L

        fun tickUntil(targetMs: Long) {
            while (clock + tickMs < targetMs) {
                clock += tickMs
                spoken += engine.tick(clock)
            }
        }

        for (event in events) {
            tickUntil(event.timeMs)
            clock = maxOf(clock, event.timeMs)
            spoken += when (event) {
                is ScenarioEvent.Vehicle -> engine.onVehicle(event.sample)
                is ScenarioEvent.Tbt -> engine.onTbt(event.update)
                is ScenarioEvent.Reroute -> engine.onReroute(event.event)
                is ScenarioEvent.Caution -> engine.onCaution(event.update)
                is ScenarioEvent.NavVoice -> engine.onNavVoice(event.timeMs, event.speaking)
            }
        }
        tickUntil(clock + tailMs)
        return ReplayResult(spoken, engine.log.outcomes.toList(), engine.log.summary())
    }
}
