package com.yeopjari.core

/** 운전자 기준 방향. */
enum class Direction { LEFT, RIGHT, NONE }

/** 방향지시등 상태. */
enum class TurnSignal { OFF, LEFT, RIGHT }

enum class Gear { P, R, N, D }

/**
 * 차량 신호 한 시점. 차종이나 권한 때문에 읽을 수 없는 값은 null 이다.
 * null 은 "꺼짐"이 아니라 "모름"으로 다룬다.
 */
data class VehicleSample(
    val timeMs: Long,
    val speedKmh: Double,
    val gear: Gear,
    val signal: TurnSignal? = null,
    val brakePressed: Boolean? = null,
    val steeringDeg: Double? = null,
)

/** 내비 회전 안내(TBT)의 종류. Pleos NaviHelper TurnType 을 어댑터에서 이 값으로 바꾼다. */
enum class ManeuverKind { MERGE, IC, SPLIT, ROUNDABOUT, TURN, STRAIGHT, DESTINATION, OTHER }

/**
 * 다음 회전 안내 갱신.
 * [maneuverId] 는 같은 지점이면 같은 값이어야 한다(어댑터가 만든다).
 */
data class TbtUpdate(
    val timeMs: Long,
    val maneuverId: String,
    val kind: ManeuverKind,
    val direction: Direction,
    val remainingM: Int,
    val etaEpochSec: Long? = null,
    val roundaboutExit: Int? = null,
)

/** 내비가 재탐색을 알려줄 때만 들어온다. 없으면 엔진이 TBT/ETA 변화로 추정한다. */
data class RerouteEvent(val timeMs: Long)

enum class CautionKind { SHARP_CURVE, NARROW_ROAD, OTHER }

data class CautionUpdate(
    val timeMs: Long,
    val cautionId: String,
    val kind: CautionKind,
    val remainingM: Int,
)
