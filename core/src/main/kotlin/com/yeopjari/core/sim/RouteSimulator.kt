package com.yeopjari.core.sim

import com.yeopjari.core.Direction
import com.yeopjari.core.ManeuverKind
import com.yeopjari.core.TbtUpdate

/**
 * 시연용 실시간 경로. 에뮬레이터에는 회전 안내를 넣는 기능이 없으므로, 정해 둔 지점들을
 * 현재 속도로 달리는 것처럼 TBT 를 만들어 준다. 깜빡이·브레이크 같은 차량 신호는 실제
 * Virtual Console 이나 신호 주입 패널에서 들어오므로, 판정은 실제 신호로 이뤄진다.
 */
class RouteSimulator(
    private val legs: List<Leg>,
    startMs: Long,
    private val etaEpochSec: Long? = null,
) {
    data class Leg(
        val id: String,
        val kind: ManeuverKind,
        val direction: Direction,
        val distanceM: Double,
        val roundaboutClock: Int? = null,
        val description: String = "",
    )

    private var index = 0
    private var remainingM = legs.firstOrNull()?.distanceM ?: 0.0
    private var lastMs = startMs

    val finished: Boolean get() = index >= legs.size
    val currentLeg: Leg? get() = legs.getOrNull(index)
    val remaining: Double get() = remainingM

    /** 시간을 진행하고 현재 지점의 TBT 를 낸다. 지점에 닿으면 0m 갱신을 낸 뒤 다음 지점으로 넘어간다. */
    fun tick(nowMs: Long, speedKmh: Double): TbtUpdate? {
        val leg = currentLeg ?: return null
        val dtSec = (nowMs - lastMs).coerceAtLeast(0) / 1000.0
        lastMs = nowMs
        remainingM -= speedKmh.coerceAtLeast(0.0) / 3.6 * dtSec
        if (remainingM <= 0.0) {
            val carry = -remainingM
            index++
            remainingM = (currentLeg?.distanceM ?: 0.0) - carry
            return TbtUpdate(nowMs, leg.id, leg.kind, leg.direction, 0, etaEpochSec, leg.roundaboutClock)
        }
        return TbtUpdate(nowMs, leg.id, leg.kind, leg.direction, remainingM.toInt(), etaEpochSec, leg.roundaboutClock)
    }

    companion object {
        /** 시연 기본 경로: 오른쪽 합류 → 좌회전 → 오른쪽 IC 진출 → 도착. */
        fun demoLegs() = listOf(
            Leg("demo-merge", ManeuverKind.MERGE, Direction.RIGHT, 1_200.0, description = "우측 방향 합류"),
            Leg("demo-turn", ManeuverKind.TURN, Direction.LEFT, 700.0),
            Leg("demo-ic", ManeuverKind.IC, Direction.RIGHT, 1_300.0),
            Leg("demo-arrive", ManeuverKind.DESTINATION, Direction.NONE, 600.0),
        )
    }
}
