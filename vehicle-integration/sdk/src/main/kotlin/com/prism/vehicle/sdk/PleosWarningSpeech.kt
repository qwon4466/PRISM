package com.prism.vehicle.sdk

import android.content.Context
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

    private companion object { const val TAG = "PRISM_AUDIO" }
}
