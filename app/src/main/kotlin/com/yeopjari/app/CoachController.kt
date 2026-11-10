package com.yeopjari.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import com.yeopjari.core.CoachEngine
import com.yeopjari.core.Direction
import com.yeopjari.core.FadingState
import com.yeopjari.core.Gear
import com.yeopjari.core.HardSpotType
import com.yeopjari.core.Phrases
import com.yeopjari.core.PracticeLadder
import com.yeopjari.core.RerouteEvent
import com.yeopjari.core.SpotOutcome
import com.yeopjari.core.TbtUpdate
import com.yeopjari.core.TurnSignal
import com.yeopjari.core.Utterance
import com.yeopjari.core.VehicleSample
import com.yeopjari.core.sim.RouteSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 시연 패널이 넣는 신호. null 이면 실제 차량(Virtual Console) 값을 쓴다. */
enum class SignalSource { VEHICLE, PANEL }

data class UiState(
    /** 시뮬레이션 경로로 시연 중이면 true. 화면에 항상 'SIMULATION' 을 띄운다. */
    val simulation: Boolean = false,
    val speedKmh: Double? = null,
    val gear: Gear? = null,
    val signal: TurnSignal? = null,
    val signalSource: SignalSource = SignalSource.VEHICLE,
    val brakePressed: Boolean? = null,
    val steeringDeg: Double? = null,
    val nextSpot: String? = null,
    val nextSpotDistanceM: Int? = null,
    val spoken: List<Utterance> = emptyList(),
    val outcomes: List<SpotOutcome> = emptyList(),
    val summary: String = "",
    val ladderStage: Int = 1,
    val ladderLabel: String = "",
    val ladderGoal: String? = null,
    val fadingEnabled: Boolean = true,
    val naviConnected: Boolean = false,
    val ttsEngine: String = "",
    val disclaimerAccepted: Boolean = false,
)

/**
 * 앱의 중심. 실제 차량·내비 신호(또는 시연 경로)를 0.25초마다 판정 엔진에 넣고,
 * 엔진이 낸 말을 TTS 로 읽고 화면 상태를 갱신한다. 엔진 호출은 모두 메인 스레드에서만 한다.
 */
