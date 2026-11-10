package com.yeopjari.app

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yeopjari.core.CautionKind
import com.yeopjari.core.CautionUpdate
import com.yeopjari.core.TbtAdapter
import com.yeopjari.core.TbtUpdate

/** 내비에서 엔진으로 가는 이벤트. */
sealed interface NaviEvent {
    data class Tbt(val update: TbtUpdate) : NaviEvent
    data class Caution(val update: CautionUpdate) : NaviEvent
    data class Rerouted(val timeMs: Long) : NaviEvent
    data class GuidanceEnded(val timeMs: Long) : NaviEvent
}

/**
 * Pleos NaviHelper 를 듣고 엔진 이벤트로 바꾼다.
 * 회전 안내는 목록의 첫 항목(바로 다음 지점)만 쓰고, ETA 는 목적지까지 남은 시간으로 만든다.
 */
class NaviInputs(private val context: Context, private val onEvent: (NaviEvent) -> Unit) {
    private val tag = "NaviInputs"
    private val adapter = TbtAdapter()
    private var navi: NaviHelper? = null

    /**
     * 도착 예정 시각(경과 시간 기준, 초). DrivingInfo 를 받은 시각에 남은 시간을 더해 고정한다.
     * TBT 마다 '지금 + 남은 시간'으로 다시 계산하면 DrivingInfo 사이에 ETA 가 계속 밀려 재탐색으로 오판한다.
     */
    @Volatile
    private var etaSec: Long? = null

    @Volatile
    var connected = false
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfo: List<TBTInfo>) {
            val next = tbtInfo.firstOrNull() ?: return
            val now = SystemClock.elapsedRealtime()
            onEvent(NaviEvent.Tbt(adapter.toUpdate(now, next.type.name, next.distance, next.description, etaSec)))
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            etaSec = SystemClock.elapsedRealtime() / 1000 + drivingInfo.destination.duration
        }

        override fun onCautionPointInfo(cautionPoints: List<CautionPoint>) {
            val now = SystemClock.elapsedRealtime()
            cautionPoints.forEach { cp ->
                val kind = when (cp.type) {
                    CautionPointType.NARROW_ROAD -> CautionKind.NARROW_ROAD
                    else -> CautionKind.OTHER
                }
                onEvent(NaviEvent.Caution(CautionUpdate(now, cp.id, kind, cp.remainDistance)))
            }
        }

        override fun onReRouteEnded() {
            adapter.reset()
            onEvent(NaviEvent.Rerouted(SystemClock.elapsedRealtime()))
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            onEvent(NaviEvent.GuidanceEnded(SystemClock.elapsedRealtime()))
        }

        override fun onRouteCancelled() {
            onEvent(NaviEvent.GuidanceEnded(SystemClock.elapsedRealtime()))
        }
    }

    fun start() {
        runCatching {
            val n = NaviHelper(context.applicationContext)
            navi = n
            n.initialize(
                {
                    connected = true
                    n.addListener(listener)
                    n.getTBTInfo()
                },
                { t -> Log.w(tag, "NaviHelper 연결 실패", t) },
            )
        }.onFailure { Log.w(tag, "NaviHelper 사용 불가", it) }
    }

    fun stop() {
        runCatching {
            navi?.removeListener(listener)
            navi?.release()
        }
        navi = null
        connected = false
    }
}
