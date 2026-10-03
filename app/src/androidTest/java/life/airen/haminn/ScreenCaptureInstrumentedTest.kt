package life.airen.haminn

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import life.airen.haminn.capability.ScreenCapturePlan
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Screen capture and screen recording on the real device.
 *
 * Two things here cannot be proven from the JVM and cannot be proven by reading the code:
 *
 * 1. A projection is handed out only after the *user* answers a system dialog, and one consent
 *    buys exactly one virtual display. Nothing in the app can answer that dialog on the user's
 *    behalf, so these tests answer it through the shell, looking the button up in a fresh window
 *    dump every time — a remembered coordinate is wrong on the next ROM and wrong again after
 *    any layout change.
 * 2. The whole point of the feature is that it keeps running while the user is somewhere else.
 *    The recording test leaves Haminn entirely, and stops the recording *while* Haminn is in
 *    the background, which is the only way "captures the whole device screen, including other
 *    apps" means anything.
 *
 * While a dialog is up the test never touches the activity: it fires the JavaScript, answers the
 * dialogs through the shell, and only then reads the page back. A projection dialog pauses
 * MainActivity, and driving it through `ActivityScenario.onActivity` in that window would block
 * against the very result the dialog exists to deliver.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ScreenCaptureInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** What was on screen instead, when a dialog could not be answered; appended to the failure. */
    private var dialogDiagnosis: String? = null

    /** The media volume from before [startTone] raised it, put back by [stopTone]. */
    private var previousMusicVolume: Int? = null

    /**
     * The ungated half: what the device says it can do, and that asking is free. No dialog
     * stands in front of `availability`, so this is the cheapest proof the contract is wired.
     */
    @Test fun availabilityIsUngatedAndMatchesTheDevice() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip()), "Screen fixture")
        try {
            ActivityScenario.launch<MainActivity>(intent(installed.appId)).use { scenario ->
                assertTrue("page never became ready", waitUntilReady(scenario))
                val probe = evaluateAsync(
                    scenario,
                    "Promise.all([haminn.screen.availability(),haminn.runtime.capabilities()," +
                        "haminn.permissions.status({capability:'screen.capture'})," +
                        "haminn.permissions.status({capability:'screen.record'})," +
                        "haminn.permissions.status({capability:'microphone.record'})])" +
                        ".then(x=>({availability:x[0],screen:x[1].capabilities.find(c=>c.name==='screen')," +
                        "capture:x[2],record:x[3],microphone:x[4]}))",
                )
                assertTrue("screen probe returned nothing", probe != null)
                val json = JSONObject(probe!!)
                val availability = json.getJSONObject("availability")

                // No grant is required to ask, and the answer is the honest device contract.
                assertTrue(availability.getBoolean("supported"))
                assertEquals(
                    "systemAudio is the platform contract, not a per-device probe",
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    availability.getBoolean("systemAudio"),
                )
                val microphone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
                assertEquals(microphone, availability.getBoolean("microphone"))
                val modes = availability.getJSONArray("audioModes").strings()
                assertTrue("none must always be offered: $modes", modes.contains("none"))
                assertEquals("microphone mode must follow the hardware: $modes", microphone, modes.contains("microphone"))
                assertEquals("mixed mode needs both routes: $modes", microphone, modes.contains("both"))
                assertEquals(
                    listOf("media", "game", "unknown"),
                    availability.getJSONArray("systemAudioUsages").strings(),
                )

                // The advertised limits are the ones the host actually enforces.
                assertEquals(1_800_000L, availability.getLong("maxDurationMs"))
                assertEquals(ScreenCapturePlan.MAX_MAX_BYTES, availability.getLong("maxBytes"))
                assertEquals(60, availability.getInt("maxFrameRate"))
                assertEquals(2048, availability.getInt("stillMaxEdge"))
                assertEquals(listOf(1.0, 0.75, 0.5), availability.getJSONArray("scales").doubles())
                // One file per segment is the only way a capture longer than the single-object
                // limit can exist, so a page has to be able to find out that it is available.
                assertTrue("segmented recording must be advertised", availability.getBoolean("segmenting"))

                val screen = json.getJSONObject("screen")
                assertTrue("screen must be implemented", screen.getBoolean("implemented"))
                assertTrue("screen must be supported on this device", screen.getBoolean("supported"))
                // A recording has to outlive the page, which is why this is not a page-lifecycle
                // capability the way the microphone is.
                assertEquals("background-service", screen.getString("lifecycle"))
                assertTrue("screen must report its own features", screen.getJSONObject("features").has("audioModes"))

                // Both capabilities exist in the authorization surface, and merely asking about
                // them must not need a grant: they are still unanswered at this point.
                for (capability in listOf("capture", "record")) {
                    val status = json.getJSONObject(capability)
                    assertTrue("screen.$capability must be implemented", status.getBoolean("implemented"))
                    assertTrue("screen.$capability must be supported on this device", status.getBoolean("supported"))
                    assertEquals("screen.$capability must still be unanswered: $status", "ask", status.getString("grant"))
                    assertFalse("screen.$capability must not be usable before it is answered: $status", status.getBoolean("usable"))
                    // Projecting is authorized by the system dialog on every single use, so there
                    // is no Android runtime permission for this capability to hold.
                    assertEquals("screen.$capability holds no runtime permission: $status", 0, status.getJSONArray("missingPermissions").length())
                    assertEquals("screen.$capability has nothing missing at the system layer: $status", "granted", status.getString("system"))
                }
                // Capturing system audio rides on RECORD_AUDIO, exactly like the microphone, so
                // the gate the recording path reuses must be about that one permission and
                // nothing else.
                val microphoneMissing = json.getJSONObject("microphone").getJSONArray("missingPermissions").let { array ->
                    (0 until array.length()).map(array::getString)
                }
                assertTrue(
                    "microphone.record must ride on RECORD_AUDIO alone: $microphoneMissing",
                    microphoneMissing.isEmpty() || microphoneMissing == listOf(android.Manifest.permission.RECORD_AUDIO),
                )
            }
        } finally {
            cleanup(app, installed.appId)
        }
    }

    /**
     * Declining is a normal answer, not an error: the page gets `cancelled`, nothing reaches the
     * file library, and the session slot is released so the next request can ask again.
     */
    @Test fun decliningTheProjectionLeavesNothingBehind() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip()), "Screen fixture")
        try {
            ActivityScenario.launch<MainActivity>(intent(installed.appId)).use { scenario ->
                assertTrue("page never became ready", waitUntilReady(scenario))
                fireCapture(scenario)
                val answered = answerDialogs(listOf(GRANT, PROJECTION_DECLINE))
                assertEquals("expected the grant then the decline, answered: $answered; $dialogDiagnosis", 2, answered.size)

                val result = awaitMarker(scenario, 20_000)
                assertEquals("{\"cancelled\":true}", result)

                // A declined projection must not leave a file behind...
                val library = filesOf(scenario)
                assertTrue("a declined capture left files behind: $library", library.isEmpty())
                // ...and must not leave the slot busy: asking again still works.
                assertEquals("true", evaluateAsync(scenario, "haminn.screen.availability().then(x=>x.supported)"))
            }
        } finally {
            cleanup(app, installed.appId)
        }
    }

    /**
     * The decisive screenshot case: the projection is granted, a still is produced, and it
     * covers the whole display rather than the Haminn window.
     */
    @Test fun anAcceptedScreenshotCoversTheWholeDisplayAndEntersTheLibrary() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip()), "Screen fixture")
        try {
            ActivityScenario.launch<MainActivity>(intent(installed.appId)).use { scenario ->
                assertTrue("page never became ready", waitUntilReady(scenario))
                fireCapture(scenario)
                val answered = answerDialogs(listOf(GRANT, PROJECTION_ACCEPT))
                assertEquals("expected the grant then the projection, answered: $answered; $dialogDiagnosis", 2, answered.size)

                val result = awaitMarker(scenario, 30_000)
                assertTrue("capture never settled: $result", result != null)
                val json = JSONObject(result!!)
                assertFalse("capture was cancelled: $result", json.optBoolean("cancelled", true))
                val logicalId = json.optString("logicalFileId")
                assertTrue("no logical file id: $result", logicalId.isNotEmpty())
                assertTrue("capturedAt was not stamped: $result", json.optLong("capturedAt") > 0L)

                // The still carries the display's own aspect ratio, un-cropped. A capture of the
                // Haminn window would come back with a different shape, and the two assertions
                // together are what say "the whole device screen": the same shape *and* the same
                // longest side as the physical display.
                val display = displaySize()
                val expected = display[1].toDouble() / display[0]
                val width = json.getInt("width")
                val height = json.getInt("height")
                val actual = height.toDouble() / width
                assertTrue(
                    "still ${width}x$height (aspect $actual) does not match the display ${display[0]}x${display[1]} (aspect $expected)",
                    abs(actual - expected) < 0.02,
                )
                // maxEdge is a cap, not a target: the scale factor is min(1, maxEdge / longest), so
                // asking for more than the display has must not upscale it. Requiring the
                // display's exact longest side is stronger than "no larger than the cap", which a
                // downscaled thumbnail would also satisfy.
                val displayLongest = maxOf(display[0], display[1])
                assertEquals(
                    "still ${width}x$height does not carry the display's longest side ($displayLongest)",
                    minOf(2048, displayLongest),
                    maxOf(width, height),
                )

                // And the bytes are really there, in the library index and on disk.
                val library = filesOf(scenario)
                assertEquals("expected exactly one delivered file: $library", 1, library.size)
                val delivered = library.single()
                assertEquals("image/jpeg", delivered.getString("mime"))
                assertTrue("the still is implausibly small: $delivered", delivered.getLong("size") > 5_000L)
                assertEquals(logicalId, delivered.getString("logicalFileId"))
                val bytes = bytesOf(installed.appId, logicalId)
                assertTrue("no bytes on disk for $logicalId", bytes != null && bytes.length() > 5_000L)
                stageForHost(bytes!!, "still.jpg")

                // The other half of the cap: below the display's size it must really scale down,
                // which is the path that keeps a still inside its byte budget. A second projection
                // needs a second consent -- one agreement buys exactly one virtual display.
                fireCapture(scenario, maxEdge = 720)
                val answeredAgain = answerDialogs(listOf(PROJECTION_ACCEPT))
                assertEquals("expected a second projection, answered: $answeredAgain; $dialogDiagnosis", 1, answeredAgain.size)
                val capped = JSONObject(awaitMarker(scenario, 30_000) ?: "{}")
                assertEquals("the capped still must land on maxEdge exactly: $capped", 720, maxOf(capped.getInt("width"), capped.getInt("height")))
                val cappedPath = bytesOf(installed.appId, capped.getString("logicalFileId"))
                assertTrue("the capped still has no bytes on disk: $capped", cappedPath != null)
                assertTrue("the capped still could not be staged for the host", stageForHost(cappedPath!!, "still-capped.jpg") != null)
            }
        } finally {
            cleanup(app, installed.appId)
        }
    }

    /**
     * The recording case, and the one that justifies putting the controller on the application
     * rather than on the activity: Haminn leaves the foreground entirely, the recording keeps
     * running into a foreground service, and it is stopped from a page that is no longer on
     * screen.
     */
    @Test fun aRecordingSurvivesLeavingHaminnAndDeliversBothTracks() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip()), "Screen fixture")
        // Granted up front so the run has a fixed number of dialogs to answer. The Haminn grant
        // is still asked for and answered for real; the Android runtime dialog is the existing,
        // already-verified microphone path, not something this feature introduces.
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        try {
            ActivityScenario.launch<MainActivity>(intent(installed.appId)).use { scenario ->
                assertTrue("page never became ready", waitUntilReady(scenario))
                // Baseline first: the app is up and idle with nothing being recorded, and the
                // difference between this and the recording windows below is what the feature
                // itself costs. A settle period keeps the page's own first-paint work out of it.
                SystemClock.sleep(1_000)
                val (baselineCpu, baselineWall) = measureCpu { SystemClock.sleep(2_000) }
                // Started first so no part of the recording is silent: the tone has to be in the
                // file for the sound claim to hold, and it only counts while it is playing.
                val tone = startTone()
                fireRecording(scenario, durationMs = 20_000)
                // Three prompts stand in front of a recording: Haminn asks for the screen
                // capability, then for the microphone, because asking for "both" audio means
                // system playback and the microphone share one gate -- and only then does the
                // system projection dialog appear.
                val answered = answerDialogs(listOf(GRANT, GRANT, PROJECTION_ACCEPT), budgetMs = 40_000)
                assertEquals("expected both grants then the projection, answered: $answered; $dialogDiagnosis", 3, answered.size)

                val result = awaitMarker(scenario, 40_000)
                assertTrue("the recording never started: $result", result != null)
                val json = JSONObject(result!!)
                // A started recording carries no `cancelled` key at all; only a declined
                // projection answers `{"cancelled":true}`.
                assertFalse("the projection was declined, not accepted: $result", json.has("cancelled"))
                val recordingId = json.optString("recordingId")
                assertTrue("no recording id: $result", recordingId.isNotEmpty())
                // The frame has to follow the display and the requested scale, whichever way round
                // the display currently is: a transposed size would compose a portrait screen into
                // a landscape frame, and the file would come out rotated.
                val display = displaySize()
                assertEquals(
                    "recording width is not the display at scale 0.5 (display ${display[0]}x${display[1]}): $result",
                    display[0] / 2,
                    json.getInt("width"),
                )
                assertEquals(
                    "recording height is not the display at scale 0.5 (display ${display[0]}x${display[1]}): $result",
                    display[1] / 2,
                    json.getInt("height"),
                )
                // An honest failure of playback capture degrades the audio track rather than the
                // recording, so this reports what the device really opened.
                val openedAudio = json.optString("audio")
                assertTrue("no audio route opened at all: $result", openedAudio != "none")

                // Leave Haminn for a completely different application. Nothing tells the
                // controller to stop: the projection belongs to a foreground service.
                shell("am start -a android.settings.SETTINGS")
                // The launch animation belongs to Settings, not to the recording, and a display
                // rotation can follow it: both are settled outside every measured window.
                SystemClock.sleep(3_000)
                // Moving first, while the drag is the only thing happening. Two windows, because
                // they cost very different amounts and a recording normally faces both: a still
                // screen produces no frames at all, while a moving one is fed to the encoder as
                // fast as it changes. One long drag rather than repeated short ones, so the
                // content really moves for the whole window, and issued from a background thread
                // so the shell's own round trip does not pad the window it is measuring. The
                // drag is derived from the display, which is landscape on this device.
                val drag = Thread {
                    shellQuiet("input swipe ${display[0] / 2} ${display[1] * 3 / 4} ${display[0] / 2} ${display[1] / 4} $DRAG_MS")
                }
                val (movingCpu, movingWall) = measureCpu {
                    drag.start()
                    SystemClock.sleep(DRAG_MS.toLong())
                    drag.join(2_000)
                }
                // Then the still window, on the very same screen once the drag has stopped.
                SystemClock.sleep(500)
                val (stillCpu, stillWall) = measureCpu { SystemClock.sleep(2_000) }
                val perf = buildString {
                    append("baseline (not recording): %.3f cpu-s / %.3f s wall (%.0f%% of one core)%n".format(baselineCpu, baselineWall, 100 * baselineCpu / baselineWall))
                    append("recording, moving screen: %.3f cpu-s / %.3f s wall (%.0f%% of one core)%n".format(movingCpu, movingWall, 100 * movingCpu / movingWall))
                    append("recording, still screen:  %.3f cpu-s / %.3f s wall (%.0f%% of one core)%n".format(stillCpu, stillWall, 100 * stillCpu / stillWall))
                }
                assertFalse(
                    "Haminn was killed while recording in the background",
                    shell("pidof ${context.packageName}").isBlank(),
                )

                // Stop it from the page that is no longer on screen. A recording that only
                // worked while the page was visible would fail right here.
                val fired = evaluate(scenario, stopScript(recordingId))
                assertEquals("started", fired)
                val stopResult = awaitMarker(scenario, 40_000)
                assertTrue("stopping never settled: $stopResult", stopResult != null)
                val stopped = JSONObject(stopResult!!)
                assertEquals("the page's own stop must be reported as such: $stopResult", "page", stopped.optString("stoppedBy"))
                assertTrue("recording is implausibly short: $stopResult", stopped.optLong("durationMs") >= 5_000L)
                assertEquals("the delivered audio must match what was opened: $stopResult", openedAudio, stopped.optString("audio"))
                val logicalId = stopped.optString("logicalFileId")
                assertTrue("no logical file id: $stopResult", logicalId.isNotEmpty())

                val library = filesOf(scenario)
                val delivered = library.firstOrNull { it.getString("mime") == "video/mp4" }
                assertTrue("no recording entered the library: $library", delivered != null)
                assertTrue("the recording is implausibly small: $delivered", delivered!!.getLong("size") > 50_000L)
                assertEquals(logicalId, delivered.getString("logicalFileId"))

                // The recording is over, so the tone has done its job and must not leak into the
                // host's audio measurement of any later run.
                stopTone(tone)

                // Staged because the two tracks can only be proven with a real demuxer on the
                // host: `ffprobe` on this file is what says each track is really inside it.
                val bytes = bytesOf(installed.appId, logicalId)
                assertTrue("no bytes on disk for $logicalId", bytes != null && bytes.length() > 50_000L)
                assertTrue("the recording could not be staged for the host", stageForHost(bytes!!, "recording.mp4") != null)
                assertTrue("the cost measurement could not be staged", stageTextForHost("perf.txt", perf) != null)

                // Stopping twice answers the same thing rather than reporting nothing left to
                // stop: the outcome is cached for exactly this kind of late repeat.
                val again = evaluate(scenario, stopScript(recordingId))
                assertEquals("started", again)
                val repeated = awaitMarker(scenario, 20_000)
                assertTrue("a repeated stop did not answer: $repeated", repeated != null)
                assertEquals(
                    "a repeated stop must return the same delivered file",
                    logicalId,
                    JSONObject(repeated!!).optString("logicalFileId"),
                )
            }
        } finally {
            restoreMusicVolume()
            cleanup(app, installed.appId)
        }
    }

    // ---------------------------------------------------------------- page side

    private fun intent(appId: String) =
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, appId)

    /**
     * Loops a full-scale [TONE_HZ] tone through the device's own media path while a recording runs.
     *
     * "The file has an audio track" and "the sound the device was making is in that track" are
     * different claims, and a quiet room cannot tell them apart: an empty system track plus room
     * noise looks exactly like a working one. Only a demuxer on the host can measure the result, so
     * this makes the second claim checkable — an unmistakable tone at a known pitch, played through
     * the same usage (`USAGE_MEDIA`) that playback capture is allowed to record, and audible to the
     * microphone through the speaker as well.
     */
    private fun startTone(): AudioTrack {
        // Nothing may mute the tone, because the tone is the evidence. The previous level is kept
        // because the device belongs to someone: a test does not get to leave the volume changed.
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            previousMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        }
        val frames = TONE_SAMPLE_RATE / 20
        val tone = ShortArray(frames) { frame ->
            (sin(2.0 * PI * TONE_HZ * frame / TONE_SAMPLE_RATE) * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TONE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(tone.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(tone, 0, tone.size)
        // Looping forever, so the tone covers the whole recording without a feeder thread.
        track.setLoopPoints(0, tone.size, -1)
        track.play()
        return track
    }

    private fun stopTone(track: AudioTrack) {
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    /**
     * Puts the media volume back where it was, whatever else happened.
     *
     * Separate from [stopTone] so the outer `finally` of a test can call it even on the path where
     * an assertion failed before the recording ever stopped: the device is not the test's to leave
     * with its volume changed.
     */
    private fun restoreMusicVolume() {
        previousMusicVolume?.let { level ->
            runCatching { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
        }
        previousMusicVolume = null
    }

    private fun cleanup(app: HaminnApplication, appId: String) {
        app.registry.deleteInstance(appId)
        app.installer.deleteAppFiles(appId)
        app.registry.finishDelete(appId)
    }

    private fun fireCapture(scenario: ActivityScenario<MainActivity>, maxEdge: Int = 2048) {
        val fired = evaluate(
            scenario,
            "window.__screenDone=null; window.__screenResult=null; " +
                "haminn.screen.capture({maxEdge:$maxEdge})" +
                ".then(x=>{window.__screenResult=JSON.stringify(x);window.__screenDone='ok'})" +
                ".catch(e=>{window.__screenResult='ERR:'+(e&&(e.code||e.message));window.__screenDone='err'}); 'started'",
        )
        assertEquals("started", fired)
    }

    private fun fireRecording(scenario: ActivityScenario<MainActivity>, durationMs: Long) {
        val fired = evaluate(
            scenario,
            "window.__screenDone=null; window.__screenResult=null; " +
                "haminn.screen.startRecording({audio:'both',maxDurationMs:$durationMs,scale:0.5,frameRate:30})" +
                ".then(x=>{window.__screenResult=JSON.stringify(x);window.__screenDone='ok'})" +
                ".catch(e=>{window.__screenResult='ERR:'+(e&&(e.code||e.message));window.__screenDone='err'}); 'started'",
        )
        assertEquals("started", fired)
    }

    private fun stopScript(recordingId: String) =
        "window.__screenDone=null; window.__screenResult=null; " +
            "haminn.screen.stopRecording({recordingId:'$recordingId'})" +
            ".then(x=>{window.__screenResult=JSON.stringify(x);window.__screenDone='ok'})" +
            ".catch(e=>{window.__screenResult='ERR:'+(e&&(e.code||e.message));window.__screenDone='err'}); 'started'"

    private fun filesOf(scenario: ActivityScenario<MainActivity>): List<JSONObject> {
        val raw = evaluateAsync(scenario, "haminn.files.list({}).then(x=>x.files)") ?: return emptyList()
        val array = org.json.JSONArray(raw)
        return (0 until array.length()).map(array::getJSONObject)
    }

    /**
     * The library names a file by its logical id, and that id is also the on-disk name — the
     * display name with its extension lives in the index, not on the filesystem.
     */
    private fun bytesOf(appId: String, logicalId: String): File? =
        File(context.filesDir, "instances/$appId")
            .walkTopDown()
            .firstOrNull { it.isFile && it.name == logicalId }

    /**
     * Copies a delivered file where the host can reach it.
     *
     * Not the app-specific *external* directory, which is the obvious choice: on this device
     * `/sdcard/Android/data` is denied to the shell user outright, so nothing could ever be
     * pulled from there. The app's own directory works because a debug build is `run-as`-able,
     * and it outlives the test since the instance is deleted but this copy is not.
     */
    private fun stageForHost(source: File, name: String): String? {
        val target = File(stagingDirectory(), name)
        return runCatching {
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            target.absolutePath
        }.getOrNull()
    }

    /** A measurement the host has to read back, written where `run-as` can find it. */
    private fun stageTextForHost(name: String, text: String): String? {
        val target = File(stagingDirectory(), name)
        return runCatching {
            target.writeText(text)
            target.absolutePath
        }.getOrNull()
    }

    private fun stagingDirectory(): File = File(context.filesDir, "screen-verification").apply { mkdirs() }

    /**
     * utime + stime of this process, in seconds.
     *
     * The recording runs in this very process -- the instrumentation shares the app's pid -- so
     * this is the cost of the feature itself rather than a whole-system reading that the test
     * runner's own work would pollute.
     */
    private fun cpuSeconds(): Double {
        val ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).toDouble()
        // Everything after the comm field, which may itself contain spaces and parentheses.
        val fields = File("/proc/self/stat").readText().substringAfterLast(") ").split(" ")
        return (fields[11].toLong() + fields[12].toLong()) / ticks
    }

    /** Runs [block] and reports what it cost as (process CPU seconds, wall seconds). */
    private fun measureCpu(block: () -> Unit): Pair<Double, Double> {
        val cpu = cpuSeconds()
        val started = SystemClock.elapsedRealtime()
        block()
        return (cpuSeconds() - cpu) to (SystemClock.elapsedRealtime() - started) / 1000.0
    }

    private fun displaySize(): IntArray {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)!!
            .getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Waits for the marker the fired promise writes.
     *
     * Reads are best-effort: [ActivityScenario.onActivity] touches the main thread, which may be
     * busy serving a system dialog, so a failed read is just a retry rather than an error.
     */
    private fun awaitMarker(scenario: ActivityScenario<MainActivity>, budgetMs: Long): String? {
        val deadline = System.currentTimeMillis() + budgetMs
        var lastFailure: String? = null
        while (System.currentTimeMillis() < deadline) {
            val value = runCatching { evaluate(scenario, "window.__screenResult") }.getOrElse { error ->
                lastFailure = error.message
                null
            }
            if (value != null) return value
            SystemClock.sleep(250)
        }
        return lastFailure?.let { "ERR:marker unreadable ($it)" }
    }

    private fun waitUntilReady(scenario: ActivityScenario<MainActivity>): Boolean {
        repeat(20) {
            if (evaluate(scenario, "String(!!(window.haminn&&window.haminn.isReady))") == "true") return true
            SystemClock.sleep(200)
        }
        return false
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String? {
        val latch = CountDownLatch(1)
        var decoded: String? = null
        scenario.onActivity { activity ->
            activity.evaluateForTest(script) { encoded ->
                decoded = if (encoded == null || encoded == "null") null else org.json.JSONTokener(encoded).nextValue()?.toString()
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return decoded
    }

    /** Best-effort read of a promise that is expected to settle quickly, with no dialog up. */
    private fun evaluateAsync(scenario: ActivityScenario<MainActivity>, promiseExpression: String): String? {
        evaluate(
            scenario,
            "window.__probe=null; ($promiseExpression).then(x=>window.__probe=JSON.stringify(x)).catch(e=>window.__probe='ERR:'+e); 'started'",
        )
        repeat(24) {
            val value = evaluate(scenario, "window.__probe")
            if (value != null) return value
            SystemClock.sleep(150)
        }
        return null
    }

    // ---------------------------------------------------------------- dialogs

    /**
     * The one UiAutomation this test holds, asked for once with the interactive-window flag so the
     * projection consent dialog is reachable as well as the page itself.
     *
     * Requesting it consistently matters: each distinct flag combination hands back a *different*
     * instance and disconnects the previous one, so mixing the default accessor with this one
     * would kill whichever channel was opened first.
     */
    private fun automation(): UiAutomation =
        InstrumentationRegistry.getInstrumentation().getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES or RETRIEVE_INTERACTIVE_WINDOWS,
        )

    private fun shell(command: String): String {
        ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand(command)).use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun shellQuiet(command: String) {
        runCatching { shell(command) }
    }

    /** A labelled node, the point that presses it, and whether it lives in Haminn's own window. */
    private class Widget(val label: String, val x: Int, val y: Int, val fromApp: Boolean)

    /**
     * One dialog to answer: the labels its button may carry, and which window it has to be in.
     *
     * The window matters because two different dialogs offer a button called 允许 — Haminn's own
     * grant prompt and the system projection prompt. Without it, a remembered grant would let the
     * first step press the *system* button and quietly turn a decline test into an accept test.
     */
    private class Step(val labels: List<String>, val ours: Boolean?)

    /**
     * Every labelled node currently on screen, read straight off the accessibility tree.
     *
     * Deliberately *not* via `uiautomator dump`: that command takes the one UiAutomation
     * connection this process may hold, so calling it from a test that then needs
     * `executeShellCommand` for its taps kills the very channel it was called from — the dump
     * succeeds, and every subsequent tap silently goes nowhere.
     */
    private fun widgets(): List<Widget> {
        val automation = automation()
        val bounds = Rect()
        val found = mutableListOf<Widget>()

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            // `text` first: a dialog button carries its label there. `contentDescription` is the
            // fallback for the icons some ROMs draw instead of words.
            val label = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (label != null) {
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    found += Widget(
                        label,
                        bounds.centerX(),
                        bounds.centerY(),
                        node.packageName?.toString() == context.packageName,
                    )
                }
            }
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }

        val roots = automation.windows
            .mapNotNull { window -> runCatching { window.root }.getOrNull() }
            .ifEmpty { listOfNotNull(runCatching { automation.rootInActiveWindow }.getOrNull()) }
        roots.forEach { visit(it, 0) }
        return found
    }

    /**
     * Clicks the next dialog in [steps], in order, by looking its button up in a fresh read of the
     * accessibility tree every time.
     *
     * Buttons are matched on the exact label a dialog uses, never on a remembered coordinate: the
     * same dialog sits somewhere else on the next ROM and moves whenever the layout changes.
     */
    private fun answerDialogs(steps: List<String>, budgetMs: Long = 30_000): List<String> {
        val deadline = System.currentTimeMillis() + budgetMs
        val answered = mutableListOf<String>()
        var lastWanted: Step? = null
        while (answered.size < steps.size && System.currentTimeMillis() < deadline) {
            val step = stepOf(steps[answered.size])
            lastWanted = step
            val hit = widgets().firstOrNull { widget ->
                step.labels.contains(widget.label) && (step.ours == null || step.ours == widget.fromApp)
            }
            if (hit == null) {
                SystemClock.sleep(400)
                continue
            }
            shellQuiet("input tap ${hit.x} ${hit.y}")
            answered += steps[answered.size]
            SystemClock.sleep(900)
        }
        if (answered.size < steps.size) {
            // What was on screen instead is the only useful evidence, and instrumentation's
            // stdout never reaches the JUnit report, so it travels in the assertion message.
            dialogDiagnosis = "wanted=${steps[answered.size]}${lastWanted?.labels} inHaminn=${lastWanted?.ours} " +
                "answered=$answered saw=${widgets().map { it.label }.take(24)}"
        }
        return answered
    }

    private fun stepOf(step: String): Step = when (step) {
        GRANT -> Step(listOf("允许"), ours = true)
        RUNTIME -> Step(listOf("仅在使用该应用时允许", "始终允许", "使用应用时允许", "允许"), ours = false)
        // The accept button is 立即开始 on AOSP and 允许 on this ROM; the reject button is 取消 on
        // AOSP and 禁止 on this ROM. Both spellings stay in the lists so the test is not tied to
        // one vendor's wording.
        PROJECTION_ACCEPT -> Step(listOf("立即开始", "开始录制", "开始投射", "开始", "确定", "允许"), ours = false)
        PROJECTION_DECLINE -> Step(listOf("取消", "禁止", "拒绝", "不允许"), ours = false)
        else -> Step(listOf(step), ours = null)
    }

    private fun appZip(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries = mapOf(
                "index.html" to "<!doctype html><meta charset=utf-8><title>Screen fixture</title><h1>Screen fixture</h1>",
                "haminn.json" to "{\"schema\":1,\"name\":\"Screen fixture\",\"version\":{\"code\":1,\"name\":\"1\"}}",
            )
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val GRANT = "haminn-grant"
        const val RUNTIME = "android-runtime"
        const val PROJECTION_ACCEPT = "projection-accept"
        const val PROJECTION_DECLINE = "projection-decline"
        const val TONE_HZ = 440.0
        const val TONE_SAMPLE_RATE = 48_000

        /** Long enough that the content is moving for nearly the whole measured window. */
        const val DRAG_MS = 4_000

        /**
         * `UiAutomation.FLAG_RETRIEVE_INTERACTIVE_WINDOWS`, which is not in the public SDK.
         *
         * Only used to make `windows` meaningful; if a ROM ignores it the list comes back empty
         * and the caller falls back to `rootInActiveWindow`, so the flag can never cost a result.
         */
        const val RETRIEVE_INTERACTIVE_WINDOWS = 0x00000002

        fun org.json.JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
        fun org.json.JSONArray.doubles(): List<Double> = (0 until length()).map { getDouble(it) }
    }
}