class CoachController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val ladder = PracticeLadder()
    private val fading = FadingState()

    /** 누적 확인 통과 횟수(연습 사다리용). 주행이 끝날 때 더한다. */
    private val ladderTotals = mutableMapOf<HardSpotType, Int>()

    private var engine = CoachEngine(fading = fading)
    private val speaker = Speaker(context)
    private val vehicle = VehicleInputs(context) { scope.launch { refreshVehicle() } }
    private val navi = NaviInputs(context) { event -> scope.launch { onNavi(event) } }

    private var simulator: RouteSimulator? = null
    private var panelSignal: TurnSignal? = null
    private var brakeUntilMs: Long = 0
    private var loop: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun start() {
        vehicle.start()
        navi.start()
        loop = scope.launch {
            while (isActive) {
                step(SystemClock.elapsedRealtime())
                delay(TICK_MS)
            }
        }
    }

    fun acceptDisclaimer() = _state.update { it.copy(disclaimerAccepted = true) }

    /** 시연: 시뮬레이션 경로(오른쪽 합류 → 좌회전 → 오른쪽 IC → 도착)를 시속 80km 로 달린다. */
    fun startSimulation() {
        val now = SystemClock.elapsedRealtime()
        newTrip()
        simulator = RouteSimulator(RouteSimulator.demoLegs(), startMs = now, etaEpochSec = now / 1000 + 600)
        _state.update { it.copy(simulation = true) }
    }

    fun stopSimulation() {
        simulator = null
        finishTrip()
        _state.update { it.copy(simulation = false) }
    }

    /** 패널에서 깜빡이를 넣는다. null 이면 Virtual Console 의 실제 값을 따른다. */
    fun setPanelSignal(signal: TurnSignal?) {
        panelSignal = signal
    }

    /** 패널에서 급제동을 1초 동안 흉내 낸다(시연용). */
    fun simulateHarshBrake() {
        brakeUntilMs = SystemClock.elapsedRealtime() + 1_000
    }

    fun setFading(enabled: Boolean) {
        fading.enabled = enabled
        _state.update { it.copy(fadingEnabled = enabled) }
    }

    fun resetFading() = fading.reset()

    /** 주행을 마치고 보류 중인 판정을 확정한다. 요약과 연습 사다리를 갱신한다. 여러 번 눌러도 한 번만 센다. */
    fun finishTrip() {
        if (tripCounted) return
        val now = SystemClock.elapsedRealtime()
        engine.finishTrip(now)
        engine.log.confirmedPassTotals().forEach { (type, n) -> ladderTotals[type] = (ladderTotals[type] ?: 0) + n }
        tripCounted = true
        publish(emptyList())
    }

    private var tripCounted = false

    private fun ladderTotalsNow(): Map<HardSpotType, Int> = HardSpotType.entries.associateWith {
        (ladderTotals[it] ?: 0) + if (tripCounted) 0 else engine.log.count(it, com.yeopjari.core.SpotResult.PASSED)
    }

    private fun newTrip() {
        if (!tripCounted && engine.log.outcomes.isNotEmpty()) finishTrip()
        engine = CoachEngine(fading = fading)
        tripCounted = false
        panelSignal = null
        _state.update { it.copy(spoken = emptyList(), outcomes = emptyList(), summary = "") }
    }

    private fun refreshVehicle() {
        val live = vehicle.latest
        _state.update {
            it.copy(
                signal = panelSignal ?: live.signal,
                signalSource = if (panelSignal != null) SignalSource.PANEL else SignalSource.VEHICLE,
                brakePressed = live.brakePressed,
                steeringDeg = live.steeringDeg,
            )
        }
    }

    private fun onNavi(event: NaviEvent) {
        if (simulator != null) return // 시연 중에는 시뮬레이션 경로만 쓴다.
        val said = when (event) {
            is NaviEvent.Tbt -> {
                showNext(event.update)
                engine.onTbt(event.update)
            }
            is NaviEvent.Caution -> engine.onCaution(event.update)
            is NaviEvent.Rerouted -> engine.onReroute(RerouteEvent(event.timeMs))
            is NaviEvent.GuidanceEnded -> engine.onGuidanceEnded(event.timeMs)
        }
        publish(said)
    }

    private fun step(now: Long) {
        val sample = currentSample(now)
        val said = mutableListOf<Utterance>()
        said += engine.onNavVoice(now, navigationVoiceActive(now))
        said += engine.onVehicle(sample)
        simulator?.let { sim ->
            sim.tick(now, sample.speedKmh)?.let { update ->
                showNext(update)
                said += engine.onTbt(update)
            }
            if (sim.finished) {
                simulator = null
                said += engine.onGuidanceEnded(now)
            }
        }
        said += engine.tick(now)
        publish(said)
    }

    private fun currentSample(now: Long): VehicleSample {
        val live = vehicle.latest
        val simulating = simulator != null
        val braking = now < brakeUntilMs
        val speed = when {
            simulating && braking -> SIM_SPEED_KMH - (SIM_SPEED_KMH - 50.0) * (1.0 - (brakeUntilMs - now) / 1_000.0)
            simulating -> SIM_SPEED_KMH
            else -> live.speedKmh ?: 0.0
        }
        return VehicleSample(
            timeMs = now,
            speedKmh = speed,
            gear = if (simulating) Gear.D else live.gear ?: Gear.P,
            signal = panelSignal ?: live.signal,
            brakePressed = if (braking) true else live.brakePressed ?: if (simulating) false else null,
            steeringDeg = live.steeringDeg,
        )
    }

    /** 다른 앱(내비)이 안내 음성을 내는 중인지. 우리 앱이 방금 말한 경우는 뺀다. */
    private fun navigationVoiceActive(now: Long): Boolean {
        if (now - speaker.lastSpokeAtMs < OWN_SPEECH_MS) return false
        return runCatching {
            audio.activePlaybackConfigurations.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            }
        }.getOrDefault(false)
    }

    private fun showNext(update: TbtUpdate) {
        val type = HardSpotType.of(update.kind)
        _state.update {
            it.copy(
                nextSpot = type?.let { t -> sideWord(update.direction) + Phrases.typeName(t) },
                nextSpotDistanceM = if (type != null) update.remainingM else null,
            )
        }
    }

    private fun sideWord(d: Direction) = when (d) {
        Direction.LEFT -> "왼쪽 "
        Direction.RIGHT -> "오른쪽 "
        Direction.NONE -> ""
    }

    private fun publish(said: List<Utterance>) {
        said.forEach { speaker.speak(it.text) }
        val totals = ladderTotalsNow()
        val stage = ladder.stage(totals)
        val live = vehicle.latest
        _state.update {
            it.copy(
                spoken = if (said.isEmpty()) it.spoken else (it.spoken + said).takeLast(MAX_LOG),
                outcomes = engine.log.outcomes.toList(),
                summary = engine.log.summary(),
                ladderStage = stage,
                ladderLabel = ladder.label(stage),
                ladderGoal = ladder.nextGoal(totals),
                speedKmh = if (simulator != null) SIM_SPEED_KMH else live.speedKmh,
                gear = if (simulator != null) Gear.D else live.gear,
                signal = panelSignal ?: live.signal,
                signalSource = if (panelSignal != null) SignalSource.PANEL else SignalSource.VEHICLE,
                brakePressed = live.brakePressed,
                steeringDeg = live.steeringDeg,
                naviConnected = navi.connected,
                ttsEngine = speaker.engineName,
            )
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val SIM_SPEED_KMH = 80.0
        private const val OWN_SPEECH_MS = 4_000L
        private const val MAX_LOG = 30
    }
}
