package com.yeopjari.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarshBrakeDetectorTest {
    private fun s(t: Long, v: Double, brake: Boolean? = true) = VehicleSample(t, v, Gear.D, brakePressed = brake)

    @Test
    fun `1초에 12km 이상 줄면 급제동`() {
        val d = HarshBrakeDetector()
        assertFalse(d.onSample(s(0, 60.0)))
        assertFalse(d.onSample(s(500, 55.0)))
        assertTrue(d.onSample(s(1_000, 47.0)))
    }

    @Test
    fun `시작 속도가 30km 미만이면 급제동이 아니다`() {
        val d = HarshBrakeDetector()
        d.onSample(s(0, 25.0))
        assertFalse(d.onSample(s(1_000, 5.0)))
    }

    @Test
    fun `브레이크를 밟지 않은 감속은 급제동이 아니다`() {
        val d = HarshBrakeDetector()
        d.onSample(s(0, 60.0, brake = false))
        assertFalse(d.onSample(s(1_000, 40.0, brake = false)))
    }

    @Test
    fun `같은 제동을 두 번 세지 않는다`() {
        val d = HarshBrakeDetector()
        d.onSample(s(0, 80.0))
        assertTrue(d.onSample(s(1_000, 60.0)))
        assertFalse(d.onSample(s(1_500, 50.0)))
    }
}

class FadingStateTest {
    private fun outcome(result: SpotResult) =
        SpotOutcome(0, "x", HardSpotType.MERGE, Direction.RIGHT, result, result == SpotResult.PASSED, null)

    @Test
    fun `연속 5번이면 400m만, 10번이면 침묵`() {
        val f = FadingState()
        repeat(5) { f.record(outcome(SpotResult.PASSED)) }
        assertEquals(GuidanceLevel.REDUCED, f.level(HardSpotType.MERGE))
        repeat(5) { f.record(outcome(SpotResult.PASSED)) }
        assertEquals(GuidanceLevel.SILENT, f.level(HardSpotType.MERGE))
        assertEquals(GuidanceLevel.FULL, f.level(HardSpotType.IC))
    }

    @Test
    fun `놓치면 처음으로, 깜빡이 없는 통과는 그대로`() {
        val f = FadingState()
        repeat(6) { f.record(outcome(SpotResult.PASSED)) }
        f.record(outcome(SpotResult.PASSED_NO_SIGNAL))
        assertEquals(6, f.streak(HardSpotType.MERGE))
        f.record(outcome(SpotResult.MISSED))
        assertEquals(GuidanceLevel.FULL, f.level(HardSpotType.MERGE))
    }

    @Test
    fun `끄면 항상 전부 안내`() {
        val f = FadingState(enabled = false)
        repeat(12) { f.record(outcome(SpotResult.PASSED)) }
        assertEquals(GuidanceLevel.FULL, f.level(HardSpotType.MERGE))
    }
}

class VoiceBudgetTest {
    private val open = SpeakGate(steeringDeg = 0.0, lastHarshBrakeMs = null, navVoiceSpeaking = false)

    @Test
    fun `회전 중이나 내비가 말하는 중에는 기다렸다가 말한다`() {
        val b = VoiceBudget()
        b.enqueue(0, "s", UtteranceKind.PRE_1KM, "곧 오른쪽 합류예요.")
        assertTrue(b.flush(0, open.copy(steeringDeg = 45.0)).isEmpty())
        assertTrue(b.flush(1_000, open.copy(navVoiceSpeaking = true)).isEmpty())
        assertEquals(1, b.flush(2_000, open).size)
    }

    @Test
    fun `10초가 지난 문장은 버린다`() {
        val b = VoiceBudget()
        b.enqueue(0, "s", UtteranceKind.PRE_1KM, "a")
        assertTrue(b.flush(10_001, open).isEmpty())
        assertEquals(0, b.pendingCount())
    }

    @Test
    fun `급제동 후 20초는 말하지 않는다`() {
        val b = VoiceBudget()
        b.enqueue(5_000, null, UtteranceKind.REROUTE, "a")
        assertTrue(b.flush(5_000, open.copy(lastHarshBrakeMs = 0)).isEmpty())
        b.enqueue(21_000, null, UtteranceKind.REROUTE, "b")
        assertEquals(listOf("b"), b.flush(21_000, open.copy(lastHarshBrakeMs = 0)).map { it.text })
    }

