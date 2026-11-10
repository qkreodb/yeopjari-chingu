package com.yeopjari.core

data class CoachConfig(
    val pre1kmM: Int = 1_000,
    val pre400mM: Int = 400,
    /** 이 거리 안으로 들어오면 지점에 "도달"한 것으로 본다. */
    val nearM: Int = 50,
    /**
     * 깜빡이를 "지점 전에 켰다"고 인정하는 구간. 지점에서 이 거리 안이면서 도달 전이어야 한다.
     * 훨씬 앞에서 다른 이유(차로 변경)로 켠 깜빡이를 인정하지 않기 위해서다.
     */
    val signalCreditM: Int = 1_000,
    /** 통과 후 급제동을 지켜보는 시간. 이 시간이 지나야 판정을 확정한다. */
    val brakeWindowAfterPassMs: Long = 10_000,
    /** 같은 지점의 남은 거리가 이만큼 다시 늘어나면 재탐색으로 본다. */
    val rerouteJumpM: Int = 100,
    /** 지점에 도달하지 않은 채 다음 안내로 넘어갈 때, ETA가 이만큼 늘었으면 놓친 것으로 본다. */
    val rerouteEtaIncreaseSec: Long = 30,
    /** TBT 갱신이 드문 내비에서, 마지막 갱신 뒤 달린 거리를 감안할 때 더하는 여유. */
    val reachSlackM: Int = 30,
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
        val roundaboutExit: Int?,
        var lastRemainingM: Int,
        var lastUpdateMs: Long,
        var said1km: Boolean = false,
        var said400m: Boolean = false,
        var reachedNear: Boolean = false,
        var signalDistanceM: Int? = null,
    )

    private class PendingPass(val spot: ActiveSpot, val passMs: Long, var harshBrake: Boolean = false)

    private class PendingReroute(val createdMs: Long, val baseEtaSec: Long?)

    fun onVehicle(sample: VehicleSample): List<Utterance> {
        if (brakeDetector.onSample(sample)) {
            lastHarshBrakeMs = sample.timeMs
            pendingPasses.forEach {
                if (sample.timeMs - it.passMs <= config.brakeWindowAfterPassMs) it.harshBrake = true
            }
        }
        if (sample.signal != null) signalReadable = true
        current?.let { spot ->
            val inCreditZone = !spot.reachedNear && spot.lastRemainingM in (config.nearM + 1)..config.signalCreditM
            if (spot.signalDistanceM == null && inCreditZone && matches(sample.signal, spot.direction)) {
                spot.signalDistanceM = spot.lastRemainingM
            }
        }
        lastSample = sample
        if (sample.gear == Gear.P) endOfGuidance(sample.timeMs)
        return advance(sample.timeMs)
    }

    fun onTbt(update: TbtUpdate): List<Utterance> {
        val etaBefore = lastEtaSec
        update.etaEpochSec?.let { lastEtaSec = it }

        pendingReroute?.let { pr ->
            val eta = update.etaEpochSec
            if (eta != null) {
                pendingReroute = null
                val added = pr.baseEtaSec?.let { eta - it }
                budget.enqueue(update.timeMs, null, UtteranceKind.REROUTE, Phrases.reroute(added))
            }
        }

        if (update.maneuverId != currentManeuverId) {
            current?.let { leave(it, update, etaBefore) }
            currentManeuverId = update.maneuverId
            current = HardSpotType.of(update.kind)?.let {
                ActiveSpot(
                    key = "${update.maneuverId}#${spotSeq++}",
                    id = update.maneuverId,
                    type = it,
                    direction = update.direction,
                    roundaboutExit = update.roundaboutExit,
                    lastRemainingM = update.remainingM,
                    lastUpdateMs = update.timeMs,
                )
            }
        } else {
            val spot = current
            if (spot != null && !spot.reachedNear &&
                update.remainingM > spot.lastRemainingM + config.rerouteJumpM
            ) {
                // 같은 지점인데 남은 거리가 다시 늘었다: 지나쳐서 돌아오는 경로가 잡힌 것.
                missed(spot, update.timeMs, etaBefore, update.etaEpochSec)
                // 돌아오는 경로의 같은 지점은 새 지점으로 처음부터 안내한다.
                current = ActiveSpot(
                    key = "${update.maneuverId}#${spotSeq++}",
                    id = spot.id,
                    type = spot.type,
                    direction = spot.direction,
                    roundaboutExit = spot.roundaboutExit,
                    lastRemainingM = update.remainingM,
                    lastUpdateMs = update.timeMs,
                )
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
     * 안내가 끝났을 때(목적지 도착, P단). 마지막 지점 뒤에 다음 안내가 없으면 [leave] 가 불리지 않으므로
     * 여기서 정리하고, 보류 중인 판정은 급제동 창을 기다리지 않고 확정한다.
     */
    fun endOfGuidance(timeMs: Long) {
        current?.let { spot ->
            if (reached(spot, timeMs)) pendingPasses += PendingPass(spot, timeMs)
            else record(spot, timeMs, SpotResult.UNCERTAIN)
        }
        current = null
        currentManeuverId = null
        pendingPasses.forEach { finalizePass(it, timeMs) }
        pendingPasses.clear()
    }

    private fun approach(spot: ActiveSpot, update: TbtUpdate) {
        spot.lastRemainingM = update.remainingM
        spot.lastUpdateMs = update.timeMs
        val remaining = update.remainingM
        if (remaining <= config.nearM) spot.reachedNear = true
        val level = fading.level(spot.type)

        // 1km 구간을 건너뛰고 400m 안으로 바로 들어온 경우 1km 안내는 하지 않는다(400m 안내가 대신한다).
        if (!spot.said1km && remaining <= config.pre1kmM && remaining > config.pre400mM) {
            spot.said1km = true
            if (level.pre1km) {
                budget.enqueue(
                    update.timeMs, spot.key, UtteranceKind.PRE_1KM,
                    Phrases.pre1km(spot.type, spot.direction, spot.roundaboutExit),
                )
            }
        }

        if (!spot.said400m && remaining <= config.pre400mM && remaining > config.nearM) {
            spot.said400m = true
            if (level.pre400m) pre400m(spot, update.timeMs)
        }
    }

    private fun pre400m(spot: ActiveSpot, timeMs: Long) {
        val text = if (spot.type.requiresSignal) {
            when {
                // 이미 켰으면 다시 말하지 않는다.
                spot.signalDistanceM != null -> return
                // 지금도 같은 방향으로 켜져 있으면(인정 구간 직전에 켠 경우) 말하지 않는다.
                matches(lastSample?.signal, spot.direction) -> return
                // 깜빡이를 읽을 수 없으면 "꺼져 있다"는 말을 하지 않는다.
                !signalReadable -> Phrases.pre400mWithoutSignal(spot.type, spot.direction)
                else -> Phrases.pre400m(spot.type, spot.direction)
            }
        } else {
            Phrases.pre400m(spot.type, spot.direction, spot.roundaboutExit)
        }
        budget.enqueue(timeMs, spot.key, UtteranceKind.PRE_400M, text)
    }

    /**
     * 지점에 도달했는지. TBT 갱신이 드문 내비를 위해, 마지막 갱신 뒤 현재 속도로 달린 거리를 감안한다.
     */
    private fun reached(spot: ActiveSpot, nowMs: Long): Boolean {
        if (spot.reachedNear) return true
        val speed = lastSample?.speedKmh ?: return false
        val travelledM = speed / 3.6 * (nowMs - spot.lastUpdateMs) / 1000.0
        return spot.lastRemainingM - travelledM <= config.nearM + config.reachSlackM
    }

    /** 다음 안내로 넘어가면서 이전 지점을 떠날 때. */
    private fun leave(spot: ActiveSpot, update: TbtUpdate, etaBeforeSec: Long?) {
        if (reached(spot, update.timeMs)) {
            pendingPasses += PendingPass(spot, update.timeMs)
            return
        }
        // 지점에 닿지 않은 채 다음 안내로 넘어갔다. ETA까지 늘었으면 놓친 것으로, 아니면 판단을 보류한다.
        val newEta = update.etaEpochSec
        val etaJump = newEta != null && etaBeforeSec != null &&
            newEta - etaBeforeSec >= config.rerouteEtaIncreaseSec
        if (etaJump) missed(spot, update.timeMs, etaBeforeSec, newEta)
        else record(spot, update.timeMs, SpotResult.UNCERTAIN)
    }

    private fun missed(spot: ActiveSpot, timeMs: Long, baseEtaSec: Long?, newEtaSec: Long?) {
        record(spot, timeMs, SpotResult.MISSED)
        scheduleRerouteMessage(timeMs, baseEtaSec, newEtaSec)
    }

    private fun scheduleRerouteMessage(timeMs: Long, baseEtaSec: Long?, newEtaSec: Long?) {
        if (newEtaSec != null) {
            val added = baseEtaSec?.let { newEtaSec - it }
            budget.enqueue(timeMs, null, UtteranceKind.REROUTE, Phrases.reroute(added))
        } else if (pendingReroute == null) {
            pendingReroute = PendingReroute(timeMs, baseEtaSec)
        }
    }

    private fun advance(nowMs: Long): List<Utterance> {
        val due = pendingPasses.filter { nowMs - it.passMs >= config.brakeWindowAfterPassMs }
        due.forEach { finalizePass(it, nowMs) }
        pendingPasses.removeAll(due)

        pendingReroute?.let {
            if (nowMs - it.createdMs >= config.rerouteMessageWaitMs) {
                pendingReroute = null
                budget.enqueue(nowMs, null, UtteranceKind.REROUTE, Phrases.reroute(null))
            }
        }

        val gate = SpeakGate(lastSample?.steeringDeg, lastHarshBrakeMs, navSpeaking)
        return budget.flush(nowMs, gate)
    }

    private fun finalizePass(pass: PendingPass, nowMs: Long) {
        val spot = pass.spot
        val levelBefore = fading.level(spot.type)
        val signalRequired = spot.type.requiresSignal && signalReadable
        val result = when {
            pass.harshBrake -> SpotResult.UNCERTAIN
            signalRequired && spot.signalDistanceM == null -> SpotResult.PASSED_NO_SIGNAL
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
        Direction.NONE -> signal == TurnSignal.LEFT || signal == TurnSignal.RIGHT
    }
}
