package com.yeopjari.core

data class CoachConfig(
    val pre1kmM: Int = 1_000,
    val pre400mM: Int = 400,
    /** 이 거리 안으로 들어오면 지점에 "도달"한 것으로 본다. */
    val nearM: Int = 50,
    /**
     * 깜빡이를 "지점 전에 켰다"고 인정하는 구간. 이 구간 안에서, 도달 전에, 꺼진 상태에서 켜는 것을 봐야 인정한다.
     * 훨씬 앞에서 다른 이유(차로 변경)로 켜 둔 깜빡이를 인정하지 않기 위해서다.
     */
    val signalCreditM: Int = 1_000,
    /** 통과 전후로 급제동을 지켜보는 시간. 통과 후에는 이 시간이 지나야 판정을 확정한다. */
    val brakeWindowAfterPassMs: Long = 10_000,
    val brakeWindowBeforePassMs: Long = 10_000,
    /** 같은 지점의 남은 거리가 이만큼 다시 늘어나면 재탐색으로 본다. */
    val rerouteJumpM: Int = 300,
    /** 남은 거리가 이만큼 늘고 ETA도 [rerouteEtaIncreaseSec] 이상 늘면 재탐색으로 본다. */
    val rerouteJumpWithEtaM: Int = 100,
    /** 지점에 도달하지 않은 채 다음 안내로 넘어갈 때, ETA가 이만큼 늘었으면 놓친 것으로 본다. */
    val rerouteEtaIncreaseSec: Long = 30,
    /** TBT 갱신이 드문 내비에서, 마지막 갱신 뒤 달린 거리를 감안할 때 더하는 여유. */
    val reachSlackM: Int = 30,
    /** 마지막 TBT 뒤 이 시간보다 오래 지나면 거리를 추정하지 않는다(터널, 내비 멈춤). */
    val maxExtrapolationMs: Long = 10_000,
    /** 재탐색 후 새 ETA를 기다리는 최대 시간. 지나면 시간 없이 말한다. */
    val rerouteMessageWaitMs: Long = 8_000,
    /** 주의 지점을 말하는 거리. */
    val cautionM: Int = 300,
    val budget: BudgetConfig = BudgetConfig(),
)

/**
 * 옆자리 친구의 판단 엔진. 차량 신호와 내비 안내를 시간 순서대로 받아
 * 말할 문장과 지점 판정 결과를 낸다. 안드로이드나 Pleos SDK에 의존하지 않으므로
 * 실차 신호든 시뮬레이션이든 같은 코드로 돈다.
 *
 * 가장 중요한 원칙: 확인하지 못한 것은 칭찬하지 않는다. 판정이 애매하면 UNCERTAIN 으로 남기고 침묵한다.
 */
