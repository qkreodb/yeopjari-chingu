package com.yeopjari.core.sim

import com.yeopjari.core.Direction
import com.yeopjari.core.ManeuverKind
import com.yeopjari.core.TurnSignal

/** 시연과 테스트에 함께 쓰는 장면들. 시연 화면의 신호 주입 패널도 이 장면에서 출발한다. */
object Scenarios {

    /** 합류 400m 전보다 먼저 깜빡이를 켬: 1km 안내 → 400m는 생략 → 통과 후 사실 확인. */
    val mergeSignalEarly = scenario("merge_signal_early", "합류 600m 전에 오른쪽 깜빡이를 켜고 합류") {
        approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_500) { r ->
            if (r <= 600) signal = TurnSignal.RIGHT
        }
        next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_400)
        signal = TurnSignal.OFF
        drive(15_000)
    }

    /** 400m 안내 뒤 깜빡이를 켬: 400m에서 "아직 꺼져 있어요" → 켬 → 통과 후 사실 확인. */
    val mergeSignalAfterReminder = scenario("merge_signal_after_reminder", "400m 안내를 듣고 깜빡이를 켜고 합류") {
        approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_500) { r ->
            if (r <= 330) signal = TurnSignal.RIGHT
        }
        next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_400)
        signal = TurnSignal.OFF
        drive(15_000)
    }

    /** 깜빡이 없이 합류: 지나왔다고 말하지 않는다(틀린 칭찬 방지). */
    val mergeNoSignal = scenario("merge_no_signal", "깜빡이를 켜지 않고 합류") {
        approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_500)
        next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_400)
        drive(15_000)
    }

    /** 합류 직후 급제동: 판정을 보류하고 20초 동안 말하지 않는다. */
    val mergeThenHarshBrake = scenario("merge_then_harsh_brake", "깜빡이를 켜고 합류했지만 직후 급제동") {
        approach("m1", ManeuverKind.MERGE, Direction.RIGHT, fromM = 1_500) { r ->
            if (r <= 600) signal = TurnSignal.RIGHT
        }
        next("t2", ManeuverKind.TURN, Direction.LEFT, remainingM = 2_400)
        signal = TurnSignal.OFF
        drive(3_000)
        brakeTo(40.0)
        drive(15_000)
    }

    /** IC 진출을 놓침: 명시적 재탐색 + ETA 3분 증가 → "괜찮아요, 3분만 더 걸려요." */
    val icMissed = scenario("ic_missed", "IC로 나가지 못하고 지나침") {
        approach("i1", ManeuverKind.IC, Direction.RIGHT, fromM = 1_200, toM = 150)
        reroute()
        next("i2", ManeuverKind.IC, Direction.RIGHT, remainingM = 3_800, addEtaSec = 180)
        drive(10_000)
    }

    /** 재탐색 이벤트가 없는 내비: 다음 안내의 ETA가 크게 늘어난 것으로 놓침을 추정. */
    val icMissedWithoutRerouteEvent = scenario("ic_missed_no_event", "재탐색 이벤트 없이 ETA 증가로 놓침 추정") {
        approach("i1", ManeuverKind.IC, Direction.RIGHT, fromM = 1_200, toM = 150)
        next("i2", ManeuverKind.IC, Direction.RIGHT, remainingM = 3_800, addEtaSec = 90)
        drive(10_000)
    }

    /** 정체 구간의 저속 제동: 급제동으로 보지 않는다. */
    val congestionBraking = scenario("congestion_braking", "시속 25km에서 정지까지 제동") {
        speedKmh = 25.0
        drive(2_000)
        brakeTo(0.0)
        drive(2_000)
    }

    /** 회전교차로: 깜빡이 조건 없이 통과 여부만 본다. */
    val roundabout = scenario("roundabout", "회전교차로 2시 방향 출구") {
        speedKmh = 30.0
        approach("r1", ManeuverKind.ROUNDABOUT, Direction.NONE, fromM = 1_100, roundaboutClock = 2)
        next("t2", ManeuverKind.TURN, Direction.RIGHT, remainingM = 900)
        drive(15_000)
    }

    val all = listOf(
        mergeSignalEarly, mergeSignalAfterReminder, mergeNoSignal, mergeThenHarshBrake,
        icMissed, icMissedWithoutRerouteEvent, congestionBraking, roundabout,
    )
}
