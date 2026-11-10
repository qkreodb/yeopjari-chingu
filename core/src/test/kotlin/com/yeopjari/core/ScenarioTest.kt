package com.yeopjari.core

import com.yeopjari.core.sim.Replayer
import com.yeopjari.core.sim.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 시연 장면 단위로 "무엇을 말하고 무엇을 말하지 않는지"를 고정한다. */
class ScenarioTest {

    private fun texts(name: String) =
        Replayer.replay(Scenarios.all.first { it.name == name }).utterances.map { it.text }

    @Test
    fun `깜빡이를 일찍 켜면 400m 안내를 생략하고 통과 후 사실을 말한다`() {
        val r = Replayer.replay(Scenarios.mergeSignalEarly)
        assertEquals(
            listOf("곧 오른쪽 합류예요.", "합류 구간 지나왔어요. 깜빡이도 미리 켰어요."),
            r.utterances.map { it.text },
        )
        val outcome = r.outcomes.single()
        assertEquals(SpotResult.PASSED, outcome.result)
        assertTrue(outcome.signalDistanceM!! in 550..600)
    }

    @Test
    fun `400m 안내는 깜빡이가 꺼져 있다는 사실만 말한다`() {
        val t = texts("merge_signal_after_reminder")
        assertEquals(
            listOf(
                "곧 오른쪽 합류예요.",
                "400미터 뒤 오른쪽 합류예요. 깜빡이는 아직 꺼져 있어요.",
                "합류 구간 지나왔어요. 깜빡이도 미리 켰어요.",
            ),
            t,
        )
    }

    @Test
    fun `깜빡이 없이 합류하면 지나왔다고 말하지 않는다`() {
        val r = Replayer.replay(Scenarios.mergeNoSignal)
        assertFalse(r.utterances.any { it.kind == UtteranceKind.AFTER_PASS })
        assertEquals(SpotResult.PASSED_NO_SIGNAL, r.outcomes.single().result)
    }

    @Test
    fun `통과 직후 급제동이 있으면 판정을 보류하고 침묵한다`() {
        val r = Replayer.replay(Scenarios.mergeThenHarshBrake)
        assertFalse(r.utterances.any { it.kind == UtteranceKind.AFTER_PASS })
        assertEquals(SpotResult.UNCERTAIN, r.outcomes.single().result)
    }

    @Test
    fun `IC를 놓치면 늘어난 시간만큼 차분하게 말한다`() {
        val r = Replayer.replay(Scenarios.icMissed)
        assertTrue("괜찮아요, 3분만 더 걸려요." in r.utterances.map { it.text })
        assertEquals(SpotResult.MISSED, r.outcomes.first().result)
    }

    @Test
    fun `재탐색 이벤트가 없어도 ETA 증가로 놓침을 추정한다`() {
        val r = Replayer.replay(Scenarios.icMissedWithoutRerouteEvent)
        assertTrue("괜찮아요, 2분만 더 걸려요." in r.utterances.map { it.text })
        assertEquals(SpotResult.MISSED, r.outcomes.first().result)
    }

    @Test
    fun `회전교차로는 출구 번호를 말하고 깜빡이 없이도 통과를 인정한다`() {
        val t = texts("roundabout")
        assertEquals(
            listOf("곧 회전교차로예요. 2번째 출구예요.", "400미터 뒤 회전교차로예요. 2번째 출구예요.", "회전교차로 지나왔어요."),
            t,
        )
    }

    @Test
    fun `어떤 장면에서도 한 지점에서 3마디를 넘지 않고 운전 지시를 하지 않는다`() {
        val forbidden = listOf("맞춰요", "충분해요", "켜세요", "줄이세요", "먼저요")
        for (scenario in Scenarios.all) {
            val r = Replayer.replay(scenario)
            val texts = r.utterances.map { it.text }
            assertTrue(texts.none { t -> forbidden.any { it in t } }, "${scenario.name}: $texts")
            val perSpotKinds = r.utterances.filter { it.kind != UtteranceKind.REROUTE && it.kind != UtteranceKind.CAUTION }
            assertTrue(perSpotKinds.size <= 3 * maxOf(1, r.outcomes.size), "${scenario.name}: $texts")
        }
    }

    @Test
    fun `주행 요약은 지나온 지점만 사실대로 센다`() {
        assertEquals("오늘 합류 구간 1번 지나왔어요.", Replayer.replay(Scenarios.mergeSignalEarly).summary)
        assertEquals("오늘 합류 구간 1번 지나왔어요.", Replayer.replay(Scenarios.mergeNoSignal).summary)
        assertEquals("오늘은 미리 안내한 어려운 구간 없이 다녀왔어요.", Replayer.replay(Scenarios.icMissed).summary)
    }
}