class CoachEngine(
    private val config: CoachConfig = CoachConfig(),
    val fading: FadingState = FadingState(),
) {
    val log = TripLog()

    private val budget = VoiceBudget(config.budget)
    private val brakeDetector = HarshBrakeDetector()

    private var lastSample: VehicleSample? = null
    private var lastHarshBrakeMs: Long? = null
    private var navSpeaking = false

    /** 방향지시등 값을 한 번이라도 읽었으면 true. 끝까지 못 읽으면 깜빡이 조건을 판정하지 않는다. */
    private var signalReadable = false

    private var spotSeq = 0
    private var currentManeuverId: String? = null
    private var current: ActiveSpot? = null
    private var lastEtaSec: Long? = null
    private var pendingReroute: PendingReroute? = null
    private val pendingPasses = mutableListOf<PendingPass>()
    private val announcedCautions = mutableSetOf<String>()

    private class ActiveSpot(
        /** 발화 예산 키. 같은 지점이 재탐색 뒤 다시 안내되면 새 키를 받는다. */
        val key: String,
        val id: String,
        val type: HardSpotType,
        val direction: Direction,
        val roundaboutClock: Int?,
        var lastRemainingM: Int,
        var lastUpdateMs: Long,
        var said1km: Boolean = false,
        var said400m: Boolean = false,
        var reachedAtMs: Long? = null,
        /** 인정 구간 안에서 같은 방향이 아닌(꺼진) 깜빡이를 봤는지. 켜는 순간을 확인하는 데 쓴다. */
        var sawSignalOffInZone: Boolean = false,
        var signalDistanceM: Int? = null,
    ) {
        val reachedNear: Boolean get() = reachedAtMs != null
    }

    private class PendingPass(val spot: ActiveSpot, val passMs: Long, var harshBrake: Boolean)

    private class PendingReroute(val createdMs: Long, val baseEtaSec: Long?, val maneuverIdAtReroute: String?)

    fun onVehicle(sample: VehicleSample): List<Utterance> {
        lastSample = sample
        if (brakeDetector.onSample(sample)) {
            lastHarshBrakeMs = sample.timeMs
            pendingPasses.forEach {
                if (sample.timeMs - it.passMs <= config.brakeWindowAfterPassMs) it.harshBrake = true
            }
        }
        if (sample.signal != null) signalReadable = true
        current?.let { trackSignal(it, sample) }
        if (sample.gear == Gear.P) parked(sample.timeMs)
        return advance(sample.timeMs)
    }

    fun onTbt(update: TbtUpdate): List<Utterance> {
        val etaBefore = lastEtaSec
        update.etaEpochSec?.let { lastEtaSec = it }
        resolvePendingReroute(update)

        if (update.maneuverId != currentManeuverId) {
            current?.let { leave(it, update, etaBefore) }
            currentManeuverId = update.maneuverId
            current = HardSpotType.of(update.kind)?.let { newSpot(update, it) }
        } else {
            val spot = current
            if (spot != null && !spot.reachedNear && isRerouteJump(spot, update, etaBefore)) {
                // 같은 지점인데 남은 거리가 크게 다시 늘었다: 지나쳐서 돌아오는 경로가 잡힌 것.
                missed(spot, update.timeMs, etaBefore, update.etaEpochSec)
                // 돌아오는 경로의 같은 지점은 새 지점으로 처음부터 안내한다.
                current = newSpot(update, spot.type)
            }
        }

        current?.let { approach(it, update) }
        return advance(update.timeMs)
    }

    /** 내비가 재탐색을 알려줄 때. 어려운 지점이든 아니든 차분한 재탐색 멘트를 한다(F5). */
    fun onReroute(event: RerouteEvent): List<Utterance> {
        val spot = current
        when {
            spot == null -> scheduleRerouteMessage(event.timeMs, lastEtaSec, null)
            // 지점에 거의 다 와서 재탐색되면 지났는지 놓쳤는지 알 수 없다. 칭찬도 '놓침'도 하지 않는다.
            spot.reachedNear -> {
                record(spot, event.timeMs, SpotResult.UNCERTAIN)
                scheduleRerouteMessage(event.timeMs, lastEtaSec, null)
            }
            else -> missed(spot, event.timeMs, lastEtaSec, null)
        }
        current = null
        // 재탐색 뒤 같은 지점이 다시 안내되면 새 지점으로 처음부터 안내한다.
        currentManeuverId = null
        return advance(event.timeMs)
    }

    /**
     * 새 경로가 이미 잡힌 뒤에 오는 재탐색 완료 알림(Pleos onReRouteEnded).
     * 이때의 현재 지점은 새 경로의 지점이므로 놓친 것으로 보지 않는다. 떠난 지점은 [leave] 가 이미 판정했다.
     * 최근 10초 안에 재탐색 멘트를 이미 냈다면 다시 말하지 않는다.
     */
    fun onRerouteCompleted(timeMs: Long): List<Utterance> {
        val recentlySaid = lastRerouteMessageMs?.let { timeMs - it <= RECENT_REROUTE_MS } == true
        if (!recentlySaid && pendingReroute == null) {
            enqueueReroute(timeMs, null)
        }
        return advance(timeMs)
    }

    fun onCaution(update: CautionUpdate): List<Utterance> {
        if (update.remainingM <= config.cautionM && announcedCautions.add(update.cautionId)) {
            Phrases.caution(update.kind)?.let {
                budget.enqueue(update.timeMs, "caution:${update.cautionId}", UtteranceKind.CAUTION, it)
            }
        }
        return advance(update.timeMs)
    }

    fun onNavVoice(timeMs: Long, speaking: Boolean): List<Utterance> {
        navSpeaking = speaking
        return advance(timeMs)
    }

    /** 이벤트가 없어도 주기적으로(1초 이하 간격) 불러 판정 확정과 발화를 진행시킨다. */
    fun tick(timeMs: Long): List<Utterance> = advance(timeMs)

    /**
     * 내비 안내가 끝났을 때(목적지 도착). 마지막 지점 뒤에는 다음 안내가 없어 [leave] 가 불리지 않으므로 여기서 정리한다.
     */
    fun onGuidanceEnded(timeMs: Long): List<Utterance> {
        current?.let { spot ->
            if (reached(spot, timeMs)) addPendingPass(spot, timeMs)
            else record(spot, timeMs, SpotResult.UNCERTAIN)
        }
        current = null
        currentManeuverId = null
        return advance(timeMs)
    }

    /**
     * 주행을 마칠 때(앱 종료, 시동 끔). 보류 중인 판정을 지금 확정한다.
     * 차가 서 있으므로 통과 후 급제동 창을 끝까지 기다리지 않아도 된다.
     */
    fun finishTrip(timeMs: Long) {
        onGuidanceEnded(timeMs)
        pendingPasses.forEach { finalizePass(it, timeMs) }
        pendingPasses.clear()
    }

    private fun newSpot(update: TbtUpdate, type: HardSpotType) = ActiveSpot(
        key = "${update.maneuverId}#${spotSeq++}",
        id = update.maneuverId,
        type = type,
        direction = update.direction,
        roundaboutClock = update.roundaboutClock,
        lastRemainingM = update.remainingM,
        lastUpdateMs = update.timeMs,
    )

    /**
     * P단: 지점에 도달한 뒤라면 통과로 넘기고, 아니면 그대로 둔다.
     * 휴게소나 긴 신호에서 잠깐 P에 둔 것을 주행 종료로 보지 않기 위해서다.
     */
    private fun parked(timeMs: Long) {
        val spot = current ?: return
        if (reached(spot, timeMs)) {
            addPendingPass(spot, timeMs)
            current = null
        }
    }

    private fun trackSignal(spot: ActiveSpot, sample: VehicleSample) {
        if (spot.reachedNear || spot.signalDistanceM != null) return
        val distance = estimatedRemaining(spot, sample.timeMs) ?: return
        if (distance <= config.nearM || distance > config.signalCreditM) return
        val on = matches(sample.signal, spot.direction)
        if (!on) {
            spot.sawSignalOffInZone = true
        } else if (spot.sawSignalOffInZone) {
            // 인정 구간 안에서 꺼진 상태를 본 뒤 켜는 것을 확인했다.
            spot.signalDistanceM = distance
        }
    }

    private fun approach(spot: ActiveSpot, update: TbtUpdate) {
        spot.lastRemainingM = update.remainingM
        spot.lastUpdateMs = update.timeMs
        val remaining = update.remainingM
        if (remaining <= config.nearM && spot.reachedAtMs == null) spot.reachedAtMs = update.timeMs
        lastSample?.let { trackSignal(spot, it.copy(timeMs = update.timeMs)) }
        val level = fading.level(spot.type)

        // 1km 구간을 건너뛰고 400m 안으로 바로 들어온 경우 1km 안내는 하지 않는다(400m 안내가 대신한다).
        if (!spot.said1km && remaining <= config.pre1kmM && remaining > config.pre400mM) {
            spot.said1km = true
            if (level.pre1km) {
                budget.enqueue(
                    update.timeMs, spot.key, UtteranceKind.PRE_1KM,
                    Phrases.pre1km(spot.type, spot.direction, spot.roundaboutClock),
                )
            }
        }

        if (!spot.said400m && remaining <= config.pre400mM && remaining > config.nearM) {
            spot.said400m = true
            if (level.pre400m) pre400m(spot, update.timeMs)
        }
    }

    private fun signalCheckable(spot: ActiveSpot): Boolean =
        spot.type.requiresSignal && signalReadable && spot.direction != Direction.NONE

    private fun pre400m(spot: ActiveSpot, timeMs: Long) {
        val text = if (spot.type.requiresSignal) {
            when {
                // 이미 켰으면 다시 말하지 않는다.
                spot.signalDistanceM != null -> return
                // 지금 같은 방향으로 켜져 있으면 말하지 않는다.
                spot.direction != Direction.NONE && matches(lastSample?.signal, spot.direction) -> return
                // 깜빡이를 읽을 수 없거나 방향을 모르면 "꺼져 있다"는 말을 하지 않는다.
                !signalCheckable(spot) -> Phrases.pre400mWithoutSignal(spot.type, spot.direction)
                else -> Phrases.pre400m(spot.type, spot.direction)
            }
        } else {
            Phrases.pre400m(spot.type, spot.direction, spot.roundaboutClock)
        }
        budget.enqueue(timeMs, spot.key, UtteranceKind.PRE_400M, text)
    }

    /** 마지막 TBT 뒤 달린 거리를 뺀 현재 남은 거리 추정. 너무 오래돼 추정할 수 없으면 null. */
    private fun estimatedRemaining(spot: ActiveSpot, nowMs: Long): Int? {
        val elapsed = nowMs - spot.lastUpdateMs
        if (elapsed <= 0) return spot.lastRemainingM
        if (elapsed > config.maxExtrapolationMs) return null
        val speed = lastSample?.speedKmh ?: return null
        return (spot.lastRemainingM - speed / 3.6 * elapsed / 1000.0).toInt()
    }

    /** 지점에 도달했는지. TBT 갱신이 드문 내비를 위해 마지막 갱신 뒤 달린 거리를 감안한다(최대 10초). */
    private fun reached(spot: ActiveSpot, nowMs: Long): Boolean {
        if (spot.reachedNear) return true
        val estimate = estimatedRemaining(spot, nowMs) ?: return false
        return estimate <= config.nearM + config.reachSlackM
    }

    private fun isRerouteJump(spot: ActiveSpot, update: TbtUpdate, etaBeforeSec: Long?): Boolean {
        val jump = update.remainingM - spot.lastRemainingM
        if (jump >= config.rerouteJumpM) return true
        val eta = update.etaEpochSec
        val etaUp = eta != null && etaBeforeSec != null && eta - etaBeforeSec >= config.rerouteEtaIncreaseSec
        return jump >= config.rerouteJumpWithEtaM && etaUp
    }

    /** 다음 안내로 넘어가면서 이전 지점을 떠날 때. */
    private fun leave(spot: ActiveSpot, update: TbtUpdate, etaBeforeSec: Long?) {
        if (reached(spot, update.timeMs)) {
            addPendingPass(spot, spot.reachedAtMs ?: update.timeMs)
            return
        }
        // 지점에 닿지 않은 채 다음 안내로 넘어갔다. ETA까지 늘었으면 놓친 것으로, 아니면 판단을 보류한다.
        val newEta = update.etaEpochSec
        val etaJump = newEta != null && etaBeforeSec != null &&
            newEta - etaBeforeSec >= config.rerouteEtaIncreaseSec
        if (etaJump) missed(spot, update.timeMs, etaBeforeSec, newEta)
        else record(spot, update.timeMs, SpotResult.UNCERTAIN)
    }

    private fun addPendingPass(spot: ActiveSpot, passMs: Long) {
        // 통과 직전의 급제동도 판정에 넣는다(합류하며 급히 선 경우).
        val brakeBefore = lastHarshBrakeMs?.let { passMs - it <= config.brakeWindowBeforePassMs } == true
        pendingPasses += PendingPass(spot, passMs, harshBrake = brakeBefore)
    }

    private fun missed(spot: ActiveSpot, timeMs: Long, baseEtaSec: Long?, newEtaSec: Long?) {
        record(spot, timeMs, SpotResult.MISSED)
        scheduleRerouteMessage(timeMs, baseEtaSec, newEtaSec)
    }

    private var lastRerouteMessageMs: Long? = null

    private fun enqueueReroute(timeMs: Long, addedSec: Long?) {
        lastRerouteMessageMs = timeMs
        budget.enqueue(timeMs, null, UtteranceKind.REROUTE, Phrases.reroute(addedSec))
    }

    private fun scheduleRerouteMessage(timeMs: Long, baseEtaSec: Long?, newEtaSec: Long?) {
        if (newEtaSec != null) {
            enqueueReroute(timeMs, baseEtaSec?.let { newEtaSec - it })
        } else if (pendingReroute == null) {
            pendingReroute = PendingReroute(timeMs, baseEtaSec, currentManeuverId)
        }
    }

    /**
     * 재탐색 뒤 새 경로의 ETA를 기다린다. 재탐색 직후의 갱신은 아직 옛 ETA일 수 있으므로,
     * 안내 지점이 바뀌었거나 ETA가 달라진 갱신만 새 경로로 인정한다.
     */
    private fun resolvePendingReroute(update: TbtUpdate) {
        val pr = pendingReroute ?: return
        val eta = update.etaEpochSec ?: return
        val newRoute = update.maneuverId != pr.maneuverIdAtReroute || eta != pr.baseEtaSec
        if (!newRoute) return
        pendingReroute = null
        enqueueReroute(update.timeMs, pr.baseEtaSec?.let { eta - it })
    }

    private fun advance(nowMs: Long): List<Utterance> {
        val due = pendingPasses.filter { nowMs - it.passMs >= config.brakeWindowAfterPassMs }
        due.forEach { finalizePass(it, nowMs) }
        pendingPasses.removeAll(due)

        pendingReroute?.let {
            if (nowMs - it.createdMs >= config.rerouteMessageWaitMs) {
                pendingReroute = null
                enqueueReroute(nowMs, null)
            }
        }

        val gate = SpeakGate(lastSample?.steeringDeg, lastHarshBrakeMs, navSpeaking)
        return budget.flush(nowMs, gate)
    }

    private fun finalizePass(pass: PendingPass, nowMs: Long) {
        val spot = pass.spot
        val levelBefore = fading.level(spot.type)
        val result = when {
            pass.harshBrake -> SpotResult.UNCERTAIN
            signalCheckable(spot) && spot.signalDistanceM == null -> SpotResult.PASSED_NO_SIGNAL
            else -> SpotResult.PASSED
        }
        val outcome = record(spot, pass.passMs, result)
        if (result == SpotResult.PASSED && levelBefore.afterPass) {
            // 판정이 늦게 확정돼도 확정 시각 기준으로 큐에 넣어 바로 만료되지 않게 한다.
            budget.enqueue(
                nowMs, spot.key, UtteranceKind.AFTER_PASS,
                Phrases.afterPass(spot.type, outcome.signalConfirmed),
            )
        }
    }

    private fun record(spot: ActiveSpot, timeMs: Long, result: SpotResult): SpotOutcome {
        val outcome = SpotOutcome(
            timeMs = timeMs,
            spotId = spot.id,
            type = spot.type,
            direction = spot.direction,
            result = result,
            signalConfirmed = spot.type.requiresSignal && spot.signalDistanceM != null,
            signalDistanceM = spot.signalDistanceM,
        )
        log.add(outcome)
        fading.record(outcome)
        return outcome
    }

    private fun matches(signal: TurnSignal?, direction: Direction): Boolean = when (direction) {
        Direction.LEFT -> signal == TurnSignal.LEFT
        Direction.RIGHT -> signal == TurnSignal.RIGHT
        // 방향을 모르는 지점에서는 어떤 깜빡이도 그 지점을 위한 것이라고 볼 수 없다.
        Direction.NONE -> false
    }

    private companion object {
        const val RECENT_REROUTE_MS = 10_000L
    }
}
