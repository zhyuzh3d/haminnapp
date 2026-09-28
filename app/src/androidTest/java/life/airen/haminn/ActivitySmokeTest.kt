package life.airen.haminn

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.core.content.ContextCompat
import life.airen.haminn.launcher.ShortcutHost
import androidx.core.content.FileProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONTokener
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@LargeTest
class ActivitySmokeTest {
    @Test fun nativeHostStartsWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }

    @Test fun publicBridgeReportsRealSystemCapabilityDetails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Capabilities", 1)), "Capability fixture")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val result = evaluateAsync(scenario, "Promise.all([haminn.runtime.info(),haminn.runtime.capabilities(),haminn.speech.availability(),haminn.tts.availability(),haminn.sensors.availability(),haminn.wifi.status(),haminn.bluetooth.status(),haminn.infrared.status(),haminn.battery.status(),haminn.network.status(),haminn.camera.torchStatus()]).then(x=>({minor:x[0].apiMinor,names:x[1].capabilities.map(c=>c.name),speech:typeof x[2].streamingAvailable==='boolean'&&!('services'in x[2])&&!('providerManagedByUser'in x[2]),tts:typeof x[3].operational==='boolean'&&!('services'in x[3])&&!('engines'in x[3])&&!('providerManagedByUser'in x[3]),sensors:Array.isArray(x[4].sensors),wifi:typeof x[5].supported==='boolean',bluetooth:typeof x[6].supported==='boolean',infrared:typeof x[7].supported==='boolean',battery:typeof x[8].charging==='boolean',network:Array.isArray(x[9].transports),torch:typeof x[10].supported==='boolean'}))")
                assertTrue(result?.contains("\"minor\":14") == true)
                for (name in listOf("sensors", "wifi", "bluetooth", "infrared", "battery", "system")) {
                    assertTrue("Missing $name in $result", result?.contains("\"$name\"") == true)
                }
                for (field in listOf("speech", "tts", "sensors", "wifi", "bluetooth", "infrared", "battery", "network", "torch")) {
                    assertTrue("Capability probe failed: $field in $result", result?.contains("\"$field\":true") == true)
                }
            }
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun storeLoadsInjectedBridgeAndCompletesHandshake() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var state: JSONObject? = null
            repeat(12) {
                val latch = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.inspectRuntimeForTest { encoded ->
                        if (encoded != null && encoded != "null") {
                            val jsonText = JSONTokener(encoded).nextValue() as? String
                            if (jsonText != null) state = JSONObject(jsonText)
                        }
                        latch.countDown()
                    }
                }
                latch.await(2, TimeUnit.SECONDS)
                if (state?.optBoolean("ready") == true) return@repeat
                android.os.SystemClock.sleep(250)
            }
            assertEquals("Haminn 应用库", state?.optString("title"))
            assertTrue(state?.optBoolean("haminn") == true)
            assertTrue(state?.optBoolean("ready") == true)
            val licenses = evaluateAsync(scenario, "haminn.host.licenses.info({}).then(x=>({hasNano:x.text.includes('NanoHTTPD')}))")
            assertEquals("{\"hasNano\":true}", licenses)
        }
    }

    @Test fun pageReportsItsResolvedThemeToTheStatusBar() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val app = context.applicationContext as HaminnApplication
            val installed = app.installer.installZip(ByteArrayInputStream(appZip("Appearance", 1)), "Appearance fixture")
            try {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)).use { scenario ->
                    assertTrue(waitUntilReady(scenario))
                    assertEquals("{\"theme\":\"dark\",\"applied\":true}", evaluateAsync(scenario, "haminn.appearance.reportTheme({theme:'dark'})"))
                    scenario.onActivity { activity ->
                        assertEquals(ContextCompat.getColor(activity, R.color.haminn_status_default_dark), activity.window.statusBarColor)
                    }
                    assertEquals("{\"theme\":\"light\",\"applied\":true}", evaluateAsync(scenario, "haminn.appearance.reportTheme({theme:'light'})"))
                    scenario.onActivity { activity ->
                        assertEquals(ContextCompat.getColor(activity, R.color.haminn_status_default_light), activity.window.statusBarColor)
                    }
                }
            } finally {
                app.registry.deleteInstance(installed.appId)
                app.installer.deleteAppFiles(installed.appId)
                app.registry.finishDelete(installed.appId)
            }
        }
    }

    @Test fun sharedZipIsCopiedIntoANewLocalInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val before = app.registry.listInstances().map { it.appId }.toSet()
        val source = File(context.cacheDir, "shared/shared-${UUID.randomUUID()}.zip").apply {
            parentFile!!.mkdirs()
            writeBytes(appZip("Shared ZIP", 1))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        var createdId: String? = null
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.importSharedZipForTest(uri) }
                repeat(30) {
                    createdId = app.registry.listInstances().firstOrNull { candidate -> candidate.appId !in before }?.appId
                    if (createdId != null) return@repeat
                    android.os.SystemClock.sleep(150)
                }
                assertTrue(createdId != null)
                assertTrue(app.registry.getInstance(createdId!!)?.activeReleaseId != null)
            }
        } finally {
            createdId?.let { id ->
                app.registry.deleteInstance(id)
                app.installer.deleteAppFiles(id)
                app.registry.finishDelete(id)
            }
            source.delete()
        }
    }

    /**
     * Installing is the moment to want an icon on the home screen, so every user-facing
     * install path asks the launcher for one. The launcher still owns the tap, so a
     * request the user has not confirmed must not leave a shortcut behind — and the
     * pinned set is read first, which on this device means a real answer rather than
     * the "unknown" fallback that would make the request unconditional.
     */
    @Test fun installingAHappAsksForItsDesktopIconOnlyWhenMissing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val shortcuts = ShortcutHost(context)
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Pin fixture", 1)), "Pin fixture")
        try {
            val instance = requireNotNull(app.registry.getInstance(installed.appId))
            assertEquals(ShortcutHost.PinState.NOT_PINNED, shortcuts.pinStates(listOf(instance))[installed.appId])
            assertEquals(ShortcutHost.PinOutcome.REQUESTED, shortcuts.requestPinIfAbsent(instance))
            assertEquals(ShortcutHost.PinState.NOT_PINNED, shortcuts.pinStates(listOf(instance))[installed.appId])
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun localAppBridgePersistsDataAcrossCodeUpdate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val first = app.installer.installZip(ByteArrayInputStream(appZip("V1", 1)), "Bridge fixture")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, first.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val value = evaluateAsync(scenario, "haminn.data.put({collection:'test',key:'answer',value:{n:42},expectedRevision:'absent'})")
                assertTrue(value?.contains("revision") == true)
            }
            val second = app.installer.installZip(ByteArrayInputStream(appZip("V2", 2)), null, first.appId,
                expectedReleaseId = first.releaseId)
            assertFalse(first.releaseId == second.releaseId)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, first.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val value = evaluateAsync(scenario, "haminn.data.get({collection:'test',key:'answer'}).then(x=>x.value)")
                assertEquals("{\"n\":42}", value)
            }
        } finally {
            val removed = app.registry.deleteInstance(first.appId)
            app.installer.deleteAppFiles(first.appId)
            app.registry.finishDelete(first.appId)
            removed?.let { app.registry.completeProfileCleanup(it.webProfileName) }
        }
    }

    @Test fun desktopLaunchRecoversByHappIdentityAndFollowsCurrentCardMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val happId = "test.desktop.launch"
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response =
                newFixedLengthResponse("<!doctype html><meta charset=utf-8><title>Desktop live</title>")
        }
        server.start()
        val installed = app.installer.installZip(
            ByteArrayInputStream(identifiedAppZip("Desktop local", happId)),
            "Desktop launch",
            liveUrl = "http://127.0.0.1:${server.listeningPort}/",
        )
        val desktopIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_APP_ID, UUID.randomUUID().toString())
            .putExtra(MainActivity.EXTRA_HAPP_ID, happId)
        fun assertLaunch(runtimeMode: String, launchChannel: String) {
            ActivityScenario.launch<MainActivity>(desktopIntent).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val info = evaluateAsync(
                    scenario,
                    "haminn.app.info().then(x=>({appId:x.appId,runtimeMode:x.runtimeMode,launchChannel:x.launchChannel}))",
                )
                assertTrue("Unexpected desktop launch info: $info", info?.contains("\"appId\":\"${installed.appId}\"") == true)
                assertTrue("Unexpected desktop launch info: $info", info?.contains("\"runtimeMode\":\"$runtimeMode\"") == true)
                assertTrue("Unexpected desktop launch info: $info", info?.contains("\"launchChannel\":\"$launchChannel\"") == true)
            }
        }
        try {
            assertLaunch("local", "stable")
            app.registry.setRuntimeMode(installed.appId, life.airen.haminn.model.HappRuntimeMode.LIVE)
            assertLaunch("live", "stable")
            app.devWorkspaces.enter(installed.appId)
            assertLaunch("local", "dev")
        } finally {
            server.stop()
            app.devWorkspaces.delete(installed.appId)
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun onlinePageUsesSameOriginOfflineIconsWithoutRequestingThemFromServer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val iconRequests = java.util.concurrent.atomic.AtomicInteger()
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                if (request.uri.startsWith("/__haminn/")) iconRequests.incrementAndGet()
                return newFixedLengthResponse("<!doctype html><meta charset=utf-8><title>Online icon fixture</title><i class='fa-solid fa-heart'></i>").apply {
                    addHeader("Content-Security-Policy", "default-src 'self'; style-src 'self'; font-src 'self'")
                }
            }
        }
        server.start()
        val instance = life.airen.haminn.model.WebAppInstance.newOnlineLive("Online icon fixture", "http://127.0.0.1:${server.listeningPort}/")
        app.registry.insertInstance(instance)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, instance.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val result = evaluateAsync(scenario, "haminn.icons.load().then(()=>document.fonts.load('900 16px \\\"Font Awesome 7 Free\\\"')).then(fonts=>fonts.length>0)")
                assertEquals("true", result)
                assertEquals(0, iconRequests.get())
            }
        } finally {
            server.stop()
            app.registry.deleteInstance(instance.appId)
            app.registry.finishDelete(instance.appId)
        }
    }

    private fun waitUntilReady(scenario: ActivityScenario<MainActivity>): Boolean {
        repeat(20) {
            val value = evaluate(scenario, "String(!!(window.haminn&&window.haminn.isReady))")
            if (value == "true") return true
            android.os.SystemClock.sleep(200)
        }
        return false
    }

    @Test fun storeIconsSearchAndModalBackWorkWithoutFrontendFrameworks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue(waitUntilReady(scenario))
            val font = evaluateAsync(scenario, "haminn.icons.load().then(()=>document.fonts.load('900 16px \\\"Font Awesome 7 Free\\\"')).then(fonts=>({loaded:fonts.length>0,family:getComputedStyle(document.querySelector('#addFolder .fa-folder-open')).fontFamily}))")
            assertTrue("Font result: $font", font?.contains("\"loaded\":true") == true)
            assertTrue(font?.contains("Font Awesome 7 Free") == true)
            assertFalse(evaluate(scenario, "getComputedStyle(document.querySelector('.fa-plus'),'::before').content") in setOf("none", "normal", "\"\""))
            evaluate(scenario, "document.querySelector('#addUrl').click(); 'opened'")
            assertEquals("true", evaluate(scenario, "String(document.querySelector('#shell').inert && !document.querySelector('#addPanel').classList.contains('hidden'))"))
            evaluate(scenario, "String(window.haminnStoreBack())")
            repeat(10) { if (evaluate(scenario, "String(document.querySelector('#addPanel').classList.contains('hidden'))") != "true") android.os.SystemClock.sleep(100) }
            assertEquals("false", evaluate(scenario, "String(document.querySelector('#shell').inert)"))
            evaluate(scenario, "document.querySelector('[data-view=icons]').click(); 'icons'")
            repeat(20) { if (evaluate(scenario, "String(!!window.haminnIconCatalog)") != "true") android.os.SystemClock.sleep(100) }
            assertEquals("2883", evaluate(scenario, "String(window.haminnIconCatalog.icons.length)"))
            evaluate(scenario, "const q=document.querySelector('#searchIcons'); q.value='相机'; q.dispatchEvent(new Event('input')); 'searched'")
            assertEquals("true", evaluate(scenario, "String(document.querySelectorAll('.catalog-icon').length>0 && [...document.querySelectorAll('.catalog-icon')].some(x=>x.textContent.includes('camera')))"))
            evaluate(scenario, "document.querySelector('[data-view=settings]').click();document.querySelector('[data-theme-choice=dark]').click(); 'dark'")
            assertEquals("dark", evaluate(scenario, "document.documentElement.dataset.theme"))
            evaluate(scenario, "document.querySelector('[data-theme-choice=system]').click(); 'reset'")
        }
    }

    @Test fun nativeHtmlLocalPageGetsAllFontStylesWithoutLinkOrBuild() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Icons", 1)), "Icon fixture")
        try {
            assertEquals("Icon fixture", app.registry.getInstance(installed.appId)?.name)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val result = evaluateAsync(scenario, "haminn.icons.load().then(()=>Promise.all([document.fonts.load('900 16px \\\"Font Awesome 7 Free\\\"'),document.fonts.load('400 16px \\\"Font Awesome 7 Free\\\"'),document.fonts.load('400 16px \\\"Font Awesome 7 Brands\\\"')])).then(xs=>xs.map(x=>x.length>0))")
                assertEquals("[true,true,true]", result)
                val styles = evaluate(scenario, "const icon=haminn.icons.create('heart',{style:'regular',label:'喜欢'});document.body.append(icon);JSON.stringify({family:getComputedStyle(icon).fontFamily,label:icon.getAttribute('aria-label')})")
                assertTrue(styles?.contains("Font Awesome 7 Free") == true)
                assertTrue(styles?.contains("喜欢") == true)
            }
            // Cancel a deletion in the Store and prove the instance/data identity survives.
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                repeat(20) { if (evaluate(scenario, "String(document.querySelectorAll('.manage').length)") == "0") android.os.SystemClock.sleep(100) }
                evaluate(scenario, "[...document.querySelectorAll('.app-card')].find(x=>x.textContent.includes('Icon fixture')).querySelector('.manage').click(); 'manage'")
                evaluate(scenario, "document.querySelector('#removeApp').click(); 'confirm'")
                assertEquals("false", evaluate(scenario, "String(document.querySelector('#confirmPanel').classList.contains('hidden'))"))
                evaluate(scenario, "document.querySelector('#cancelConfirm').click(); 'cancelled'")
                assertTrue(app.registry.getInstance(installed.appId) != null)
                assertEquals("true", evaluate(scenario, "String(document.querySelector('#confirmPanel').classList.contains('hidden'))"))
            }
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun developmentTabShowsAndRotatesOnlyTheCurrentPassword() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue(waitUntilReady(scenario))
            evaluate(scenario, "document.querySelector('[data-view=development]').click();'opened'")
            for (i in 0 until 30) {
                if (evaluate(scenario, "String(/^[0-9]{6}$/.test(document.querySelector('#agentPassword').value))") == "true") break
                android.os.SystemClock.sleep(100)
            }
            assertEquals("true", evaluate(scenario, "String(/^[0-9]{6}$/.test(document.querySelector('#agentPassword').value))"))
            val previous = app.agentServer.passwordForUi()
            val replacement = if (previous == "654321") "123456" else "654321"
            evaluate(scenario, "const p=document.querySelector('#agentPassword');p.value='$replacement';p.dispatchEvent(new Event('input'));document.querySelector('#saveAgentPassword').click();'rotate'")
            for (i in 0 until 20) { if (previous != app.agentServer.passwordForUi()) break; android.os.SystemClock.sleep(100) }
            assertEquals(replacement, app.agentServer.passwordForUi())
            assertEquals("true", evaluateAsync(scenario, "haminn.host.agent.status().then(s=>s.password===document.querySelector('#agentPassword').value)"))
        }
    }

    @Test fun favoritesAndFiveTabNavigationStayAlignedAndConsistent() {
        val app = ApplicationProvider.getApplicationContext<HaminnApplication>()
        val instance = life.airen.haminn.model.WebAppInstance.newOnlineLive("Favorite fixture", "https://example.com")
        app.registry.insertInstance(instance)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                repeat(20) {
                    if (evaluate(scenario, "String(!!document.querySelector(\"[data-app-id='${instance.appId}']\"))") == "true") return@repeat
                    android.os.SystemClock.sleep(100)
                }
                assertEquals("true", evaluate(scenario, "String(document.querySelector(\"[data-app-id='${instance.appId}']\").classList.contains('hidden'))"))
                evaluate(scenario, "document.querySelector('.bottom-nav [data-view=all]').click();'all'")
                assertEquals("false", evaluate(scenario, "String(document.querySelector(\"[data-app-id='${instance.appId}']\").classList.contains('hidden'))"))
                evaluate(scenario, "document.querySelector(\"[data-app-id='${instance.appId}'] .favorite\").click();'favorite'")
                repeat(20) { if (app.registry.getInstance(instance.appId)?.favorite != true) android.os.SystemClock.sleep(100) }
                assertTrue(app.registry.getInstance(instance.appId)?.favorite == true)
                evaluate(scenario, "document.querySelector('.bottom-nav [data-view=favorites]').click();'favorites'")
                assertEquals("false", evaluate(scenario, "String(document.querySelector(\"[data-app-id='${instance.appId}']\").classList.contains('hidden'))"))
                val geometry = evaluate(scenario, "JSON.stringify((()=>{const n=[...document.querySelectorAll('.bottom-nav button')];return {count:n.length,widths:n.map(x=>Math.round(x.getBoundingClientRect().width)),offsets:n.map(x=>{const a=x.getBoundingClientRect(),b=x.querySelector('.nav-icon').getBoundingClientRect();return Math.round((a.left+a.width/2)-(b.left+b.width/2))})}})())")
                assertTrue(geometry?.contains("\"count\":5") == true)
                assertTrue(geometry?.contains("\"offsets\":[0,0,0,0,0]") == true)
                assertEquals("\"10knet·zhyuzh3d\"", evaluateAsync(scenario, "haminn.host.about.info({}).then(x=>x.author)"))
                evaluate(scenario, "window.haminnShellUnavailable('offline test');'fallback'")
                assertEquals("offline test", evaluate(scenario, "document.querySelector('#noticeText').textContent"))
            }
        } finally {
            app.registry.deleteInstance(instance.appId)
            app.registry.finishDelete(instance.appId)
        }
    }

    @Test fun switchingDeveloperModesAndPasswordResetCloseLegacyListener() {
        val app = ApplicationProvider.getApplicationContext<HaminnApplication>()
        val installed = runBlocking { app.installer.installZip(ByteArrayInputStream(appZip("Mode switch", 1)), "Mode switch") }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                val address = app.agentServer.addresses().firstOrNull()
                org.junit.Assume.assumeTrue(address != null)
                app.developmentServer.start(installed.appId, "adb")
                assertEquals("true", evaluateAsync(scenario, "haminn.host.agent.start({address:${JSONObject.quote(address)}}).then(x=>x.active)"))
                assertFalse(app.developmentServer.status().optBoolean("active"))
                assertTrue(app.agentServer.status().optBoolean("active"))
                evaluateAsync(scenario, "haminn.host.deploy.start({appId:${JSONObject.quote(installed.appId)},mode:'adb'}).then(()=>true)")
                assertFalse(app.agentServer.status().optBoolean("active"))
                assertTrue(app.developmentServer.status().optBoolean("active"))
                assertEquals("true", evaluateAsync(scenario, "haminn.host.agent.resetPassword().then(()=>true)"))
                assertFalse(app.developmentServer.status().optBoolean("active"))
            }
        } finally {
            app.agentServer.stop("Test finished"); app.developmentServer.stop("Test finished")
            app.registry.deleteInstance(installed.appId); app.installer.deleteAppFiles(installed.appId); app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun bringingAnUnchangedTargetToTheFrontKeepsTheRunningPage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val first = app.installer.installZip(ByteArrayInputStream(appZip("Front fixture", 1)), "Front fixture")
        val second = app.installer.installZip(ByteArrayInputStream(appZip("Other fixture", 1)), "Other fixture")
        try {
            val target = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, first.appId)
            ActivityScenario.launch<MainActivity>(target).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                assertEquals("marked", evaluate(scenario, "window.__haminnFront='marked'"))
                // A launcher tap and "intoExisting" both re-deliver the entry intent of a
                // task that is already open. That is not a navigation request.
                scenario.onActivity { activity ->
                    activity.deliverIntentForTest(
                        Intent(activity, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, first.appId)
                    )
                }
                assertEquals("marked", evaluate(scenario, "window.__haminnFront"))
                // Returning from the background resumes the same page instead of reloading it.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                assertEquals("marked", evaluate(scenario, "window.__haminnFront"))
                // A genuinely different target still takes over the runtime.
                scenario.onActivity { activity ->
                    activity.deliverIntentForTest(
                        Intent(activity, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, second.appId)
                    )
                }
                assertTrue(waitUntilReady(scenario))
                assertNull(evaluate(scenario, "window.__haminnFront"))
                assertEquals("Other fixture", evaluate(scenario, "document.title"))
            }
        } finally {
            for (id in listOf(first.appId, second.appId)) {
                app.registry.deleteInstance(id)
                app.installer.deleteAppFiles(id)
                app.registry.finishDelete(id)
            }
        }
    }

    @Test fun relaunchingHaminnKeepsTheLibraryPage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Store return fixture", 1)), "Store return fixture")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                assertEquals("marked", evaluate(scenario, "window.__haminnFront='marked'"))
                scenario.onActivity { activity ->
                    activity.deliverIntentForTest(Intent(activity, MainActivity::class.java))
                }
                assertEquals("marked", evaluate(scenario, "window.__haminnFront"))
                // Opening a happ from the library still replaces the library runtime.
                scenario.onActivity { activity ->
                    activity.deliverIntentForTest(
                        Intent(activity, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)
                    )
                }
                assertTrue(waitUntilReady(scenario))
                assertNull(evaluate(scenario, "window.__haminnFront"))
            }
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun aTappedNotificationReachesThePageThatIsAlreadyOpen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Notice fixture", 1)), "Notice fixture")
        try {
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)
            ).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                assertEquals(
                    "listening",
                    evaluate(scenario, "window.__haminnOpened='none'; haminn.on('notifications.opened', d=>window.__haminnOpened=d.id); 'listening'"),
                )
                assertEquals("marked", evaluate(scenario, "window.__haminnFront='marked'"))
                scenario.onActivity { activity ->
                    activity.deliverIntentForTest(
                        Intent(activity, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_APP_ID, installed.appId)
                            .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, "notice-1")
                    )
                }
                repeat(20) {
                    if (evaluate(scenario, "window.__haminnOpened") != "notice-1") android.os.SystemClock.sleep(150)
                }
                assertEquals("notice-1", evaluate(scenario, "window.__haminnOpened"))
                // The payload arrives without rebuilding the page the user is looking at.
                assertEquals("marked", evaluate(scenario, "window.__haminnFront"))
            }
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    @Test fun aPageTransfersMoreBytesThanOneBridgeMessageAllows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(appZip("Channel fixture", 1)), "Channel fixture")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_APP_ID, installed.appId)).use { scenario ->
                assertTrue(waitUntilReady(scenario))
                // 400 KiB is past the 256 KiB single-message cap: before the chunked
                // channel existed this request was dropped by the transport without a
                // reply, so the page could only wait for its own timeout.
                evaluate(scenario, """
                    window.__haminnChannel=null; (async()=>{
                      const total=400*1024, bytes=new Uint8Array(total);
                      for(let i=0;i<total;i++) bytes[i]=(i*31+7)&255;
                      const write=await haminn.files.beginWrite({name:'payload.bin',mime:'application/octet-stream'});
                      let chunks=0;
                      for(let at=0;at<total;at+=write.maxChunkBytes){
                        const slice=bytes.subarray(at,at+write.maxChunkBytes);
                        let binary='';
                        for(const byte of slice) binary+=String.fromCharCode(byte);
                        chunks++;
                        await haminn.files.appendBytes({writeId:write.writeId,chunkBase64:btoa(binary)});
                      }
                      const file=await haminn.files.finishWrite({writeId:write.writeId});
                      return {chunks:chunks,size:file.size,sha256:file.sha256,maxChunk:write.maxChunkBytes};
                    })().then(x=>window.__haminnChannel=JSON.stringify(x)).catch(e=>window.__haminnChannel='ERR:'+(e.code||e.message)); 'started'
                """.trimIndent())
                val written = evaluateUntil(scenario, "window.__haminnChannel", 30_000)
                assertTrue("Chunked write failed: $written", written != null && !written.startsWith("ERR:"))
                val stored = JSONObject(written!!)
                assertEquals(400 * 1024, stored.getInt("size"))
                assertEquals(7, stored.getInt("chunks"))
                assertEquals(65_536, stored.getInt("maxChunk"))
                val expected = ByteArray(400 * 1024).also { bytes -> for (index in bytes.indices) bytes[index] = ((index * 31 + 7) and 0xff).toByte() }
                assertEquals(
                    MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) },
                    stored.getString("sha256"),
                )
                // The cap still exists, but it is now an answer instead of a silence. The
                // text must be the transport's own refusal, not the 256 KiB rule inside
                // writeText, which a raised transport cap would hide behind.
                evaluate(scenario, "window.__haminnChannel=null; haminn.files.writeText({name:'too-big.txt',text:'x'.repeat(300*1024)}).then(()=>window.__haminnChannel='accepted').catch(e=>window.__haminnChannel='ERR:'+e.code+'|'+String(e.message).slice(0,40)); 'started'")
                val rejected = evaluateUntil(scenario, "window.__haminnChannel", 20_000)
                assertTrue("Oversized message was not refused by the transport: $rejected", rejected?.startsWith("ERR:E_QUOTA|消息有") == true)
                // Boundary: a message under the cap is still delivered unchanged.
                val fits = evaluateAsync(scenario, "haminn.files.writeText({name:'fits.txt',text:'y'.repeat(200*1024)}).then(f=>({size:f.size})).catch(e=>({error:e.code}))")
                assertTrue("Under-cap write failed: $fits", fits?.contains("\"size\":204800") == true)
            }
        } finally {
            app.registry.deleteInstance(installed.appId)
            app.installer.deleteAppFiles(installed.appId)
            app.registry.finishDelete(installed.appId)
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String? {
        val latch = CountDownLatch(1)
        var decoded: String? = null
        scenario.onActivity { activity -> activity.evaluateForTest(script) { encoded ->
            decoded = if (encoded == null || encoded == "null") null else JSONTokener(encoded).nextValue()?.toString()
            latch.countDown()
        } }
        latch.await(3, TimeUnit.SECONDS)
        return decoded
    }

    private fun evaluateAsync(scenario: ActivityScenario<MainActivity>, promiseExpression: String): String? {
        evaluate(scenario, "window.__haminnTestResult=null; ($promiseExpression).then(x=>window.__haminnTestResult=JSON.stringify(x)).catch(e=>window.__haminnTestResult='ERR:'+e.code); 'started'")
        repeat(20) {
            val value = evaluate(scenario, "window.__haminnTestResult")
            if (value != null) return value
            android.os.SystemClock.sleep(150)
        }
        return null
    }

    /** Polls a JS marker for longer than [evaluateAsync]'s fixed window; a chunked upload needs it. */
    private fun evaluateUntil(scenario: ActivityScenario<MainActivity>, script: String, budgetMs: Long): String? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            val value = evaluate(scenario, script)
            if (value != null) return value
            android.os.SystemClock.sleep(250)
        }
        return null
    }

    private fun appZip(label: String, version: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries = mapOf(
                "index.html" to "<!doctype html><meta charset=utf-8><title>$label</title><h1>$label</h1>",
                "haminn.json" to "{\"schema\":1,\"name\":\"Bridge fixture\",\"version\":{\"code\":$version,\"name\":\"$version\"}}",
            )
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        return bytes.toByteArray()
    }

    private fun identifiedAppZip(label: String, happId: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries = mapOf(
                "index.html" to "<!doctype html><meta charset=utf-8><title>$label</title><h1>$label</h1>",
                "haminn.json" to """{"schema":2,"happId":"$happId","name":"$label","version":{"code":1,"name":"1"},"entry":"index.html","routing":"hash"}""",
            )
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
