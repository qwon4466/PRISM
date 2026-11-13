package com.prism.vehicle.sdk

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener

/** TTS-only session for offline scenario warnings; does not start Vehicle or location SDKs. */
class PleosWarningSpeech(context: Context) {
    private var service: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        runCatching {
            service = TextToSpeech(context.applicationContext, Mode.ON_DEVICE).also { tts ->
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        // 기본값은 USAGE_MEDIA(음악 볼륨) → 내비 안내 볼륨으로. 초기화 전에 부르면 SDK 가 예외를 던진다
                        runCatching { tts.setAudioAttributes(GUIDANCE) }.onFailure { Log.w(TAG, "Pleos TTS audio attributes failed", it) }
                        ready = true
                        Log.i(TAG, "pleosSpeechReady=true")
                    }
                    override fun onFailure(error: ErrorType) {
                        Log.w(TAG, "pleosSpeechReady=false error=${error.name}")
                    }
                })
            }
        }.onFailure { Log.w(TAG, "Pleos TTS initialization failed", it) }
    }

    fun speak(text: String): Boolean {
        val tts = service ?: return false
        if (!ready) return false
        return runCatching { tts.speak(text, true) }.onFailure { Log.w(TAG, "Pleos TTS speak failed", it) }.isSuccess
    }

    fun stop() { service?.let { runCatching { it.stop() } } }
    fun release() {
        stop()
        service?.let { runCatching { it.release() } }
        service = null
        ready = false
    }

    companion object {
        private const val TAG = "PRISM_AUDIO"
        /** 운전 중 안내 음성용. Pleos SDK 에 볼륨 API 가 없어 볼륨은 차량의 내비 안내 볼륨을 따른다. */
        val GUIDANCE: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
