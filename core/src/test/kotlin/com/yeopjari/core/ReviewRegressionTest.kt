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

    // ---- 2차 리뷰 ----

    @Test
    fun `TBT 갱신 사이에 지점 바로 앞에서 켠 깜빡이를 먼 거리로 기록하지 않는다`() {
        val e = CoachEngine()
        // 100km/h(초당 약 27.8m), TBT는 900m에서 한 번, 이후 3초 동안 갱신 없음
        e.onVehicle(VehicleSample(0, 100.0, Gear.D, TurnSignal.OFF, false, 0.0))
        e.onTbt(TbtUpdate(0, "m", ManeuverKind.MERGE, Direction.RIGHT, 900, 1_000))
        e.onVehicle(VehicleSample(500, 100.0, Gear.D, TurnSignal.OFF, false, 0.0))
        // 30초쯤 지나 지점 60m 앞: 마지막 TBT가 오래돼 추정하지 않으므로 인정하지 않는다.
        e.onVehicle(VehicleSample(30_500, 100.0, Gear.D, TurnSignal.RIGHT, false, 0.0))
        e.onTbt(TbtUpdate(31_000, "n", ManeuverKind.TURN, Direction.LEFT, 2_000, 1_000))
        for (k in 1..60) e.tick(31_000 + k * 250L)
        assertFalse(e.log.outcomes.any { it.result == SpotResult.PASSED && it.signalConfirmed })
    }

    @Test
    fun `인정 구간에 들어오기 전부터 켜져 있던 깜빡이는 인정하지 않는다`() {
        val s = scenario("left_on", "3km 전부터 켜 둔 깜빡이") {
            signal = TurnSignal.RIGHT
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 3_000)
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            signal = TurnSignal.OFF
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertEquals(SpotResult.PASSED_NO_SIGNAL, r.outcomes.single().result)
        // 켜져 있는 동안 "꺼져 있어요"라고 말하지도 않는다.
        assertFalse(r.utterances.any { "꺼져 있어요" in it.text })
    }

    @Test
    fun `방향을 모르는 합류에서는 어떤 깜빡이도 칭찬 근거가 되지 않는다`() {
        val s = scenario("merge_dir_unknown", "방향 정보가 없는 합류") {
            approach("m1", ManeuverKind.MERGE, Direction.NONE, fromM = 1_200) { r ->
                if (r <= 600) signal = TurnSignal.LEFT
            }
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            signal = TurnSignal.OFF
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertFalse(r.utterances.any { "미리 켰어요" in it.text || "꺼져 있어요" in it.text }, "${r.utterances}")
        assertFalse(r.outcomes.single().signalConfirmed)
    }

    @Test
    fun `합류 직전 급제동도 판정을 보류한다`() {
        val s = scenario("brake_before_pass", "합류 지점 2초 전 급제동") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_200, toM = 60) { r ->
                if (r <= 600) signal = TurnSignal.RIGHT
            }
            brakeTo(35.0)
            speedKmh = 35.0
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 50)
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            signal = TurnSignal.OFF
            drive(40_000)
        }
        val r = Replayer.replay(s)
        assertEquals(SpotResult.UNCERTAIN, r.outcomes.single().result)
        assertFalse(r.utterances.any { it.kind == UtteranceKind.AFTER_PASS })
    }

    @Test
    fun `휴게소에서 잠깐 P단에 둬도 앞의 지점을 지우지 않는다`() {
        val s = scenario("rest_stop", "지점 5km 전 휴게소 정차 후 출발") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 6_000, toM = 5_000)
            val moving = speedKmh
            speedKmh = 0.0
            gear = Gear.P
            drive(60_000)
            gear = Gear.D
            speedKmh = moving
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 5_000) { r ->
                if (r <= 600) signal = TurnSignal.RIGHT
            }
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            signal = TurnSignal.OFF
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertEquals(listOf(SpotResult.PASSED), r.outcomes.map { it.result })
    }

    @Test
    fun `재탐색 직후 옛 ETA가 담긴 갱신으로 시간을 계산하지 않는다`() {
        val e = CoachEngine()
        e.onVehicle(VehicleSample(0, 60.0, Gear.D, TurnSignal.OFF, false, 0.0))
        e.onTbt(TbtUpdate(0, "t1", ManeuverKind.TURN, Direction.LEFT, 800, 1_000))
        val said = mutableListOf<Utterance>()
        said += e.onReroute(RerouteEvent(500))
        // 같은 안내, 옛 ETA → 아직 새 경로 아님
        said += e.onTbt(TbtUpdate(1_000, "t1", ManeuverKind.TURN, Direction.LEFT, 790, 1_000))
        // 새 경로: ETA 4분 증가
        said += e.onTbt(TbtUpdate(2_000, "t9", ManeuverKind.TURN, Direction.RIGHT, 1_500, 1_240))
        for (k in 1..20) said += e.tick(2_000 + k * 250L)
        assertEquals(listOf("괜찮아요, 4분만 더 걸려요."), said.map { it.text })
    }

    @Test
    fun `GPS 흔들림으로 남은 거리가 조금 늘어도 놓침으로 보지 않는다`() {
        val s = scenario("gps_drift", "터널 뒤 남은 거리 150m 증가") {
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_500, toM = 800)
            approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 950) { r ->
                if (r <= 600) signal = TurnSignal.RIGHT
            }
            next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_000)
            signal = TurnSignal.OFF
            drive(15_000)
        }
        val r = Replayer.replay(s)
        assertEquals(listOf(SpotResult.PASSED), r.outcomes.map { it.result })
        assertEquals(1, r.utterances.count { it.kind == UtteranceKind.PRE_1KM })
    }

    @Test
    fun `말하지 못하고 만료된 문장은 지점 예산을 쓰지 않는다`() {
        val b = VoiceBudget()
        val blocked = SpeakGate(steeringDeg = 45.0, lastHarshBrakeMs = null, navVoiceSpeaking = false)
        val open = blocked.copy(steeringDeg = 0.0)
        b.enqueue(0, "s", UtteranceKind.PRE_1KM, "a")
        b.enqueue(0, "s", UtteranceKind.PRE_400M, "b")
        assertTrue(b.flush(5_000, blocked).isEmpty())
        assertTrue(b.flush(11_000, blocked).isEmpty()) // 둘 다 만료
        assertTrue(b.enqueue(11_000, "s", UtteranceKind.AFTER_PASS, "c"))
        assertEquals(listOf("c"), b.flush(11_000, open).map { it.text })
    }

    @Test
    fun `통과 직후 급제동으로 보류된 지점도 요약에 기록으로 남는다`() {
        assertEquals(
            "오늘은 어려운 구간 1곳의 기록을 남겼어요.",
            Replayer.replay(com.yeopjari.core.sim.Scenarios.mergeThenHarshBrake).summary,
        )
    }

    // ---- 2단계 리뷰 ----

    @Test
    fun `재탐색 완료 알림은 새 경로의 지점을 놓친 것으로 보지 않고 멘트를 한 번만 한다`() {
        val e = CoachEngine()
        val said = mutableListOf<Utterance>()
        e.onVehicle(VehicleSample(0, 60.0, Gear.D, TurnSignal.OFF, false, 0.0))
        // 옛 지점에서 벗어나며 ETA 3분 증가 → leave() 가 놓침 판정 + 멘트
        said += e.onTbt(TbtUpdate(0, "a", ManeuverKind.IC, Direction.RIGHT, 600, 1_000))
        said += e.onTbt(TbtUpdate(500, "b", ManeuverKind.MERGE, Direction.RIGHT, 2_000, 1_180))
        // 그 뒤 Pleos 재탐색 완료 알림
        said += e.onRerouteCompleted(1_000)
        for (k in 1..40) said += e.tick(1_000 + k * 250L)
        assertEquals(listOf(SpotResult.MISSED), e.log.outcomes.map { it.result })
        assertEquals(1, said.count { it.kind == UtteranceKind.REROUTE })
    }

    @Test
    fun `차속과 기어를 읽을 수 없으면 정지나 P단으로 보지 않는다`() {
        val e = CoachEngine()
        e.onTbt(TbtUpdate(0, "m", ManeuverKind.MERGE, Direction.RIGHT, 300, 1_000))
        repeat(40) { k -> e.onVehicle(VehicleSample(k * 250L, null, null, TurnSignal.OFF, false, 0.0)) }
        // 거리 추정이 불가능하므로 도달로 보지 않고, P단 처리도 하지 않는다.
        assertTrue(e.log.outcomes.isEmpty())
        e.onTbt(TbtUpdate(10_500, "n", ManeuverKind.TURN, Direction.LEFT, 1_000, 1_000))
        assertEquals(SpotResult.UNCERTAIN, e.log.outcomes.single().result)
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
