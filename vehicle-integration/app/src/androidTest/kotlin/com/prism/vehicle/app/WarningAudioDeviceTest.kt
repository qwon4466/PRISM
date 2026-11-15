package com.prism.vehicle.app

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.vehicle.replay.RiskLevel
import com.prism.vehicle.replay.RiskObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the real SoundPool and TTS requests on an emulator. */
@RunWith(AndroidJUnit4::class)
class WarningAudioDeviceTest {
    @Test fun watchAndWarningBothPlayChimeAndQueueSpeech() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val policy = RiskAudioAlertPolicy()
        val audio = WarningAudio(app)
        try {
            for (attempt in 0 until 30) {
                if (audio.isChimeReady()) break
                Thread.sleep(500)
            }
            assertTrue("Alert chime did not load", audio.isChimeReady())
            listOf(RiskLevel.WATCH, RiskLevel.WARNING).forEach { level ->
                val message = policy.next(listOf(RiskObject("test-car", level)))!!
                val result = audio.announce(message, null)
                assertTrue("$level chime did not play", result.chimePlayed)
                assertTrue("$level TTS was not queued", result.speechQueued)
                Thread.sleep(3_000)
            }
        } finally {
            audio.release()
        }
    }
}
