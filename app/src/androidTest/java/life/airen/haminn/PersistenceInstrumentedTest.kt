package life.airen.haminn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import life.airen.haminn.data.FileStore
import life.airen.haminn.data.RecordsStore
import life.airen.haminn.backup.BackupCoordinator
import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.install.IdentityInstallChoice
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HappRuntimeMode
import life.airen.haminn.model.HappSource
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import life.airen.haminn.capability.NativeHttpClient
import life.airen.haminn.runtime.LocalContentGateway
import life.airen.haminn.runtime.RuntimeNetworkPolicy
import android.net.Uri
import android.webkit.WebResourceRequest
import life.airen.haminn.model.WebAppInstance
import life.airen.haminn.notification.NotificationSpec
import life.airen.haminn.notification.Recurrence

@RunWith(AndroidJUnit4::class)
class PersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val createdApps = mutableListOf<String>()

    @After fun cleanup() {
        val app = context.applicationContext as HaminnApplication
        createdApps.forEach { id ->
            java.io.File(context.filesDir, "instances/$id").deleteRecursively()
            app.registry.deleteInstance(id)?.let { deleted ->
                app.registry.finishDelete(id)
                app.registry.completeProfileCleanup(deleted.webProfileName)
            }
            app.registry.pendingProfileCleanup().filter { it.second == id }
                .forEach { app.registry.completeProfileCleanup(it.first) }
        }
    }

    @Test fun recordsProvideCasAtomicBatchAndStableExport() {
        val store = RecordsStore(context)
        val appId = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        createdApps += appId
        val first = store.put(appId, generation, "notes", "a", JSONObject().put("text", "one"), "absent")
        assertEquals("one", store.get(appId, generation, "notes", "a")!!.getJSONObject("value").getString("text"))
        val conflict = assertThrows(HaminnException::class.java) {
            store.put(appId, generation, "notes", "a", "two", "absent")
        }
        assertEquals(ErrorCodes.CONFLICT, conflict.code)
        val operations = JSONArray()
            .put(JSONObject().put("op", "put").put("collection", "notes").put("key", "a").put("value", "two").put("expectedRevision", first.getString("revision")))
            .put(JSONObject().put("op", "put").put("collection", "notes").put("key", "b").put("value", "three").put("expectedRevision", "absent"))
        store.batch(appId, generation, operations)
        val exported = ByteArrayOutputStream()
        assertEquals(2, store.exportJsonLines(appId, generation, exported))
        val secondGeneration = UUID.randomUUID().toString()
        assertEquals(2, store.importJsonLines(appId, secondGeneration, ByteArrayInputStream(exported.toByteArray())))
        assertEquals("three", store.get(appId, secondGeneration, "notes", "b")!!.getString("value"))
    }

    @Test fun logicalFilesRoundTripWithoutExternalPathDependency() {
        val store = FileStore(context)
        val appId = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        createdApps += appId
        val saved = store.import(appId, generation, "hello".byteInputStream(), "hello.txt", "text/plain")
        assertEquals("/__haminn/files/${saved.getString("logicalFileId")}", saved.getString("url"))
        assertTrue(File(context.filesDir, "instances/$appId/data/$generation/files/${saved.getString("logicalFileId")}").isFile)
        assertEquals("hello", store.readText(appId, generation, saved.getString("logicalFileId")).getString("text"))
    }

    @Test fun onlineOriginChangeRotatesScopedTrustButKeepsSharedWebProfile() {
        val app = context.applicationContext as HaminnApplication
        val instance = WebAppInstance.newOnlineLive("Online fixture", "https://example.test/one")
        app.registry.insertInstance(instance)
        createdApps += instance.appId

        app.registry.updateInstance(instance.appId, instance.name, "https://example.test/two", null)
        val sameOrigin = app.registry.getInstance(instance.appId)!!
        assertEquals(instance.trustRevision, sameOrigin.trustRevision)
        assertEquals(instance.webProfileName, sameOrigin.webProfileName)

        app.registry.updateInstance(instance.appId, instance.name, "https://other.test/", null)
        val changed = app.registry.getInstance(instance.appId)!!
        assertEquals(instance.trustRevision + 1, changed.trustRevision)
        assertEquals(instance.webProfileName, changed.webProfileName)

        app.installer.recoverStorage()
        assertNotNull(app.registry.getInstance(instance.appId))
    }

    @Test fun happSourceAndRuntimeModeAreIndependent() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val local = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "local"))), "Local happ")
        createdApps += local.appId
        val localInstance = app.registry.getInstance(local.appId)!!
        assertEquals(HappSource.LOCAL, localInstance.source)
        assertEquals(HappRuntimeMode.LOCAL, localInstance.runtimeMode)
        assertFalse(localInstance.toJson().getBoolean("liveAvailable"))
        assertThrows(IllegalStateException::class.java) {
            app.registry.setRuntimeMode(local.appId, HappRuntimeMode.LIVE)
        }

        val onlineLocal = app.installer.installZip(
            ByteArrayInputStream(zipOf(mapOf("index.html" to "cached"))),
            "Online packaged happ",
            provenance = "online-manifest",
            source = HappSource.ONLINE,
            liveUrl = "https://example.test/app/",
        )
        createdApps += onlineLocal.appId
        val packaged = app.registry.getInstance(onlineLocal.appId)!!
        assertEquals(HappSource.ONLINE, packaged.source)
        assertEquals(HappRuntimeMode.LOCAL, packaged.runtimeMode)
        assertTrue(packaged.toJson().getBoolean("localAvailable"))
        assertTrue(packaged.toJson().getBoolean("liveAvailable"))

        val live = app.registry.setRuntimeMode(onlineLocal.appId, HappRuntimeMode.LIVE)
        assertEquals(HappRuntimeMode.LIVE, live.runtimeMode)
        assertEquals("https://example.test/app/", live.startUrl)
        assertEquals(packaged.trustRevision, live.trustRevision)

        val localAgain = app.registry.setRuntimeMode(onlineLocal.appId, HappRuntimeMode.LOCAL)
        assertEquals(HappRuntimeMode.LOCAL, localAgain.runtimeMode)
        assertEquals("https://example.test/app/", localAgain.startUrl)
    }

    @Test fun onlineUrlUsesSameOriginManifestAndDefaultsToLocalRuntime() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val archive = zipOf(mapOf(
            "index.html" to "<!doctype html><title>Downloaded happ</title>",
            "haminn.json" to "{\"schema\":1,\"name\":\"Downloaded happ\",\"version\":{\"name\":\"2.0.0\"}}",
        ))
        val sha = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/haminn-install.json" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    "{\"schema\":1,\"package\":\"happ.zip\",\"sha256\":\"$sha\"}",
                )
                "/happ.zip" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/zip",
                    ByteArrayInputStream(archive),
                    archive.size.toLong(),
                )
                else -> newFixedLengthResponse(Response.Status.OK, "text/html", "live")
            }
        }
        server.start()
        try {
            val pageUrl = "http://127.0.0.1:${server.listeningPort}/page"
            val installed = app.remoteInstaller.installOnline(pageUrl, null)
            createdApps += installed.appId
            val instance = app.registry.getInstance(installed.appId)!!
            assertEquals("local", installed.strategy)
            assertEquals(HappSource.ONLINE, instance.source)
            assertEquals(HappRuntimeMode.LOCAL, instance.runtimeMode)
            assertEquals(pageUrl, instance.liveUrl)
            assertNotNull(instance.activeReleaseId)
            assertEquals("2.0.0", app.registry.getRelease(instance.activeReleaseId!!)!!.versionName)
        } finally {
            server.stop()
        }
    }

    @Test fun onlineEntryClassifiesLivePagesAndInstallDescriptors() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val archive = zipOf(mapOf(
            "index.html" to "<!doctype html><title>Direct package</title>",
            "haminn.json" to "{\"schema\":1,\"name\":\"Direct package\",\"version\":{\"name\":\"3.0.0\"}}",
        ))
        val sha = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/direct.zip", "/catalog/direct.zip" -> newFixedLengthResponse(
                    Response.Status.OK, "application/zip", ByteArrayInputStream(archive), archive.size.toLong()
                )
                "/catalog/haminn-install.json" -> newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    "{\"schema\":1,\"package\":\"direct.zip\",\"sha256\":\"$sha\"}",
                )
                "/haminn-install.json" -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
                else -> newFixedLengthResponse(Response.Status.OK, "text/html", "<!doctype html><title>Live page</title>")
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            val descriptor = app.remoteInstaller.installOnline("$base/catalog/haminn-install.json", null)
            createdApps += descriptor.appId
            val descriptorApp = app.registry.getInstance(descriptor.appId)!!
            assertEquals("descriptor", descriptor.kind)
            assertEquals("online-descriptor", descriptorApp.sourceAdapter)
            assertNotNull(descriptorApp.activeReleaseId)
            assertEquals("clean", app.devWorkspaces.enter(descriptor.appId).getString("state"))

            val live = app.remoteInstaller.installOnline("$base/page", null)
            createdApps += live.appId
            val liveApp = app.registry.getInstance(live.appId)!!
            assertEquals("live", live.kind)
            assertEquals(HappRuntimeMode.LIVE, liveApp.runtimeMode)
            assertEquals(null, liveApp.activeReleaseId)
            val error = assertThrows(HaminnException::class.java) { app.devWorkspaces.enter(live.appId) }
            assertEquals(ErrorCodes.CONFLICT, error.code)
        } finally {
            server.stop()
        }
    }

    @Test fun systemPermissionObservationsPersistLatestKnownState() {
        val registry = (context.applicationContext as HaminnApplication).registry
        val permission = "life.airen.haminn.TEST_PERMISSION"
        registry.observeSystemPermission(permission, false)
        registry.observeSystemPermission(permission, true)
        val item = registry.systemPermissionsJson().let { items ->
            (0 until items.length()).map(items::getJSONObject).first { it.getString("permission") == permission }
        }
        assertTrue(item.getBoolean("granted"))
        assertTrue(item.getLong("observedAt") > 0)
    }

    @Test fun restartRecoveryKeepsOnlyCommittedStorageAndTerminatesOperations() {
        val app = context.applicationContext as HaminnApplication
        val installed = runBlocking { app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "ok"))), "Recovery fixture") }
        createdApps += installed.appId
        val instance = app.registry.getInstance(installed.appId)!!
        val appRoot = java.io.File(context.filesDir, "instances/${installed.appId}")
        val incoming = java.io.File(appRoot, "incoming/stale.zip").apply { parentFile!!.mkdirs(); writeText("partial") }
        val orphanRelease = java.io.File(appRoot, "releases/${UUID.randomUUID()}/web").apply { mkdirs(); resolve("index.html").writeText("orphan") }
        val orphanData = java.io.File(appRoot, "data/${UUID.randomUUID()}").apply { mkdirs(); resolve("partial").writeText("orphan") }
        val operation = app.registry.createOperation(installed.appId, "install-release", "recovery-operation", "abc", installed.releaseId)
        app.registry.updateOperation(operation, "committing")
        app.registry.recoverInterruptedOperations()
        app.installer.recoverStorage()
        assertFalse(incoming.exists())
        assertFalse(orphanRelease.parentFile!!.exists())
        assertFalse(orphanData.exists())
        assertTrue(java.io.File(appRoot, "releases/${installed.releaseId}/web/index.html").isFile)
        assertEquals(instance.activeDataGeneration, app.registry.getInstance(installed.appId)!!.activeDataGeneration)
        assertEquals("failed", app.registry.operationJson(operation)!!.getString("state"))
    }

    @Test fun packageInstallCommitsValidZipAndRejectsTraversal() = runBlocking {
        val registry = (context.applicationContext as HaminnApplication).registry
        val installer = InstallCoordinator(context, registry)
        val valid = zipOf(mapOf("index.html" to "<h1>ok</h1>", "haminn.json" to "{\"schema\":1,\"name\":\"Test\"}"))
        val result = installer.installZip(ByteArrayInputStream(valid), null)
        createdApps += result.appId
        assertNotNull(registry.getInstance(result.appId)?.activeReleaseId)
        val updatedBytes = zipOf(mapOf(
            "start.html" to "<h1>v2</h1>",
            "haminn.json" to "{\"schema\":1,\"entry\":\"start.html\"}",
        ))
        val updated = installer.installZip(ByteArrayInputStream(updatedBytes), null, result.appId,
            idempotencyKey = "install-idempotency-v2", expectedReleaseId = result.releaseId)
        assertEquals(registry.getInstance(result.appId)!!.localUrl, registry.getInstance(result.appId)!!.runtimeUrl)
        assertEquals("start.html", registry.getRelease(updated.releaseId)!!.entryPath)
        val replayed = installer.installZip(ByteArrayInputStream(updatedBytes), null, result.appId,
            idempotencyKey = "install-idempotency-v2", expectedReleaseId = result.releaseId)
        assertEquals(updated.releaseId, replayed.releaseId)
        val reactivated = installer.installZip(ByteArrayInputStream(valid), null, result.appId,
            expectedReleaseId = updated.releaseId)
        assertEquals(result.releaseId, reactivated.releaseId)
        assertEquals(result.releaseId, registry.getInstance(result.appId)?.activeReleaseId)
        assertEquals("index.html", registry.getRelease(result.releaseId)!!.entryPath)
        val invalid = zipOf(mapOf("../escape.txt" to "bad"))
        val error = assertThrows(HaminnException::class.java) { runBlocking { installer.installZip(ByteArrayInputStream(invalid), null) } }
        assertEquals(ErrorCodes.INVALID_ARGUMENT, error.code)
        assertTrue(!java.io.File(context.filesDir.parentFile, "escape.txt").exists())
    }

    @Test fun schemaTwoManifestCreatesStableIdentityAndUrls() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val manifest = """{"schema":2,"happId":"com.example.notes","name":"Notes","version":{"code":2,"name":"2.0.0"},"entry":"web/index.html","routing":"history","icon":"assets/icon.png","liveUrl":"https://example.test/apps/notes/","updateUrl":"https://example.test/apps/notes/latest.json"}"""
        val installed = app.installer.installZip(ByteArrayInputStream(zipBytesOf(mapOf(
            "haminn.json" to manifest.toByteArray(),
            "web/index.html" to "<h1>notes</h1>".toByteArray(),
            "assets/icon.png" to pngIcon(96, 48, android.graphics.Color.BLUE),
        ))), null)
        createdApps += installed.appId
        val instance = app.registry.getInstance(installed.appId)!!
        val release = app.registry.getRelease(installed.releaseId)!!
        assertEquals("com.example.notes", instance.happId)
        assertEquals("https://example.test/apps/notes/", instance.liveUrl)
        assertEquals("https://example.test/apps/notes/latest.json", instance.updateUrl)
        assertEquals("history", release.routing)
        assertEquals("web/index.html", release.entryPath)
        assertEquals(null, instance.iconUrl)
        assertTrue(instance.defaultIconUrl!!.startsWith("/__haminn/objects/images/${installed.appId}/"))

        val customIcon = "data:image/png;base64," + android.util.Base64.encodeToString(
            pngIcon(192, 192, android.graphics.Color.RED), android.util.Base64.NO_WRAP,
        )
        val customIconBytes = android.util.Base64.decode(customIcon.removePrefix("data:image/png;base64,"), android.util.Base64.NO_WRAP)
        app.registry.updatePresentation(installed.appId, instance.name, customIconBytes, replaceIcon = true)
        val updateManifest = manifest.replace("\"code\":2", "\"code\":3").replace("2.0.0", "3.0.0")
        app.installer.installZip(ByteArrayInputStream(zipBytesOf(mapOf(
            "haminn.json" to updateManifest.toByteArray(),
            "web/index.html" to "<h1>updated</h1>".toByteArray(),
            "assets/icon.png" to pngIcon(64, 96, android.graphics.Color.GREEN),
        ))), null, installed.appId, expectedReleaseId = installed.releaseId)
        assertTrue(app.registry.getInstance(installed.appId)!!.iconUrl!!.startsWith("/__haminn/objects/images/${installed.appId}/"))
        val afterUpdate = app.registry.getInstance(installed.appId)!!
        assertTrue(afterUpdate.defaultIconUrl != null)
        assertTrue(afterUpdate.defaultIconUrl != afterUpdate.iconUrl)
        assertEquals(afterUpdate.iconUrl, afterUpdate.effectiveIconUrl)
        app.registry.updatePresentation(installed.appId, instance.name, null, replaceIcon = true)
        val reset = app.registry.getInstance(installed.appId)!!
        assertEquals(null, reset.iconUrl)
        assertEquals(reset.defaultIconUrl, reset.effectiveIconUrl)
    }

    @Test fun repeatedHappIdNeedsAnExplicitInstanceChoice() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        fun archive(version: Int) = zipOf(mapOf(
            "haminn.json" to """{"schema":2,"happId":"com.example.identity","name":"Identity","version":{"code":$version,"name":"$version.0.0"}}""",
            "index.html" to "<h1>$version</h1>",
        ))
        val first = app.installer.installZip(ByteArrayInputStream(archive(1)), null)
        createdApps += first.appId
        var prompted = false
        val second = app.installer.installZip(ByteArrayInputStream(archive(2)), null, identityChoice = { existing, incomingPublisher ->
            prompted = true
            assertEquals(first.appId, existing.appId)
            assertEquals(null, incomingPublisher)
            IdentityInstallChoice.UPDATE
        })
        assertTrue(prompted)
        assertEquals(first.appId, second.appId)
        assertEquals(2L, app.registry.getRelease(second.releaseId)!!.versionCode)
    }

    @Test fun notificationSchedulesAreIsolatedByInstance() {
        val repository = (context.applicationContext as HaminnApplication).notifications.repository
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val spec = NotificationSpec("same-id", "提醒", "内容", JSONObject().put("value", 1))
        val triggerAt = System.currentTimeMillis() + 60_000
        try {
            repository.upsert(first, spec, triggerAt, Recurrence.DAILY)
            repository.upsert(second, spec, triggerAt, Recurrence.WEEKLY)
            assertEquals(Recurrence.DAILY, repository.list(first).single().recurrence)
            assertEquals(Recurrence.WEEKLY, repository.list(second).single().recurrence)
            assertTrue(repository.cancel(first, spec.id))
            assertTrue(repository.list(first).isEmpty())
            assertEquals(1, repository.list(second).size)
        } finally {
            repository.deleteInstance(first)
            repository.deleteInstance(second)
        }
    }

    @Test fun removingLiveUrlFallsBackToLocalWithoutChangingGrant() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "ok"))), "URL fixture",
            source = HappSource.ONLINE, liveUrl = "https://example.test/app/")
        createdApps += installed.appId
        val live = app.registry.setRuntimeMode(installed.appId, HappRuntimeMode.LIVE)
        app.registry.putGrant(live.appId, live.trustRevision, "notifications", "allow")
        val updated = app.registry.updateUrls(live.appId, null, null)
        assertEquals(HappRuntimeMode.LOCAL, updated.runtimeMode)
        assertTrue(updated.runtimeUrl.contains(".apps.haminn.invalid"))
        assertEquals("allow", app.registry.getGrant(updated.appId, updated.trustRevision, "notifications"))
    }

    @Test fun treeHashEncodesFileBoundariesUnambiguously() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val one = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf(
            "a" to "xb\u0000y",
            "index.html" to "ok",
        ))), "Tree hash fixture")
        createdApps += one.appId
        val two = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf(
            "a" to "x",
            "b" to "y",
            "index.html" to "ok",
        ))), null, one.appId, expectedReleaseId = one.releaseId)
        assertFalse(one.treeHash == two.treeHash)
        assertFalse(one.releaseId == two.releaseId)
    }

    @Test fun interruptedPackageTransferDoesNotLeaveIncomingArchive() {
        val app = context.applicationContext as HaminnApplication
        val instances = File(context.filesDir, "instances")
        val before = instances.walkTopDown().filter { it.isFile && it.parentFile?.name == "incoming" }.map { it.path }.toSet()
        val rootsBefore = instances.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()
        val broken = object : InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count++ < 32) return 'x'.code
                throw IOException("test interruption")
            }
        }
        val error = assertThrows(HaminnException::class.java) {
            runBlocking { app.installer.installZip(broken, "Broken") }
        }
        assertEquals(ErrorCodes.STORAGE, error.code)
        val after = instances.walkTopDown().filter { it.isFile && it.parentFile?.name == "incoming" }.map { it.path }.toSet()
        assertEquals(before, after)
        assertEquals(rootsBefore, instances.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty())
    }

    @Test fun logicalBackupRestoresCodeRecordsAndFileIds() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val records = RecordsStore(context)
        val fileStore = FileStore(context)
        val installed = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf(
            "index.html" to "<h1>backup</h1>",
            "haminn.json" to "{\"schema\":1,\"name\":\"Backup fixture\"}",
        ))), null)
        createdApps += installed.appId
        val instance = app.registry.getInstance(installed.appId)!!
        records.put(installed.appId, instance.activeDataGeneration, "notes", "one", JSONObject().put("text", "kept"), "absent")
        val originalFile = fileStore.import(installed.appId, instance.activeDataGeneration, "attachment".byteInputStream(), "a.txt", "text/plain")
        val share = java.io.File(context.filesDir, "shared/test-${UUID.randomUUID()}.zip").apply { parentFile!!.mkdirs(); createNewFile() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", share)
        val coordinator = BackupCoordinator(context, app.registry, app.installer, records, fileStore, app.notifications.repository)
        coordinator.export(installed.appId, uri)
        val restoredResult = coordinator.restore(uri)
        val restoredId = restoredResult.getString("appId")
        createdApps += restoredId
        val restored = app.registry.getInstance(restoredId)!!
        assertEquals("kept", records.get(restoredId, restored.activeDataGeneration, "notes", "one")!!.getJSONObject("value").getString("text"))
        assertEquals("attachment", fileStore.readText(restoredId, restored.activeDataGeneration, originalFile.getString("logicalFileId")).getString("text"))

        val targetInstall = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "<h1>target code</h1>"))), "Restore target")
        createdApps += targetInstall.appId
        val targetBefore = app.registry.getInstance(targetInstall.appId)!!
        records.put(targetInstall.appId, targetBefore.activeDataGeneration, "notes", "old", "replace me", "absent")
        app.registry.putGrant(targetInstall.appId, targetBefore.trustRevision, "speech", "allow")
        coordinator.restoreData(uri, targetInstall.appId)
        val targetAfter = app.registry.getInstance(targetInstall.appId)!!
        assertEquals(targetInstall.appId, targetAfter.appId)
        assertEquals(targetInstall.releaseId, targetAfter.activeReleaseId)
        assertFalse(targetBefore.activeDataGeneration == targetAfter.activeDataGeneration)
        assertEquals(targetBefore.trustRevision + 1, targetAfter.trustRevision)
        assertEquals("allow", app.registry.getGrant(targetAfter.appId, targetAfter.trustRevision, "speech"))
        assertEquals(null, records.get(targetAfter.appId, targetAfter.activeDataGeneration, "notes", "old"))
        assertEquals("kept", records.get(targetAfter.appId, targetAfter.activeDataGeneration, "notes", "one")!!.getJSONObject("value").getString("text"))
        assertEquals("attachment", fileStore.readText(targetAfter.appId, targetAfter.activeDataGeneration, originalFile.getString("logicalFileId")).getString("text"))
        share.delete()
        Unit
    }

    @Test fun loopbackDeployIsTargetBoundAuthenticatedAndTransactional() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val first = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "v1"))), "Deploy fixture")
        createdApps += first.appId
        var reloadedAppId: String? = null
        val reloadOwner = Any()
        app.developmentServer.setReloadHandler(reloadOwner) { appId -> reloadedAppId = appId; true }
        val session = app.developmentServer.start(first.appId)
        try {
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(session.getString("address") + "/v1/status").build()).execute().use {
                assertEquals(401, it.code)
            }
            val next = zipOf(mapOf("index.html" to "v2"))
            val sha = MessageDigest.getInstance("SHA-256").digest(next).joinToString("") { "%02x".format(it) }
            val request = Request.Builder().url(session.getString("address") + "/v1/apps/${first.appId}/release")
                .header("Authorization", "Bearer ${session.getString("token")}")
                .header("Idempotency-Key", "instrumented-deploy-v2")
                .header("X-Haminn-Expected-Release", first.releaseId)
                .header("X-Haminn-Content-SHA256", sha)
                .put(next.toRequestBody("application/zip".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                assertEquals(response.body.string(), 200, response.code)
            }
            assertFalse(first.releaseId == app.registry.getInstance(first.appId)!!.activeReleaseId)
            val reload = Request.Builder().url(session.getString("address") + "/v1/apps/${first.appId}/reload")
                .header("Authorization", "Bearer ${session.getString("token")}")
                .post(ByteArray(0).toRequestBody(null)).build()
            client.newCall(reload).execute().use { response -> assertEquals(response.body.string(), 202, response.code) }
            assertEquals(first.appId, reloadedAppId)
        } finally {
            app.developmentServer.clearReloadHandler(reloadOwner)
            app.developmentServer.stop("test complete")
        }
    }

    @Test fun lanDeployUsesEphemeralTlsAndSpkiPin() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val installed = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "tls"))), "TLS fixture")
        createdApps += installed.appId
        val session = app.developmentServer.start(installed.appId, "lan")
        try {
            val observed = arrayOfNulls<X509Certificate>(1)
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    observed[0] = chain.firstOrNull()
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
            val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
            val request = Request.Builder().url(session.getString("address") + "/v1/status")
                .header("Authorization", "Bearer ${session.getString("token")}").build()
            client.newCall(request).execute().use {
                assertEquals(200, it.code)
                val certificate = requireNotNull(observed[0])
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
                val actualPin = "sha256//" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
                assertEquals(session.getString("spkiPin"), actualPin)
            }
        } finally { app.developmentServer.stop("test complete") }
    }

    @Test fun nativeHttpRejectsLoopbackBeforeAuthorization() {
        var authorized = false
        val error = assertThrows(HaminnException::class.java) { runBlocking {
            NativeHttpClient(FileStore(context)).request(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                JSONObject().put("url", "http://127.0.0.1:9999/")) { _, _ -> authorized = true }
        } }
        assertEquals(ErrorCodes.ORIGIN_DENIED, error.code)
        assertFalse(authorized)
    }

    @Test fun nativeHttpSeparatesPublicAndPrivateOriginGrants() {
        val publicTarget = NativeHttpClient.AuthorizationTarget("https://example.test", "public")
        val privateTarget = NativeHttpClient.AuthorizationTarget("https://example.test", "private")
        assertEquals("public:https://example.test", publicTarget.grantScope)
        assertEquals("private:https://example.test", privateTarget.grantScope)
        assertFalse(publicTarget.grantScope == privateTarget.grantScope)
    }

    @Test fun nativeHttpRejectsIpv4MappedLoopback() {
        var authorized = false
        val error = assertThrows(HaminnException::class.java) { runBlocking {
            NativeHttpClient(FileStore(context)).request(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                JSONObject().put("url", "http://[::ffff:127.0.0.1]:9999/")) { _, _ -> authorized = true }
        } }
        assertEquals(ErrorCodes.ORIGIN_DENIED, error.code)
        assertFalse(authorized)
    }

    @Test fun localGatewayProvidesMimeRangeAndServiceWorkerSuppression() {
        val root = java.io.File(context.cacheDir, "gateway-${UUID.randomUUID()}").apply { mkdirs() }
        java.io.File(root, "index.html").writeText("home")
        java.io.File(root, "asset.js").writeText("0123456789")
        try {
            val gateway = LocalContentGateway.forRelease(context, "https://fixture.apps.haminn.invalid/", root, "index.html", false,
                allowNetworkFallback = false)
            val full = gateway.intercept(request("https://fixture.apps.haminn.invalid/asset.js"))!!
            assertEquals(200, full.statusCode)
            assertEquals("text/javascript", full.mimeType)
            assertEquals("https://sw-disabled.haminn.invalid/", full.responseHeaders["Service-Worker-Allowed"])
            assertEquals("0123456789", full.data.reader().readText())
            val partial = gateway.intercept(request("https://fixture.apps.haminn.invalid/asset.js", mapOf("Range" to "bytes=2-5")))!!
            assertEquals(206, partial.statusCode)
            assertEquals("2345", partial.data.reader().readText())
            assertEquals(403, gateway.intercept(request("https://fixture.apps.haminn.invalid/missing.js"))!!.statusCode)
        } finally { root.deleteRecursively() }
    }

    @Test fun localGatewayOverlaysMountedPathAndFallsThroughToNetwork() {
        val root = java.io.File(context.cacheDir, "gateway-live-${UUID.randomUUID()}").apply { mkdirs() }
        java.io.File(root, "index.html").writeText("home")
        java.io.File(root, "app.js").writeText("local")
        try {
            val gateway = LocalContentGateway.forRelease(context, "https://example.test/apps/one/", root, "index.html", false,
                allowNetworkFallback = true)
            assertEquals("home", gateway.intercept(request("https://example.test/apps/one/"))!!.data.reader().readText())
            assertEquals("local", gateway.intercept(request("https://example.test/apps/one/app.js"))!!.data.reader().readText())
            assertEquals(null, gateway.intercept(request("https://example.test/api/info")))
            assertEquals(null, gateway.intercept(request("https://example.test/apps/one/api", method = "POST")))
        } finally { root.deleteRecursively() }
    }

    @Test fun networkPolicyAllowsPassiveAssetsButBlocksActiveCrossOriginRequests() {
        val policy = RuntimeNetworkPolicy("https://example.test/app/", false)
        assertEquals(null, policy.intercept(request("https://example.test/api")))
        assertEquals(null, policy.intercept(request("https://cdn.test/image.png", mapOf("Sec-Fetch-Dest" to "image"))))
        assertEquals(403, policy.intercept(request("https://api.other.test/data", mapOf("Sec-Fetch-Dest" to "empty")))!!.statusCode)
    }

    @Test fun declaredBuildTravelsWithTheReleaseAndStaysAPlainClaim() = runBlocking {
        val app = context.applicationContext as HaminnApplication
        val happId = "com.example.declared"
        val record = """{"schema":1,"happId":"$happId","name":"Declared","versionName":"2.0.0","versionCode":2,""" +
            """"author":"author","maintainer":"maintainer","packagedAt":"2026-09-30T00:00:00Z",""" +
            """"basedOn":{"name":"Declared","versionName":"1.0.0","maintainer":"maintainer"}}"""
        val manifest = """{"schema":2,"happId":"$happId","name":"Declared","author":"author","maintainer":"maintainer",""" +
            """"version":{"code":2,"name":"2.0.0"},"entry":"index.html"}"""
        val installed = app.installer.installZip(ByteArrayInputStream(zipBytesOf(mapOf(
            "haminn.json" to manifest.toByteArray(),
            "index.html" to "<h1>declared</h1>".toByteArray(),
            "haminn-build.json" to record.toByteArray(),
        ))), null)
        createdApps += installed.appId
        // It arrives with the release, so it survives the package being gone …
        val declared = JSONObject(app.registry.getRelease(installed.releaseId)!!.declaredBuild!!)
        assertEquals("maintainer", declared.getString("maintainer"))
        assertEquals("2026-09-30T00:00:00Z", declared.getString("packagedAt"))
        assertEquals("1.0.0", declared.getJSONObject("basedOn").getString("versionName"))

        // … and it stays a claim: a package without one, and a package with a broken one, both
        // install exactly like any other.
        val plain = app.installer.installZip(ByteArrayInputStream(zipOf(mapOf("index.html" to "plain"))), "Plain fixture")
        createdApps += plain.appId
        assertEquals(null, app.registry.getRelease(plain.releaseId)!!.declaredBuild)
        val broken = app.installer.installZip(ByteArrayInputStream(zipBytesOf(mapOf(
            "index.html" to "broken".toByteArray(),
            "haminn-build.json" to "{ this is not json".toByteArray(),
        ))), "Broken declaration fixture")
        createdApps += broken.appId
        assertEquals(null, app.registry.getRelease(broken.releaseId)!!.declaredBuild)
    }

    private fun request(value: String, headers: Map<String, String> = emptyMap(), method: String = "GET") = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = headers
    }

    private fun zipOf(files: Map<String, String>): ByteArray {
        return zipBytesOf(files.mapValues { it.value.toByteArray() })
    }

    private fun zipBytesOf(files: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
        } }
        return bytes.toByteArray()
    }

    private fun pngIcon(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
