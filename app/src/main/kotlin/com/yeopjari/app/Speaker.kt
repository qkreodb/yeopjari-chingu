package com.yeopjari.app

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 차가 하는 말. Pleos TTS(온디바이스, 한국어)를 쓰고, 초기화에 실패하면 안드로이드 기본 TTS 로 대신한다.
 * 내비 안내와 같은 용도(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 소리를 내어 시스템이 내비 음성과 조율하게 한다.
 */
class Speaker(context: Context) {
    private val tag = "Speaker"
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var pleos: PleosTts? = null
    private var pleosReady = false
    private var fallback: AndroidTts? = null
    private var fallbackReady = false

    /** 우리 앱이 마지막으로 말을 시작한 시각. 내비 음성 감지에서 우리 소리를 빼는 데 쓴다. */
    @Volatile
    var lastSpokeAtMs: Long = 0
        private set

    init {
        runCatching {
            PleosTts(context.applicationContext, Mode.ON_DEVICE).also { tts ->
                pleos = tts
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching {
                            tts.setLocale(Locale.KOREA)
                            tts.setAudioAttributes(attributes)
                        }
                        pleosReady = true
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
                fallbackReady = true
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
        lastSpokeAtMs = SystemClock.elapsedRealtime()
        val p = pleos
        if (pleosReady && p != null) {
            runCatching { p.speak(text, false) }.onSuccess { return }
        }
        if (fallbackReady) fallback?.speak(text, AndroidTts.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun release() {
        runCatching { pleos?.release() }
        fallback?.shutdown()
    }
}
