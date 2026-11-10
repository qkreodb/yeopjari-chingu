package com.yeopjari.core

/**
 * Pleos NaviHelper 의 회전 안내(TurnType 이름, 거리, 설명)를 엔진 입력([TbtUpdate])으로 바꾼다.
 * Pleos 타입에 의존하지 않도록 TurnType 은 이름 문자열로 받는다(예: "TURN_ENTER_IC_R").
 *
 * Pleos TBT 에는 지점 ID가 없으므로 여기서 만든다.
 * - 안내 종류가 바뀌거나 거리가 [newSpotJumpM] 이상 다시 늘면 새 지점
 *   (TBT 갱신 간격과 무관하게, 지나간 지점 다음의 같은 종류 지점을 새 지점으로 본다)
 * - 지점을 놓친 것인지는 엔진이 판단한다: 도달 거리 추정, ETA 증가, Pleos 재탐색 이벤트(onReRouteEnded)
 */
class TbtAdapter(
    private val newSpotJumpM: Double = 300.0,
) {
    data class Mapped(val kind: ManeuverKind, val direction: Direction, val roundaboutClock: Int? = null)

    private var seq = 0
    private var lastType: String? = null
    private var lastDistanceM: Double? = null

    fun toUpdate(timeMs: Long, turnType: String, distanceM: Double, description: String?, etaEpochSec: Long?): TbtUpdate {
        val previousDistance = lastDistanceM
        val isNew = lastType != turnType ||
            (previousDistance != null && distanceM > previousDistance + newSpotJumpM)
        if (isNew) seq++
        lastType = turnType
        lastDistanceM = distanceM
        val mapped = map(turnType, description)
        return TbtUpdate(
            timeMs = timeMs,
            maneuverId = "tbt-$seq",
            kind = mapped.kind,
            direction = mapped.direction,
            remainingM = distanceM.toInt().coerceAtLeast(0),
            etaEpochSec = etaEpochSec,
            roundaboutClock = mapped.roundaboutClock,
        )
    }

    /** 재탐색이 끝나면 다음 안내를 새 지점으로 시작한다. */
    fun reset() {
        lastType = null
        lastDistanceM = null
    }

    companion object {
        fun map(turnType: String, description: String? = null): Mapped {
            val t = turnType.removePrefix("TURN_")
            return when {
                t == "DEVIDE_MERGE" || t == "DIVIDE_MERGE" -> Mapped(ManeuverKind.MERGE, directionFrom(description))
                t.startsWith("ENTER_IC_") || t.startsWith("EXIT_IC_") -> Mapped(ManeuverKind.IC, suffixDirection(t))
                t == "KEEP_L" -> Mapped(ManeuverKind.SPLIT, Direction.LEFT)
                t == "KEEP_R" -> Mapped(ManeuverKind.SPLIT, Direction.RIGHT)
                t.startsWith("ENTER_ROUNDABOUT_") ->
                    Mapped(ManeuverKind.ROUNDABOUT, Direction.NONE, t.removePrefix("ENTER_ROUNDABOUT_").toIntOrNull())
                t == "ARRIVE" -> Mapped(ManeuverKind.DESTINATION, Direction.NONE)
                t == "STRAIGHT" -> Mapped(ManeuverKind.STRAIGHT, Direction.NONE)
                t == "LEFT" || t == "SHARP_L" || t == "UTURN" -> Mapped(ManeuverKind.TURN, Direction.LEFT)
                t == "RIGHT" || t == "SHARP_R" -> Mapped(ManeuverKind.TURN, Direction.RIGHT)
                else -> Mapped(ManeuverKind.OTHER, Direction.NONE)
            }
        }

        private fun suffixDirection(t: String): Direction = when {
            t.endsWith("_L") -> Direction.LEFT
            t.endsWith("_R") -> Direction.RIGHT
            else -> Direction.NONE
        }

        /** 합류 TurnType 에는 방향이 없어 설명 문구에서 찾는다. 찾지 못하면 방향을 모르는 합류로 둔다. */
        fun directionFrom(description: String?): Direction {
            val d = description ?: return Direction.NONE
            val left = listOf("왼쪽", "좌측", "좌로").any { it in d }
            val right = listOf("오른쪽", "우측", "우로").any { it in d }
            return when {
                left && !right -> Direction.LEFT
                right && !left -> Direction.RIGHT
                else -> Direction.NONE
            }
        }
    }
}
