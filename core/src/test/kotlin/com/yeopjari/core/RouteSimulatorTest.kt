package com.yeopjari.core

import com.yeopjari.core.sim.RouteSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteSimulatorTest {
    @Test
    fun `시뮬레이션 경로와 실제 깜빡이 신호로 합류 판정이 돈다`() {
        val sim = RouteSimulator(RouteSimulator.demoLegs(), startMs = 0, etaEpochSec = 10_000)
        val engine = CoachEngine()
        val said = mutableListOf<String>()
        var t = 0L
        while (!sim.finished && t < 400_000) {
            t += 250
            val update = sim.tick(t, 80.0)
            val signal = if (sim.currentLeg?.kind == ManeuverKind.MERGE && sim.remaining < 500) TurnSignal.RIGHT else TurnSignal.OFF
            said += engine.onVehicle(VehicleSample(t, 80.0, Gear.D, signal, false, 0.0)).map { it.text }
            if (update != null) said += engine.onTbt(update).map { it.text }
        }
        for (k in 1..60) said += engine.tick(t + k * 250L).map { it.text }
        assertTrue("합류 구간 지나왔어요. 깜빡이도 미리 켰어요." in said, "$said")
        assertTrue("400미터 뒤 오른쪽 IC 방향이에요. 깜빡이는 아직 꺼져 있어요." in said, "$said")
        assertEquals(SpotResult.PASSED, engine.log.outcomes.first().result)
    }
}
