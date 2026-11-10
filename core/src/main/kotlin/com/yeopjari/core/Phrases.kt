package com.yeopjari.core

import kotlin.math.ceil

/**
 * 차가 하는 말. 원칙: 한 문장, 방향과 시점만, 차가 확인한 사실만.
 * 도로 상황 판단("틈이 충분해요")이나 운전 지시("속도 맞춰요")는 넣지 않는다.
 */
object Phrases {
    const val DISCLAIMER =
        "옆자리 친구는 도로를 보지 못하는 보조 안내입니다. 판단과 운전 책임은 운전자에게 있습니다."

    private fun side(d: Direction) = when (d) {
        Direction.LEFT -> "왼쪽 "
        Direction.RIGHT -> "오른쪽 "
        Direction.NONE -> ""
    }

    /** Pleos TBT 는 회전교차로 출구를 시계 방향(2시, 3시 …)으로 준다. */
    private fun exit(clock: Int?) = if (clock != null && clock in 1..12) " ${clock}시 방향으로 나가요." else ""

    fun pre1km(type: HardSpotType, d: Direction, roundaboutClock: Int? = null): String = when (type) {
        HardSpotType.MERGE -> "곧 ${side(d)}합류예요."
        HardSpotType.IC -> "곧 ${side(d)}IC 방향이에요."
        HardSpotType.SPLIT -> "곧 ${side(d)}갈림길이에요."
        HardSpotType.ROUNDABOUT -> "곧 회전교차로예요.${exit(roundaboutClock)}"
    }

    /** 400m 안내. 합류·IC는 깜빡이가 꺼져 있을 때만 쓰고, 꺼져 있다는 사실만 말한다. */
    fun pre400m(type: HardSpotType, d: Direction, roundaboutClock: Int? = null): String = when (type) {
        HardSpotType.MERGE -> "400미터 뒤 ${side(d)}합류예요. 깜빡이는 아직 꺼져 있어요."
        HardSpotType.IC -> "400미터 뒤 ${side(d)}IC 방향이에요. 깜빡이는 아직 꺼져 있어요."
        HardSpotType.SPLIT -> "400미터 뒤 ${side(d)}길로 갈라져요."
        HardSpotType.ROUNDABOUT -> "400미터 뒤 회전교차로예요.${exit(roundaboutClock)}"
    }

    /** 깜빡이를 읽을 수 없는 차에서 쓰는 400m 안내. 확인할 수 없는 사실은 말하지 않는다. */
    fun pre400mWithoutSignal(type: HardSpotType, d: Direction): String = when (type) {
        HardSpotType.MERGE -> "400미터 뒤 ${side(d)}합류예요."
        HardSpotType.IC -> "400미터 뒤 ${side(d)}IC 방향이에요."
        else -> pre400m(type, d)
    }

    fun afterPass(type: HardSpotType, signalConfirmed: Boolean): String {
        val base = when (type) {
            HardSpotType.MERGE -> "합류 구간 지나왔어요."
            HardSpotType.IC -> "IC 구간 지나왔어요."
            HardSpotType.SPLIT -> "갈림길 지나왔어요."
            HardSpotType.ROUNDABOUT -> "회전교차로 지나왔어요."
        }
        return if (signalConfirmed) "$base 깜빡이도 미리 켰어요." else base
    }

    /** 재탐색 멘트. [addedSec] 는 새 ETA − 이전 ETA. 모르면 null. */
    fun reroute(addedSec: Long?): String = when {
        addedSec == null -> "괜찮아요, 다시 안내할게요."
        addedSec < 60 -> "괜찮아요, 금방이에요."
        else -> "괜찮아요, ${ceil(addedSec / 60.0).toInt()}분만 더 걸려요."
    }

    fun caution(kind: CautionKind): String? = when (kind) {
        CautionKind.SHARP_CURVE -> "곧 급커브예요."
        CautionKind.NARROW_ROAD -> "곧 좁은 길이에요."
        CautionKind.OTHER -> null
    }

    fun typeName(type: HardSpotType): String = when (type) {
        HardSpotType.MERGE -> "합류 구간"
        HardSpotType.IC -> "IC 구간"
        HardSpotType.SPLIT -> "갈림길"
        HardSpotType.ROUNDABOUT -> "회전교차로"
    }
}
