package com.yeopjari.core

import com.yeopjari.core.sim.Replayer
import com.yeopjari.core.sim.scenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 1단계 코드 리뷰에서 나온 문제를 고정하는 회귀 테스트. */
class ReviewRegressionTest {

    @Test
    fun `지점 훨씬 앞에서 다른 이유로 켠 깜빡이는 인정하지 않는다`() {
        val s = scenario("far_signal", "3km 앞 차로 변경 깜빡이") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 3_000) { r ->
                signal = if (r in 2_500..3_000) TurnSignal.RIGHT else TurnSignal.OFF
            }
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertEquals(SpotResult.PASSED_NO_SIGNAL, r.outcomes.single().result)
        assertTrue(r.utterances.any { "아직 꺼져 있어요" in it.text })
        assertFalse(r.utterances.any { "미리 켰어요" in it.text })
    }

    @Test
    fun `지점에 도달한 뒤 켠 깜빡이는 인정하지 않는다`() {
        val s = scenario("late_signal", "합류 지점에서야 깜빡이") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_500) { r ->
                if (r <= 20) signal = TurnSignal.RIGHT
            }
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            drive(15_000)
        }
        assertEquals(SpotResult.PASSED_NO_SIGNAL, Replayer.replay(s).outcomes.single().result)
    }

    @Test
    fun `재탐색 직후 새로 안내된 지점을 놓친 것으로 오판하지 않는다`() {
        val s = scenario("reroute_then_new_spot", "재탐색 뒤 새 합류 지점을 정상 통과") {
            reroute()
            approach("m9", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_200) { r ->
                if (r <= 500) signal = TurnSignal.RIGHT
            }
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            signal = TurnSignal.OFF
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertEquals(listOf(SpotResult.PASSED), r.outcomes.map { it.result })
        assertTrue(r.utterances.any { it.text == "괜찮아요, 금방이에요." || it.text == "괜찮아요, 다시 안내할게요." })
    }

    @Test
    fun `지나쳐서 돌아오는 경로의 같은 지점은 처음부터 다시 안내한다`() {
        val s = scenario("overshoot_same_id", "합류 300m 전에서 경로 이탈, 같은 지점으로 돌아옴") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_500, toM = 300)
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_400, toM = 900)
            drive(5_000)
        }
        val r = Replayer.replay(s)
        assertEquals(SpotResult.MISSED, r.outcomes.first().result)
        assertEquals(2, r.utterances.count { it.kind == UtteranceKind.PRE_1KM })
    }

    @Test
    fun `지점에 거의 다 와서 재탐색되면 놓침도 칭찬도 하지 않는다`() {
        val s = scenario("reroute_at_spot", "합류 지점에서 재탐색") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_200) { r ->
                if (r <= 600) signal = TurnSignal.RIGHT
            }
            reroute()
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertEquals(SpotResult.UNCERTAIN, r.outcomes.single().result)
        assertFalse(r.utterances.any { it.kind == UtteranceKind.AFTER_PASS })
    }

    @Test
    fun `TBT 갱신이 드물어도 정상 통과를 인정한다`() {
        val e = CoachEngine()
        val said = mutableListOf<Utterance>()
        var t = 0L
        // 3초마다 갱신, 시속 72km(초당 20m)로 접근. 마지막 갱신은 80m.
        for (r in listOf(1_220, 1_160, 1_100, 1_040, 980, 920, 860, 800, 740, 680, 620, 560, 500, 440, 380, 320, 260, 200, 140, 80)) {
            said += e.onTbt(TbtUpdate(t, "m", ManeuverKind.MERGE, Direction.RIGHT, r, 2_000))
            for (k in 0 until 6) {
                val st = t + k * 500
                said += e.onVehicle(
                    VehicleSample(st, 72.0, Gear.D, if (r <= 700) TurnSignal.RIGHT else TurnSignal.OFF, false, 0.0),
                )
            }
            t += 3_000
        }
        said += e.onTbt(TbtUpdate(t, "n", ManeuverKind.TURN, Direction.LEFT, 3_000, 2_000))
        for (k in 1..60) said += e.tick(t + k * 250L)
        assertEquals(SpotResult.PASSED, e.log.outcomes.single().result)
        assertTrue(said.any { it.text == "합류 구간 지나왔어요. 깜빡이도 미리 켰어요." })
    }

    @Test
    fun `평범한 정체로 ETA만 늘어난 정상 통과는 놓침이 아니다`() {
        val s = scenario("eta_up_after_pass", "통과 직후 정체로 ETA 증가") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_200) { r ->
                if (r <= 600) signal = TurnSignal.RIGHT
            }
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000, addEtaSec = 120)
            signal = TurnSignal.OFF
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertEquals(SpotResult.PASSED, r.outcomes.single().result)
        assertFalse(r.utterances.any { it.kind == UtteranceKind.REROUTE })
    }

    @Test
    fun `판정이 늦게 확정돼도 통과 후 말이 만료되지 않는다`() {
        val e = CoachEngine()
        for (r in 1_200 downTo 0 step 8) {
            val t = (1_200 - r) / 8 * 500L
            e.onTbt(TbtUpdate(t, "m", ManeuverKind.MERGE, Direction.RIGHT, r, 1_000))
            e.onVehicle(VehicleSample(t, 60.0, Gear.D, if (r <= 600) TurnSignal.RIGHT else TurnSignal.OFF, false, 0.0))
        }
        val passT = 75_500L
        e.onTbt(TbtUpdate(passT, "n", ManeuverKind.TURN, Direction.LEFT, 2_000, 1_000))
        // 25초 동안 이벤트가 없다가 다음 샘플이 온다.
        val said = e.onVehicle(VehicleSample(passT + 25_000, 60.0, Gear.D, TurnSignal.OFF, false, 0.0))
        assertEquals(listOf("합류 구간 지나왔어요. 깜빡이도 미리 켰어요."), said.map { it.text })
    }

    @Test
    fun `마지막 지점 뒤 안내가 끝나도 P단에서 판정을 남긴다`() {
        val s = scenario("last_spot", "목적지 직전 합류 후 주차") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_200) { r ->
                if (r <= 600) signal = TurnSignal.RIGHT
            }
            speedKmh = 0.0
            gear = Gear.P
            drive(1_000)
        }
        assertEquals(SpotResult.PASSED, Replayer.replay(s).outcomes.single().result)
    }

    @Test
    fun `연습 사다리는 깜빡이까지 확인된 통과만 센다`() {
        val log = TripLog()
        repeat(5) {
            log.add(SpotOutcome(0, "m$it", HardSpotType.MERGE, Direction.RIGHT, SpotResult.PASSED_NO_SIGNAL, false, null))
        }
        assertEquals(0, log.confirmedPassTotals()[HardSpotType.MERGE])
        assertEquals(5, log.passedCount(HardSpotType.MERGE))
    }
}
