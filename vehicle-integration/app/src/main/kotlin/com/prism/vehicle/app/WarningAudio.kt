package com.prism.vehicle.app

import android.app.Application
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.util.Log
import com.prism.vehicle.adapters.VehicleSdkProvider
import com.prism.vehicle.sdk.PleosWarningSpeech
import java.util.Locale

/** A short two-note cue followed by spoken, explicitly simulated guidance. */
class WarningAudio(app: Application) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private var chimeId = 0
    private val pleosSpeech = PleosWarningSpeech(app)
    @Volatile private var chimeReady = false
    @Volatile private var speechReady = false
    private lateinit var speech: TextToSpeech

    init {
        soundPool.setOnLoadCompleteListener { _, id, status ->
            chimeReady = id == chimeId && status == 0
            Log.i(TAG, "chimeReady=$chimeReady")
        }
        chimeId = soundPool.load(app, R.raw.alert_chime, 1)
        speech = TextToSpeech(app) { result ->
            if (result == TextToSpeech.SUCCESS) {
                speechReady = speech.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
                Log.i(TAG, "androidSpeechReady=$speechReady")
                speech.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }
    }

    fun announce(message: String, pleos: VehicleSdkProvider?, chime: Boolean = true): Boolean {
        val played = chime && chimeReady && soundPool.play(chimeId, 0.75f, 0.75f, 1, 0, 1f) != 0
        if (pleos?.speak(message) == true) {
            Log.i(TAG, "warning output=pleos chime=$played")
            return true
        }
        if (pleosSpeech.speak(message)) {
            Log.i(TAG, "warning output=pleos-only chime=$played")
            return true
        }
        val spoken = speechReady && speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "prism-warning") == TextToSpeech.SUCCESS
        Log.i(TAG, "warning output=android spoken=$spoken chime=$played")
        return spoken
    }

    fun stop() {
        speech.stop()
        pleosSpeech.stop()
    }
    fun release() {
        speech.shutdown()
        pleosSpeech.release()
        soundPool.release()
    }

    private companion object { const val TAG = "PRISM_AUDIO" }
}
