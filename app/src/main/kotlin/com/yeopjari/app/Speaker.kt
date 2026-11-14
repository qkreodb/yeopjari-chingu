package com.yeopjari.app

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 차가 하는 말. Pleos TTS(온디바이스, 한국어)를 쓰고, 초기화에 실패하면 안드로이드 기본 TTS 로 대신한다.
 * 내비 안내와 같은 용도(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 소리를 내어 시스템이 내비 음성과 조율하게 한다.
 * TTS 가 준비되기 전에 들어온 문장은 잠시 보관했다가, 준비되면 10초 안의 것만 말한다.
 */
class Speaker(context: Context) {
    private val tag = "Speaker"
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var pleos: PleosTts? = null
    @Volatile private var pleosReady = false
    private var fallback: AndroidTts? = null
    @Volatile private var fallbackReady = false
    private val waiting = ArrayDeque<Pair<Long, String>>()

    /** 지금 우리 앱이 말하는 중인지(TTS 시작~끝). 내비 음성 감지에서 우리 소리를 빼는 데 쓴다. */
    @Volatile
    var speaking: Boolean = false
        private set

    init {
        runCatching {
            PleosTts(context.applicationContext, Mode.ON_DEVICE).also { tts ->
                pleos = tts
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        // addEventListener 는 initialize 가 끝난 뒤에만 부를 수 있다(전에 부르면 예외).
                        // 리스너나 로케일 설정이 실패하면 Pleos 를 쓰지 않고 기본 TTS 로 대신한다.
                        // (리스너 없이 말하면 우리 목소리를 내비 음성으로 오인한다.)
                        val configured = runCatching {
                            tts.addEventListener(speakingListener())
                            tts.setLocale(Locale.KOREA)
                            tts.setAudioAttributes(attributes)
                        }.onFailure { Log.w(tag, "Pleos TTS 설정 실패, 기본 TTS 로 대신함", it) }
                        if (configured.isFailure) return
                        pleosReady = true
                        flushWaiting()
                    }

                    override fun onFailure(errorType: ErrorType) {
                        Log.w(tag, "Pleos TTS 초기화 실패: $errorType")
                    }
                })
            }
        }.onFailure { Log.w(tag, "Pleos TTS 사용 불가", it) }

        fallback = AndroidTts(context.applicationContext) { status ->
            if (status == AndroidTts.SUCCESS) {
                fallback?.language = Locale.KOREA
                fallback?.setAudioAttributes(attributes)
                fallback?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                })
                fallbackReady = true
                flushWaiting()
            }
        }
    }

    val engineName: String
        get() = when {
            pleosReady -> "Pleos TTS"
            fallbackReady -> "Android TTS"
            else -> "준비 중"
        }

    fun speak(text: String) {
        if (!say(text)) {
            synchronized(waiting) { waiting.addLast(SystemClock.elapsedRealtime() to text) }
        }
    }

    private fun say(text: String): Boolean {
        val p = pleos
        // 두 번째 인자는 isFinal: true 여야 바로 읽는다. false 면 이어질 문장을 기다리느라 다음 발화 때까지 밀린다.
        if (pleosReady && p != null) {
            val spoken = runCatching { p.speak(text, true) }
                .onFailure { Log.w(tag, "Pleos TTS 말하기 실패, 기본 TTS 로 대신함", it) }
            if (spoken.isSuccess) return true
        }
        val f = fallback
        if (fallbackReady && f != null) {
            return f.speak(text, AndroidTts.QUEUE_ADD, null, text.hashCode().toString()) == AndroidTts.SUCCESS
        }
        return false
    }

    private fun flushWaiting() {
        val now = SystemClock.elapsedRealtime()
        val fresh = synchronized(waiting) {
            waiting.filter { now - it.first <= MAX_WAIT_MS }.also { waiting.clear() }
        }
        // 한 번에 하나의 완결 요청으로 보낸다. 따로 보내면 앞 문장이 끊길 수 있다.
        if (fresh.isNotEmpty()) say(fresh.joinToString(" ") { it.second })
    }

    private fun speakingListener() = object : EventListener {
        override fun onStart() { speaking = true }
        override fun onReady() = Unit
        override fun onDone() { speaking = false }
        override fun onStop() { speaking = false }
        override fun onError(errorType: ErrorType) { speaking = false }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun release() {
        runCatching { pleos?.release() }
        fallback?.shutdown()
    }

    private companion object {
        const val MAX_WAIT_MS = 10_000L
    }
}
