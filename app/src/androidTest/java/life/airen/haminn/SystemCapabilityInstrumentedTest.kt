package life.airen.haminn

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import life.airen.haminn.capability.BluetoothController
import life.airen.haminn.capability.InfraredController
import life.airen.haminn.capability.SensorController
import life.airen.haminn.capability.SpeechController
import life.airen.haminn.capability.TtsController
import life.airen.haminn.capability.WifiController
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SystemCapabilityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun speechAndTtsDetectionMatchInstalledAndroidServices() = runBlocking {
        val speech = SpeechController(context)
        val speechStatus = speech.availability()
        @Suppress("DEPRECATION")
        val recognizers = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        assertEquals(SpeechRecognizer.isRecognitionAvailable(context) || recognizers.any { it.serviceInfo.enabled }, speechStatus.getBoolean("streamingAvailable"))
        assertTrue(!speechStatus.has("services"))
        assertEquals(recognizers.size, speech.providerStatus().getJSONArray("services").length())

        val tts = TtsController(context)
        try {
            @Suppress("DEPRECATION")
            val installed = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            val ttsStatus = tts.availability()
            assertEquals(installed.isNotEmpty(), ttsStatus.getBoolean("available"))
            assertTrue(!ttsStatus.has("services"))
            val providerStatus = tts.providerStatus()
            assertEquals(installed.size, providerStatus.getJSONArray("engines").length())
            if (installed.isNotEmpty()) {
                assertTrue(providerStatus.getJSONArray("engines").length() > 0)
                assertTrue(tts.voices().has("voices"))
            }
        } finally { tts.shutdown() }
    }

    @Test fun sensorCatalogMatchesFrameworkAndAvailableAccelerometerStreams() {
        val framework = context.getSystemService(SensorManager::class.java)
        val controller = SensorController(context)
        val catalog = controller.availability().getJSONArray("sensors")
        val accelerometer = (0 until catalog.length()).map(catalog::getJSONObject).first { it.getString("type") == "accelerometer" }
        val expected = framework.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        assertEquals(expected, accelerometer.getBoolean("available"))
        if (!expected) return
        val latch = CountDownLatch(1)
        val watch = controller.watch(JSONObject().put("type", "accelerometer").put("rateHz", 5)) { event, data ->
            if (event == "sensors.changed" && data.getJSONArray("values").length() >= 3) latch.countDown()
        }
        try { assertTrue("No accelerometer event received", latch.await(3, TimeUnit.SECONDS)) }
        finally { controller.clearWatch(watch.getString("subscriptionId")); controller.cancelAll() }
    }

    @Test fun connectivityAndInfraredSupportMatchDeviceFeatures() {
        val features = context.packageManager
        assertEquals(features.hasSystemFeature("android.hardware.wifi"), WifiController(context).status().getBoolean("supported"))
        assertEquals(features.hasSystemFeature("android.hardware.bluetooth"), BluetoothController(context).status().getBoolean("supported"))
        assertEquals(features.hasSystemFeature("android.hardware.consumerir"), InfraredController(context).status().getBoolean("supported"))
    }
}
