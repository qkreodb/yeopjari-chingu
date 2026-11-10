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
import com.yeopjari.core.SpotOutcome
import com.yeopjari.core.SpotResult
import com.yeopjari.core.TbtUpdate
import com.yeopjari.core.TurnSignal
import com.yeopjari.core.Utterance
import com.yeopjari.core.VehicleSample
import com.yeopjari.core.sim.RouteSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 깜빡이 값의 출처. 패널은 시연 때 직접 넣는 값, 차량은 실제 신호(에뮬레이터에서는 Virtual Console). */
enum class SignalSource { VEHICLE, PANEL }

data class UiState(
    /** 시뮬레이션 경로로 시연 중이면 true. 화면에 항상 'SIMULATION' 을 띄운다. */
    val simulation: Boolean = false,
    val speedKmh: Double? = null,
    val gear: Gear? = null,
    val signal: TurnSignal? = null,
    val signalSource: SignalSource = SignalSource.VEHICLE,
    val nextSpot: String? = null,
    val nextSpotDistanceM: Int? = null,
    val spoken: List<Utterance> = emptyList(),
    /** 진행 중인 주행의 판정. 주행을 마치면 [lastTripOutcomes] 로 옮긴다. */
    val outcomes: List<SpotOutcome> = emptyList(),
    val summary: String = "",
    val lastTripSummary: String? = null,
    val lastTripOutcomes: List<SpotOutcome> = emptyList(),
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
 * 안전 고지에 동의하기 전에는 엔진을 돌리지 않는다(아무 말도 하지 않는다).
 */
class CoachController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("yeopjari", Context.MODE_PRIVATE)
    private val ladder = PracticeLadder()
    private val fading = FadingState()

    /** 마친 주행들의 누적 확인 통과 횟수(연습 사다리용). */
    private val ladderTotals = mutableMapOf<HardSpotType, Int>()

    private var engine = CoachEngine(fading = fading)
    private val speaker = Speaker(context)
    private val vehicle = VehicleInputs(context) { scope.launch { publishVehicle() } }
    private val navi = NaviInputs(context) { event -> scope.launch { onNavi(event) } }

    private var simulator: RouteSimulator? = null
    private var panelSignal: TurnSignal? = null
    private var brakeUntilMs: Long = 0

    private val _state = MutableStateFlow(UiState(disclaimerAccepted = prefs.getBoolean(KEY_DISCLAIMER, false)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val accepted: Boolean get() = _state.value.disclaimerAccepted

    fun start() {
        vehicle.start()
        navi.start()
        scope.launch {
            while (isActive) {
                if (accepted) step(SystemClock.elapsedRealtime())
                delay(TICK_MS)
            }
        }
    }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply()
        _state.update { it.copy(disclaimerAccepted = true) }
    }

    /** 차속 권한을 새로 받았을 때 차량 신호 구독을 다시 시작한다. */
    fun onPermissionsChanged() {
        vehicle.restartCarProperties()
    }

    /** 시연: 시뮬레이션 경로(오른쪽 합류 → 좌회전 → 오른쪽 IC → 도착)를 시속 80km 로 달린다. */
    fun startSimulation() {
        val now = SystemClock.elapsedRealtime()
        finishTrip()
        engine = CoachEngine(fading = fading)
        simulator = RouteSimulator(RouteSimulator.demoLegs(), startMs = now, etaEpochSec = now / 1000 + 600)
        panelSignal = null
        _state.update { it.copy(simulation = true) }
    }

    fun stopSimulation() {
        simulator = null
        finishTrip()
        _state.update { it.copy(simulation = false, nextSpot = null, nextSpotDistanceM = null) }
    }

    /** 패널에서 깜빡이를 넣는다. null 이면 차량(Virtual Console)의 실제 값을 따른다. */
    fun setPanelSignal(signal: TurnSignal?) {
        panelSignal = signal
        publishVehicle()
    }

    /** 패널에서 급제동을 1초 동안 흉내 낸다(시연 경로에서만). */
    fun simulateHarshBrake() {
        if (simulator != null) brakeUntilMs = SystemClock.elapsedRealtime() + 1_000
    }

    fun setFading(enabled: Boolean) {
        fading.enabled = enabled
        _state.update { it.copy(fadingEnabled = enabled) }
    }

    fun resetFading() = fading.reset()

    /**
     * 주행을 마친다: 보류 중인 판정을 확정하고, 요약을 '지난 주행'으로 옮기고, 새 주행을 시작한다.
     * 판정이 하나도 없으면 아무것도 하지 않는다.
     */
    fun finishTrip() {
        val now = SystemClock.elapsedRealtime()
        engine.finishTrip(now)
        val log = engine.log
        if (log.outcomes.isEmpty()) return
        log.confirmedPassTotals().forEach { (type, n) -> ladderTotals[type] = (ladderTotals[type] ?: 0) + n }
        val summary = log.summary()
        val outcomes = log.outcomes.toList()
        engine = CoachEngine(fading = fading)
        _state.update { it.copy(lastTripSummary = summary, lastTripOutcomes = outcomes, spoken = emptyList()) }
        publish(emptyList())
    }

    /** 마지막 지점의 통과 후 급제동 창(10초)이 지난 뒤 주행을 마친다. 그 사이 새 주행이 시작됐으면 건드리지 않는다. */
    private fun finishTripLater() {
        val ended = engine
        scope.launch {
            delay(12_000)
            if (engine === ended) finishTrip()
        }
    }

    private fun onNavi(event: NaviEvent) {
        if (!accepted || simulator != null) return // 시연 중에는 시뮬레이션 경로만 쓴다.
        val said = when (event) {
            is NaviEvent.Tbt -> {
                showNext(event.update)
                engine.onTbt(event.update)
            }
            is NaviEvent.Caution -> engine.onCaution(event.update)
            is NaviEvent.Rerouted -> engine.onRerouteCompleted(event.timeMs)
            is NaviEvent.GuidanceEnded -> engine.onGuidanceEnded(event.timeMs)
        }
        publish(said)
        if (event is NaviEvent.GuidanceEnded) finishTripLater()
    }

    private fun step(now: Long) {
        val sample = currentSample(now)
        val said = mutableListOf<Utterance>()
        said += engine.onNavVoice(now, navigationVoiceActive())
        said += engine.onVehicle(sample)
        simulator?.let { sim ->
            sim.tick(now, sample.speedKmh ?: 0.0)?.let { update ->
                showNext(update)
                said += engine.onTbt(update)
            }
            if (sim.finished) {
                said += engine.onGuidanceEnded(now)
                simulator = null
                _state.update { it.copy(simulation = false, nextSpot = null, nextSpotDistanceM = null) }
                finishTripLater()
            }
        }
        said += engine.tick(now)
        publish(said)
    }

    private fun currentSample(now: Long): VehicleSample {
        val live = vehicle.latest
        val simulating = simulator != null
        val braking = simulating && now < brakeUntilMs
        val speed = when {
            braking -> SIM_SPEED_KMH - (SIM_SPEED_KMH - 50.0) * (1.0 - (brakeUntilMs - now) / 1_000.0)
            simulating -> SIM_SPEED_KMH
            else -> live.speedKmh // 읽을 수 없으면 null: 정지로 보지 않는다.
        }
        return VehicleSample(
            timeMs = now,
            speedKmh = speed,
            gear = if (simulating) Gear.D else live.gear, // 읽을 수 없으면 null: P단으로 보지 않는다.
            signal = panelSignal ?: live.signal,
            brakePressed = when {
                braking -> true
                live.brakePressed != null -> live.brakePressed
                simulating -> false
                else -> null
            },
            steeringDeg = live.steeringDeg,
        )
    }

    /**
     * 다른 앱(내비)이 안내 음성을 내는 중인지. 우리 TTS 도 같은 용도로 소리를 내므로,
     * 우리가 말하는 중이면 내비 안내 재생이 하나 더 있을 때만 내비가 말하는 것으로 본다.
     */
    private fun navigationVoiceActive(): Boolean = runCatching {
        val guidance = audio.activePlaybackConfigurations.count {
            it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
        guidance > if (speaker.speaking) 1 else 0
    }.getOrDefault(false)

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

    /** 화면에 보이는 차량 값만 갱신한다(조향각·브레이크처럼 자주 바뀌고 화면에 없는 값은 넣지 않는다). */
    private fun publishVehicle() {
        val live = vehicle.latest
        val simulating = simulator != null
        val signal = panelSignal ?: live.signal
        val source = if (panelSignal != null) SignalSource.PANEL else SignalSource.VEHICLE
        val speed = if (simulating) SIM_SPEED_KMH else live.speedKmh?.let { kotlin.math.round(it) }
        val gear = if (simulating) Gear.D else live.gear
        val s = _state.value
        if (s.signal != signal || s.signalSource != source || s.speedKmh != speed || s.gear != gear) {
            _state.update { it.copy(signal = signal, signalSource = source, speedKmh = speed, gear = gear) }
        }
    }

    private fun publish(said: List<Utterance>) {
        said.forEach { speaker.speak(it.text) }
        val totals = HardSpotType.entries.associateWith {
            (ladderTotals[it] ?: 0) + engine.log.count(it, SpotResult.PASSED)
        }
        val stage = ladder.stage(totals)
        val outcomes = engine.log.outcomes
        val s = _state.value
        if (said.isNotEmpty() || s.outcomes.size != outcomes.size || s.ttsEngine != speaker.engineName ||
            s.naviConnected != navi.connected || s.ladderStage != stage || s.ladderGoal != ladder.nextGoal(totals)
        ) {
            _state.update {
                it.copy(
                    spoken = if (said.isEmpty()) it.spoken else (it.spoken + said).takeLast(MAX_LOG),
                    outcomes = outcomes.toList(),
                    summary = if (outcomes.isEmpty()) "" else engine.log.summary(),
                    ladderStage = stage,
                    ladderLabel = ladder.label(stage),
                    ladderGoal = ladder.nextGoal(totals),
                    naviConnected = navi.connected,
                    ttsEngine = speaker.engineName,
                )
            }
        }
        publishVehicle()
    }

    companion object {
        private const val TICK_MS = 250L
        private const val SIM_SPEED_KMH = 80.0
        private const val MAX_LOG = 30
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
    }
}
