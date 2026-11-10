package com.yeopjari.core.sim

import com.yeopjari.core.CautionUpdate
import com.yeopjari.core.Direction
import com.yeopjari.core.Gear
import com.yeopjari.core.ManeuverKind
import com.yeopjari.core.RerouteEvent
import com.yeopjari.core.TbtUpdate
import com.yeopjari.core.TurnSignal
import com.yeopjari.core.VehicleSample

/** 시뮬레이션·신호 주입에 쓰는 입력 이벤트. 실차 어댑터도 같은 이벤트를 만든다. */
sealed interface ScenarioEvent {
    val timeMs: Long

    data class Vehicle(val sample: VehicleSample) : ScenarioEvent {
        override val timeMs get() = sample.timeMs
    }

    data class Tbt(val update: TbtUpdate) : ScenarioEvent {
        override val timeMs get() = update.timeMs
    }

    data class Reroute(val event: RerouteEvent) : ScenarioEvent {
        override val timeMs get() = event.timeMs
    }

    data class Caution(val update: CautionUpdate) : ScenarioEvent {
        override val timeMs get() = update.timeMs
    }

    data class NavVoice(override val timeMs: Long, val speaking: Boolean) : ScenarioEvent
}

data class Scenario(val name: String, val description: String, val events: List<ScenarioEvent>)

/**
 * 주행 장면을 코드로 쓰는 빌더. 차량 상태(속도, 깜빡이, 브레이크, 조향)를 바꾸고
 * [approach] 로 어려운 지점에 다가가면 TBT와 차량 샘플이 함께 만들어진다.
 */
class ScenarioBuilder(
    private val stepMs: Long = 500,
    startEtaEpochSec: Long = 100_000,
) {
    private val events = mutableListOf<ScenarioEvent>()

    var timeMs: Long = 0
        private set
    var speedKmh: Double = 60.0
    var signal: TurnSignal? = TurnSignal.OFF
    var brakePressed: Boolean? = false
    var steeringDeg: Double? = 0.0
    var gear: Gear = Gear.D

    /** 도착 예정 시각(절대값). 재탐색하면 늘어난다. */
    var etaEpochSec: Long = startEtaEpochSec
        private set

    private fun sample() {
        events += ScenarioEvent.Vehicle(VehicleSample(timeMs, speedKmh, gear, signal, brakePressed, steeringDeg))
    }

    /** 현재 상태로 [durationMs] 동안 달린다. */
    fun drive(durationMs: Long) {
        val end = timeMs + durationMs
        while (timeMs < end) {
            timeMs += stepMs
            sample()
        }
    }

    /**
     * [fromM] 부터 [toM] 까지 현재 속도로 다가간다. [onDistance] 는 남은 거리별로 차량 상태를 바꿀 때 쓴다.
     */
    fun approach(
        id: String,
        kind: ManeuverKind,
        direction: Direction,
        fromM: Int,
        toM: Int = 0,
        roundaboutExit: Int? = null,
        onDistance: ScenarioBuilder.(remainingM: Int) -> Unit = {},
    ) {
        var remaining = fromM.toDouble()
        while (true) {
            val r = remaining.toInt().coerceAtLeast(toM)
            onDistance(r)
            events += ScenarioEvent.Tbt(TbtUpdate(timeMs, id, kind, direction, r, etaEpochSec, roundaboutExit))
            sample()
            if (r <= toM) break
            timeMs += stepMs
            remaining -= speedKmh / 3.6 * stepMs / 1000.0
        }
    }

    /** 다음 안내로 넘어간다(지점 통과 또는 재탐색 후 새 경로). */
    fun next(id: String, kind: ManeuverKind, direction: Direction, remainingM: Int, addEtaSec: Long = 0) {
        timeMs += stepMs
        etaEpochSec += addEtaSec
        events += ScenarioEvent.Tbt(TbtUpdate(timeMs, id, kind, direction, remainingM, etaEpochSec))
        sample()
    }

    fun reroute() {
        timeMs += stepMs
        events += ScenarioEvent.Reroute(RerouteEvent(timeMs))
    }

    /** 1초 동안 [toKmh] 까지 브레이크를 밟는다. */
    fun brakeTo(toKmh: Double, durationMs: Long = 1_000) {
        val start = speedKmh
        val steps = (durationMs / stepMs).toInt().coerceAtLeast(1)
        brakePressed = true
        for (i in 1..steps) {
            timeMs += stepMs
            speedKmh = start + (toKmh - start) * i / steps
            sample()
        }
        brakePressed = false
    }

    fun navVoice(speaking: Boolean) {
        events += ScenarioEvent.NavVoice(timeMs, speaking)
    }

    fun build(name: String, description: String) = Scenario(name, description, events.toList())
}

fun scenario(name: String, description: String, block: ScenarioBuilder.() -> Unit): Scenario =
    ScenarioBuilder().apply(block).build(name, description)
