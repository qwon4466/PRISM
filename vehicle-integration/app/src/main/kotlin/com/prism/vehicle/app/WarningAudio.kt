package com.prism.vehicle.app

import android.app.Application
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.util.Log
import com.prism.vehicle.adapters.VehicleSdkProvider
import com.prism.vehicle.sdk.PleosWarningSpeech
import java.util.Locale

internal data class AudioAnnouncementResult(val chimePlayed: Boolean, val speechQueued: Boolean)

/** A short two-note cue and spoken, explicitly simulated guidance. */
internal class WarningAudio(app: Application) {
    // 알림음·음성 모두 내비 안내 볼륨 그룹으로: 음악 볼륨과 따로 조절되고, 재생 중인 음악은 잠시 줄어든다
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private val chimeLock = Any()
    private var chimeId = 0
    private var pendingChime = false
    private val pleosSpeech = PleosWarningSpeech(app)
    @Volatile private var chimeReady = false
    @Volatile private var speechReady = false
    private lateinit var speech: TextToSpeech

    init {
        soundPool.setOnLoadCompleteListener { _, id, status ->
            synchronized(chimeLock) {
                chimeReady = id == chimeId && status == 0
                Log.i(TAG, "chimeReady=$chimeReady")
                if (chimeReady && pendingChime) {
                    val played = soundPool.play(chimeId, 1f, 1f, 1, 0, 1f) != 0
                    pendingChime = false
                    Log.i(TAG, "pending chime played=$played")
                }
            }
        }
        chimeId = soundPool.load(app, R.raw.alert_chime, 1)
        speech = TextToSpeech(app) { result ->
            if (result == TextToSpeech.SUCCESS) {
                speechReady = speech.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
                Log.i(TAG, "androidSpeechReady=$speechReady")
                speech.setAudioAttributes(PleosWarningSpeech.GUIDANCE)
            }
        }
    }

    fun announce(message: String, pleos: VehicleSdkProvider?, chime: Boolean = true): AudioAnnouncementResult {
        val played = if (chime) synchronized(chimeLock) {
            if (chimeReady) soundPool.play(chimeId, 1f, 1f, 1, 0, 1f) != 0
            else { pendingChime = true; false }
        } else false
        if (pleos?.speak(message) == true) {
            Log.i(TAG, "alert=$message output=pleos chime=$played")
            return AudioAnnouncementResult(played, true)
        }
        if (pleosSpeech.speak(message)) {
            Log.i(TAG, "alert=$message output=pleos-only chime=$played")
            return AudioAnnouncementResult(played, true)
        }
        val spoken = speechReady && speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "prism-warning") == TextToSpeech.SUCCESS
        Log.i(TAG, "alert=$message output=android spoken=$spoken chime=$played")
        return AudioAnnouncementResult(played, spoken)
    }

    fun stop() {
        synchronized(chimeLock) { pendingChime = false }
        speech.stop()
        pleosSpeech.stop()
    }
    fun release() {
        speech.shutdown()
        pleosSpeech.release()
        soundPool.release()
    }

    private companion object { const val TAG = "PRISM_AUDIO" }

    internal fun isChimeReady(): Boolean = chimeReady
}