    @Test
    fun `한 지점에서 3마디까지만`() {
        val b = VoiceBudget()
        repeat(3) { assertTrue(b.enqueue(0, "s", UtteranceKind.PRE_1KM, "x$it")) }
        assertFalse(b.enqueue(0, "s", UtteranceKind.AFTER_PASS, "x3"))
    }

    @Test
    fun `문장 사이 3초 간격`() {
        val b = VoiceBudget()
        b.enqueue(0, null, UtteranceKind.REROUTE, "a")
        b.enqueue(0, null, UtteranceKind.REROUTE, "b")
        assertEquals(listOf("a"), b.flush(0, open).map { it.text })
        assertTrue(b.flush(2_000, open).isEmpty())
        assertEquals(listOf("b"), b.flush(3_000, open).map { it.text })
    }
}

class EngineEdgeCaseTest {
    @Test
    fun `깜빡이를 읽을 수 없는 차에서는 꺼져 있다고 말하지 않고, 통과는 깜빡이 언급 없이 말한다`() {
        val e = CoachEngine()
        val said = mutableListOf<Utterance>()
        var t = 0L
        for (r in 1_200 downTo 0 step 8) {
            said += e.onTbt(TbtUpdate(t, "m", ManeuverKind.MERGE, Direction.RIGHT, r, 1_000))
            said += e.onVehicle(VehicleSample(t, 60.0, Gear.D, signal = null, brakePressed = null, steeringDeg = null))
            t += 500
        }
        said += e.onTbt(TbtUpdate(t, "n", ManeuverKind.TURN, Direction.LEFT, 2_000, 1_000))
        for (k in 1..60) said += e.tick(t + k * 250L)
        val texts = said.map { it.text }
        assertTrue(texts.none { "꺼져 있어요" in it }, "$texts")
        assertTrue("400미터 뒤 오른쪽 합류예요." in texts, "$texts")
        assertTrue("합류 구간 지나왔어요." in texts, "$texts")
        assertEquals(SpotResult.PASSED, e.log.outcomes.single().result)
    }

    @Test
    fun `안내 줄이기 단계가 오르면 1km 안내와 통과 후 말을 생략한다`() {
        val fading = FadingState()
        repeat(5) {
            fading.record(SpotOutcome(0, "p$it", HardSpotType.MERGE, Direction.RIGHT, SpotResult.PASSED, true, 500))
        }
        val r = com.yeopjari.core.sim.Replayer.replay(
            com.yeopjari.core.sim.Scenarios.mergeSignalAfterReminder,
            CoachEngine(fading = fading),
        )
        assertEquals(
            listOf("400미터 뒤 오른쪽 합류예요. 깜빡이는 아직 꺼져 있어요."),
            r.utterances.map { it.text },
        )
    }
}

class PracticeLadderTest {
    private val ladder = PracticeLadder()

    @Test
    fun `단계는 순서대로만 오른다`() {
        assertEquals(1, ladder.stage(emptyMap()))
        assertEquals(2, ladder.stage(mapOf(HardSpotType.ROUNDABOUT to 3)))
        assertEquals(2, ladder.stage(mapOf(HardSpotType.ROUNDABOUT to 3, HardSpotType.MERGE to 9)))
        assertEquals(
            4,
            ladder.stage(mapOf(HardSpotType.ROUNDABOUT to 3, HardSpotType.SPLIT to 5, HardSpotType.MERGE to 5)),
        )
    }

    @Test
    fun `다음 목표를 남은 횟수로 말한다`() {
        assertEquals("다음 단계까지 회전교차로 2번 남았어요.", ladder.nextGoal(mapOf(HardSpotType.ROUNDABOUT to 1)))
        assertNull(
            ladder.nextGoal(mapOf(HardSpotType.ROUNDABOUT to 3, HardSpotType.SPLIT to 5, HardSpotType.MERGE to 5)),
        )
    }
}
