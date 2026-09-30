package life.airen.haminn.registry

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import life.airen.haminn.data.HostImageStore
import life.airen.haminn.model.CodeRelease
import life.airen.haminn.model.HappRuntimeMode
import life.airen.haminn.model.HappSource
import life.airen.haminn.model.LaunchChannel
import life.airen.haminn.model.DevWorkspace
import life.airen.haminn.model.WebAppInstance
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AppRegistry(private val context: Context) : SQLiteOpenHelper(context, "haminn-registry.sqlite", null, VERSION) {
    private val images = HostImageStore(context)
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE instances (
              app_id TEXT PRIMARY KEY, name TEXT NOT NULL,
              source_kind TEXT NOT NULL CHECK(source_kind IN ('ONLINE','LOCAL')),
              runtime_mode TEXT NOT NULL CHECK(runtime_mode IN ('LOCAL','LIVE')),
              launch_channel TEXT NOT NULL DEFAULT 'STABLE' CHECK(launch_channel IN ('STABLE','DEV')),
              start_url TEXT NOT NULL, primary_origin TEXT NOT NULL,
              live_url TEXT,
              web_profile_name TEXT NOT NULL UNIQUE, trust_revision INTEGER NOT NULL,
              active_release_id TEXT, active_data_generation TEXT NOT NULL,
              source_adapter TEXT NOT NULL, source_spec TEXT NOT NULL,
              developer_enabled INTEGER NOT NULL DEFAULT 0,
              favorite INTEGER NOT NULL DEFAULT 0,
              icon_url TEXT,
              default_icon_url TEXT,
              happ_id TEXT,
              publisher_key_id TEXT,
              download_url TEXT,
              download_version_code INTEGER,
              download_version_name TEXT,
              update_url TEXT,
              source_path TEXT,
              source_uri TEXT,
              notification_enabled INTEGER NOT NULL DEFAULT 0,
              allow_cross_origin_network INTEGER NOT NULL DEFAULT 0,
              state TEXT NOT NULL DEFAULT 'ready',
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE releases (
              release_id TEXT PRIMARY KEY,
              app_id TEXT NOT NULL REFERENCES instances(app_id) ON DELETE CASCADE,
              tree_hash TEXT NOT NULL, provenance TEXT NOT NULL,
              version_code INTEGER, version_name TEXT, source_revision TEXT,
              entry_path TEXT NOT NULL DEFAULT 'index.html',
              routing TEXT NOT NULL DEFAULT 'hash',
              happ_id TEXT,
              publisher_key_id TEXT,
              declared_build TEXT,
              relative_root TEXT NOT NULL, created_at INTEGER NOT NULL,
              UNIQUE(app_id, tree_hash)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX releases_by_app_time ON releases(app_id, created_at DESC)")
        createDevWorkspaces(db)
        db.execSQL("""
            CREATE TABLE grants (
              app_id TEXT NOT NULL REFERENCES instances(app_id) ON DELETE CASCADE,
              trust_revision INTEGER NOT NULL, capability TEXT NOT NULL,
              resource_scope TEXT NOT NULL DEFAULT '',
              decision TEXT NOT NULL CHECK(decision IN ('allow','deny')),
              updated_at INTEGER NOT NULL,
              PRIMARY KEY(app_id, trust_revision, capability, resource_scope)
            )
        """.trimIndent())
        createSystemPermissionObservations(db)
        db.execSQL("""
            CREATE TABLE operations (
              operation_id TEXT PRIMARY KEY, app_id TEXT, kind TEXT NOT NULL,
              state TEXT NOT NULL, idempotency_key TEXT, input_hash TEXT,
              expected_release_id TEXT, result_release_id TEXT,
              error_code TEXT, error_message TEXT,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE diagnostic_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT, app_id TEXT, session_id TEXT,
              method TEXT NOT NULL, decision TEXT NOT NULL, result_code TEXT,
              duration_ms INTEGER, created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE profile_cleanup (
              profile_name TEXT PRIMARY KEY, app_id TEXT NOT NULL,
              state TEXT NOT NULL DEFAULT 'pending',
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        createSettings(db)
        // The two DDL copies above and in createMissingTables() would otherwise have to be kept in
        // sync by hand, and a column added to only one of them is invisible on whichever path did
        // not get it: fresh installs (onCreate) or upgrades (onUpgrade). Running the additive pass
        // here too makes createMissingTables() the single self-healing authority for new columns.
        createMissingTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion > VERSION) throw IllegalStateException("Unsupported registry version $newVersion")
        if (oldVersion >= VERSION) return
        // Never rebuild the registry during an APK update. Older installations
        // may contain user data; create missing auxiliary tables and add only
        // columns that did not exist in that schema.
        db.beginTransaction()
        try {
            createMissingTables(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createMissingTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS instances (app_id TEXT PRIMARY KEY, name TEXT NOT NULL, source_kind TEXT NOT NULL, runtime_mode TEXT NOT NULL, launch_channel TEXT NOT NULL DEFAULT 'STABLE', start_url TEXT NOT NULL, primary_origin TEXT NOT NULL, live_url TEXT, web_profile_name TEXT NOT NULL UNIQUE, trust_revision INTEGER NOT NULL DEFAULT 1, active_release_id TEXT, active_data_generation TEXT NOT NULL, source_adapter TEXT NOT NULL DEFAULT 'unknown', source_spec TEXT NOT NULL DEFAULT '{}', developer_enabled INTEGER NOT NULL DEFAULT 0, favorite INTEGER NOT NULL DEFAULT 0, icon_url TEXT, default_icon_url TEXT, happ_id TEXT, publisher_key_id TEXT, download_url TEXT, download_version_code INTEGER, download_version_name TEXT, update_url TEXT, source_path TEXT, source_uri TEXT, notification_enabled INTEGER NOT NULL DEFAULT 0, allow_cross_origin_network INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'ready', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS releases (release_id TEXT PRIMARY KEY, app_id TEXT NOT NULL, tree_hash TEXT NOT NULL, provenance TEXT NOT NULL, version_code INTEGER, version_name TEXT, source_revision TEXT, entry_path TEXT NOT NULL DEFAULT 'index.html', routing TEXT NOT NULL DEFAULT 'hash', happ_id TEXT, publisher_key_id TEXT, declared_build TEXT, relative_root TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(app_id, tree_hash))")
        db.execSQL("CREATE INDEX IF NOT EXISTS releases_by_app_time ON releases(app_id, created_at DESC)")
        createDevWorkspaces(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS grants (app_id TEXT NOT NULL, trust_revision INTEGER NOT NULL, capability TEXT NOT NULL, resource_scope TEXT NOT NULL DEFAULT '', decision TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(app_id, trust_revision, capability, resource_scope))")
        createSystemPermissionObservations(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS operations (operation_id TEXT PRIMARY KEY, app_id TEXT, kind TEXT NOT NULL, state TEXT NOT NULL, idempotency_key TEXT, input_hash TEXT, expected_release_id TEXT, result_release_id TEXT, error_code TEXT, error_message TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diagnostic_events (id INTEGER PRIMARY KEY AUTOINCREMENT, app_id TEXT, session_id TEXT, method TEXT NOT NULL, decision TEXT NOT NULL, result_code TEXT, duration_ms INTEGER, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS profile_cleanup (profile_name TEXT PRIMARY KEY, app_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        createSettings(db)
        val columns = db.rawQuery("PRAGMA table_info(instances)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        // Adding a column here is not enough on its own: SQLite only calls onUpgrade while the
        // file version is still behind, so every new column must come with a VERSION bump.
        val additions = mapOf("launch_channel" to "TEXT NOT NULL DEFAULT 'STABLE'", "source_adapter" to "TEXT NOT NULL DEFAULT 'unknown'", "source_spec" to "TEXT NOT NULL DEFAULT '{}'", "developer_enabled" to "INTEGER NOT NULL DEFAULT 0", "favorite" to "INTEGER NOT NULL DEFAULT 0", "icon_url" to "TEXT", "default_icon_url" to "TEXT", "happ_id" to "TEXT", "publisher_key_id" to "TEXT", "download_url" to "TEXT", "download_version_code" to "INTEGER", "download_version_name" to "TEXT", "update_url" to "TEXT", "source_path" to "TEXT", "source_uri" to "TEXT", "notification_enabled" to "INTEGER NOT NULL DEFAULT 0", "allow_cross_origin_network" to "INTEGER NOT NULL DEFAULT 0", "state" to "TEXT NOT NULL DEFAULT 'ready'")
        additions.filterKeys { it !in columns }.forEach { (name, definition) -> db.execSQL("ALTER TABLE instances ADD COLUMN $name $definition") }
        val releaseColumns = db.rawQuery("PRAGMA table_info(releases)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        mapOf("declared_build" to "TEXT")
            .filterKeys { it !in releaseColumns }
            .forEach { (name, definition) -> db.execSQL("ALTER TABLE releases ADD COLUMN $name $definition") }
    }

    fun listInstances(): List<WebAppInstance> =
        readableDatabase.query("instances", null, "state = ?", arrayOf("ready"), null, null, "created_at ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toInstance()) } }

    fun listAllInstances(): List<WebAppInstance> =
        readableDatabase.query("instances", null, "state IN ('ready','archived')", null, null, null, "created_at ASC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toInstance()) } }

    fun findArchived(happId: String, publisherKeyId: String): WebAppInstance? = readableDatabase.query(
        "instances", null, "state = 'archived' AND happ_id = ? AND publisher_key_id = ?",
        arrayOf(happId, publisherKeyId), null, null, "updated_at DESC", "2"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toInstance().takeUnless { cursor.moveToNext() }
    }

    fun findReady(happId: String, publisherKeyId: String): WebAppInstance? = readableDatabase.query(
        "instances", null, "state = 'ready' AND happ_id = ? AND publisher_key_id = ?",
        arrayOf(happId, publisherKeyId), null, null, "updated_at DESC", "2"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toInstance().takeUnless { cursor.moveToNext() }
    }

    fun findUnsignedReady(happId: String): WebAppInstance? = findUniqueByIdentity("ready", happId)
    fun findUnsignedArchived(happId: String): WebAppInstance? = findUniqueByIdentity("archived", happId)
    fun findAnyReady(happId: String): WebAppInstance? = findUniqueByHappId("ready", happId)
    fun findAnyArchived(happId: String): WebAppInstance? = findUniqueByHappId("archived", happId)

    private fun findUniqueByIdentity(state: String, happId: String): WebAppInstance? = readableDatabase.query(
        "instances", null, "state = ? AND happ_id = ? AND publisher_key_id IS NULL",
        arrayOf(state, happId), null, null, "updated_at DESC", "2"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toInstance().takeUnless { cursor.moveToNext() }
    }

    private fun findUniqueByHappId(state: String, happId: String): WebAppInstance? = readableDatabase.query(
        "instances", null, "state = ? AND happ_id = ?", arrayOf(state, happId), null, null, "updated_at DESC", "2"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toInstance().takeUnless { cursor.moveToNext() }
    }

    fun updatePackageIdentity(appId: String, happId: String?, publisherKeyId: String?) {
        val changed = writableDatabase.update("instances", ContentValues().apply {
            if (happId == null) putNull("happ_id") else put("happ_id", happId)
            if (publisherKeyId == null) putNull("publisher_key_id") else put("publisher_key_id", publisherKeyId)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
        check(changed == 1) { "App not found" }
    }

    fun getInstance(appId: String): WebAppInstance? =
        readableDatabase.query("instances", null, "app_id = ? AND state = ?", arrayOf(appId, "ready"), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }

    fun getAnyInstance(appId: String): WebAppInstance? =
        readableDatabase.query("instances", null, "app_id = ? AND state IN ('ready','archived')", arrayOf(appId), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.toInstance() else null }

    fun resolveLaunchTarget(appId: String?, happId: String?, publisherKeyId: String?): WebAppInstance? {
        appId?.takeIf { it.isNotBlank() }?.let(::getInstance)?.let { return it }
        val stableId = happId?.takeIf { it.isNotBlank() } ?: return null
        val publisher = publisherKeyId?.takeIf { it.isNotBlank() }
        return if (publisher == null) findAnyReady(stableId) else findReady(stableId, publisher)
    }

    fun insertInstance(instance: WebAppInstance) {
        writableDatabase.insertOrThrow("instances", null, instance.values())
    }

    fun insertLocalWithRelease(instance: WebAppInstance, release: CodeRelease) {
        require(instance.appId == release.appId)
        writableDatabase.inTransaction {
            insertOrThrow("instances", null, instance.values())
            insertOrThrow("releases", null, release.values())
            val values = ContentValues().apply {
                put("active_release_id", release.releaseId)
                put("updated_at", System.currentTimeMillis())
            }
            check(update("instances", values, "app_id = ? AND active_release_id IS NULL", arrayOf(instance.appId)) == 1)
        }
    }

    fun restoreArchivedWithRelease(instanceId: String, release: CodeRelease) {
        require(instanceId == release.appId)
        writableDatabase.inTransaction {
            insertOrThrow("releases", null, release.values())
            val current = query("instances", arrayOf("live_url"), "app_id = ? AND state = 'archived'", arrayOf(instanceId), null, null, null)
                .use { if (it.moveToFirst()) if (it.isNull(0)) null else it.getString(0) else throw IllegalStateException("Archived app not found") }
            val target = current ?: "${localOrigin(instanceId)}/"
            check(update("instances", ContentValues().apply {
                put("state", "ready"); put("active_release_id", release.releaseId); put("runtime_mode", "LOCAL")
                put("start_url", target); put("primary_origin", originOf(target)); put("notification_enabled", 0)
                put("updated_at", System.currentTimeMillis())
            }, "app_id = ? AND state = 'archived'", arrayOf(instanceId)) == 1)
        }
    }

    fun commitRelease(release: CodeRelease, expectedActive: String?) {
        writableDatabase.inTransaction {
            insertOrThrow("releases", null, release.values())
            val runtimeMode = query("instances", arrayOf("runtime_mode"), "app_id = ?", arrayOf(release.appId), null, null, null)
                .use { cursor -> if (cursor.moveToFirst()) HappRuntimeMode.valueOf(cursor.getString(0)) else throw IllegalStateException("App not found") }
            val values = ContentValues().apply {
                put("active_release_id", release.releaseId)
                if (runtimeMode == HappRuntimeMode.LOCAL) {
                    val liveUrl = query("instances", arrayOf("live_url"), "app_id = ?", arrayOf(release.appId), null, null, null)
                        .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
                    val target = liveUrl ?: "${localOrigin(release.appId)}/"
                    put("start_url", target)
                    put("primary_origin", originOf(target))
                }
                put("updated_at", System.currentTimeMillis())
            }
            val where = if (expectedActive == null) "app_id = ? AND active_release_id IS NULL" else "app_id = ? AND active_release_id = ?"
            val args = if (expectedActive == null) arrayOf(release.appId) else arrayOf(release.appId, expectedActive)
            if (update("instances", values, where, args) != 1) throw IllegalStateException("Active release changed")
        }
    }

    fun getRelease(releaseId: String): CodeRelease? =
        readableDatabase.query("releases", null, "release_id = ?", arrayOf(releaseId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toRelease() else null }

    fun findReleaseByTreeHash(appId: String, treeHash: String): CodeRelease? =
        readableDatabase.query("releases", null, "app_id = ? AND tree_hash = ?", arrayOf(appId, treeHash), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toRelease() else null }

    fun listReleases(appId: String): List<CodeRelease> =
        readableDatabase.query("releases", null, "app_id = ?", arrayOf(appId), null, null, "created_at DESC")
            .use { c -> buildList { while (c.moveToNext()) add(c.toRelease()) } }

    fun activateRelease(appId: String, releaseId: String, expectedActive: String?) {
        writableDatabase.inTransaction {
            val releaseExists = query("releases", arrayOf("release_id"), "app_id = ? AND release_id = ?",
                arrayOf(appId, releaseId), null, null, null).use { it.moveToFirst() }
            if (!releaseExists) throw IllegalArgumentException("Release does not belong to app")
            val runtimeMode = query("instances", arrayOf("runtime_mode"), "app_id = ?", arrayOf(appId), null, null, null)
                .use { cursor -> if (cursor.moveToFirst()) HappRuntimeMode.valueOf(cursor.getString(0)) else throw IllegalArgumentException("App not found") }
            val values = ContentValues().apply {
                put("active_release_id", releaseId)
                if (runtimeMode == HappRuntimeMode.LOCAL) {
                    val liveUrl = query("instances", arrayOf("live_url"), "app_id = ?", arrayOf(appId), null, null, null)
                        .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
                    val target = liveUrl ?: "${localOrigin(appId)}/"
                    put("start_url", target)
                    put("primary_origin", originOf(target))
                }
                put("updated_at", System.currentTimeMillis())
            }
            val where = if (expectedActive == null) "app_id = ? AND active_release_id IS NULL" else "app_id = ? AND active_release_id = ?"
            val args = if (expectedActive == null) arrayOf(appId) else arrayOf(appId, expectedActive)
            check(update("instances", values, where, args) == 1) { "Active release changed" }
        }
    }

    fun deleteReleaseRows(appId: String, releaseIds: Collection<String>) {
        if (releaseIds.isEmpty()) return
        writableDatabase.inTransaction {
            releaseIds.forEach { releaseId ->
                delete("releases", "app_id = ? AND release_id = ? AND release_id NOT IN (SELECT active_release_id FROM instances WHERE app_id = ?)",
                    arrayOf(appId, releaseId, appId))
            }
        }
    }

    fun updateInstance(appId: String, name: String, liveUrl: String?, sourceSpec: String?) {
        val current = getInstance(appId) ?: throw IllegalArgumentException("App not found")
        val nextLiveOrigin = liveUrl?.let(::originOf)
        val previousLiveOrigin = current.liveUrl?.let(::originOf)
        val originChanged = nextLiveOrigin != previousLiveOrigin
        writableDatabase.inTransaction {
            val now = System.currentTimeMillis()
            val changed = update("instances", ContentValues().apply {
                put("name", name.take(80))
                if (liveUrl == null) putNull("live_url") else put("live_url", liveUrl)
                val target = if (current.runtimeMode == HappRuntimeMode.LIVE) liveUrl else liveUrl ?: current.localUrl
                if (target != null) {
                    put("start_url", target)
                    put("primary_origin", originOf(target))
                }
                sourceSpec?.let { put("source_spec", it) }
                if (originChanged) {
                    put("trust_revision", current.trustRevision + 1)
                }
                put("updated_at", now)
            }, "app_id = ? AND state = 'ready'", arrayOf(appId))
            check(changed == 1) { "App changed while updating" }
            if (originChanged) {
                delete("grants", "app_id = ? AND resource_scope != ''", arrayOf(appId))
            }
        }
    }

    fun setRuntimeMode(appId: String, requested: HappRuntimeMode): WebAppInstance {
        val current = getInstance(appId) ?: throw IllegalArgumentException("App not found")
        if (requested == current.runtimeMode) return current
        val targetUrl: String
        val targetOrigin: String
        if (requested == HappRuntimeMode.LIVE) {
            targetUrl = current.liveUrl ?: throw IllegalStateException("此线上 happ 没有可实时运行的页面地址")
            targetOrigin = originOf(targetUrl)
        } else {
            current.activeReleaseId?.let(::getRelease)
                ?: throw IllegalStateException("此 happ 没有可用的本地代码")
            targetUrl = current.liveUrl ?: "${localOrigin(appId)}/"
            targetOrigin = originOf(targetUrl)
        }
        writableDatabase.inTransaction {
            val now = System.currentTimeMillis()
            val changed = update("instances", ContentValues().apply {
                put("runtime_mode", requested.name)
                put("start_url", targetUrl)
                put("primary_origin", targetOrigin)
                put("developer_enabled", 0)
                put("updated_at", now)
            }, "app_id = ? AND state = 'ready' AND runtime_mode = ?", arrayOf(appId, current.runtimeMode.name))
            check(changed == 1) { "运行方式已发生变化" }
        }
        return getInstance(appId) ?: error("App not found")
    }

    fun updateSource(appId: String, adapter: String, sourceSpec: String) {
        writableDatabase.update("instances", ContentValues().apply {
            put("source_adapter", adapter)
            put("source_spec", sourceSpec)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
    }

    fun swapDataGeneration(appId: String, expectedGeneration: String, nextGeneration: String) {
        writableDatabase.inTransaction {
            val trustRevision = query("instances", arrayOf("trust_revision"),
                "app_id = ? AND state = 'ready' AND active_data_generation = ?",
                arrayOf(appId, expectedGeneration), null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: throw IllegalStateException("Active data generation changed")
            val changed = update("instances", ContentValues().apply {
                put("active_data_generation", nextGeneration)
                put("trust_revision", trustRevision + 1)
                put("developer_enabled", 0)
                put("updated_at", System.currentTimeMillis())
            }, "app_id = ? AND state = 'ready' AND active_data_generation = ?", arrayOf(appId, expectedGeneration))
            if (changed != 1) throw IllegalStateException("Active data generation changed")
        }
    }

    fun setDeveloperEnabled(appId: String, enabled: Boolean) {
        writableDatabase.update("instances", ContentValues().apply {
            put("developer_enabled", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ?", arrayOf(appId))
    }

    fun setLaunchChannel(appId: String, channel: LaunchChannel): WebAppInstance {
        writableDatabase.inTransaction {
            if (channel == LaunchChannel.DEV) {
                val workspace = query("dev_workspaces", arrayOf("app_id"), "app_id = ?", arrayOf(appId), null, null, null)
                    .use { it.moveToFirst() }
                if (!workspace) throw IllegalStateException("开发工作副本不存在")
            }
            val current = getInstance(appId) ?: throw IllegalArgumentException("App not found")
            val values = ContentValues().apply {
                put("launch_channel", channel.name)
                if (channel == LaunchChannel.DEV) {
                    put("runtime_mode", HappRuntimeMode.LOCAL.name)
                    val target = current.liveUrl ?: current.localUrl
                    put("start_url", target)
                    put("primary_origin", originOf(target))
                }
                put("updated_at", System.currentTimeMillis())
            }
            check(update("instances", values, "app_id = ? AND state = 'ready'", arrayOf(appId)) == 1) { "App not found" }
        }
        return getInstance(appId) ?: error("App not found")
    }

    fun getDevWorkspace(appId: String): DevWorkspace? = readableDatabase.query(
        "dev_workspaces", null, "app_id = ?", arrayOf(appId), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toDevWorkspace() else null }

    fun insertDevWorkspace(workspace: DevWorkspace): DevWorkspace {
        writableDatabase.insertOrThrow("dev_workspaces", null, workspace.values())
        return getDevWorkspace(workspace.appId) ?: error("Unable to create dev workspace")
    }

    fun commitDevWorkspace(appId: String, expectedRevision: Long, generation: String, treeHash: String,
        dirty: Boolean, baseReleaseId: String? = null): DevWorkspace {
        val values = ContentValues().apply {
            put("generation", generation)
            put("revision", expectedRevision + 1)
            put("tree_hash", treeHash)
            put("dirty", if (dirty) 1 else 0)
            baseReleaseId?.let { put("base_release_id", it) }
            put("updated_at", System.currentTimeMillis())
        }
        val changed = writableDatabase.update("dev_workspaces", values,
            "app_id = ? AND revision = ?", arrayOf(appId, expectedRevision.toString()))
        if (changed != 1) throw IllegalStateException("Dev workspace revision changed")
        return getDevWorkspace(appId) ?: error("Dev workspace disappeared")
    }

    fun deleteDevWorkspace(appId: String) {
        writableDatabase.inTransaction {
            update("instances", ContentValues().apply { put("launch_channel", LaunchChannel.STABLE.name) },
                "app_id = ?", arrayOf(appId))
            delete("dev_workspaces", "app_id = ?", arrayOf(appId))
        }
    }

    fun setFavorite(appId: String, favorite: Boolean): WebAppInstance {
        val changed = writableDatabase.update("instances", ContentValues().apply {
            put("favorite", if (favorite) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
        check(changed == 1) { "App not found" }
        return getInstance(appId) ?: error("App not found")
    }

    fun setNotificationEnabled(appId: String, enabled: Boolean): WebAppInstance {
        val changed = writableDatabase.update("instances", ContentValues().apply {
            put("notification_enabled", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
        check(changed == 1) { "App not found" }
        return getInstance(appId) ?: error("App not found")
    }

    fun setCrossOriginNetworkEnabled(appId: String, enabled: Boolean): WebAppInstance {
        val changed = writableDatabase.update("instances", ContentValues().apply {
            put("allow_cross_origin_network", if (enabled) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
        check(changed == 1) { "App not found" }
        return getInstance(appId) ?: error("App not found")
    }

    fun updateUrls(appId: String, liveUrl: String?, updateUrl: String?): WebAppInstance {
        val current = getInstance(appId) ?: throw IllegalArgumentException("App not found")
        if (current.runtimeMode == HappRuntimeMode.LIVE && liveUrl == null && current.activeReleaseId == null) {
            throw IllegalStateException("实时运行实例不能删除唯一入口")
        }
        val nextMode = if (liveUrl == null && current.runtimeMode == HappRuntimeMode.LIVE) HappRuntimeMode.LOCAL else current.runtimeMode
        val previousOrigin = current.liveUrl?.let(::originOf)
        val nextOrigin = liveUrl?.let(::originOf)
        writableDatabase.inTransaction {
            val values = ContentValues().apply {
                if (liveUrl == null) putNull("live_url") else put("live_url", liveUrl)
                if (updateUrl == null) putNull("update_url") else put("update_url", updateUrl)
                put("runtime_mode", nextMode.name)
                val target = if (nextMode == HappRuntimeMode.LIVE) requireNotNull(liveUrl) else liveUrl ?: current.localUrl
                put("start_url", target)
                put("primary_origin", originOf(target))
                if (previousOrigin != nextOrigin) put("trust_revision", current.trustRevision + 1)
                put("updated_at", System.currentTimeMillis())
            }
            check(update("instances", values, "app_id = ? AND state = 'ready'", arrayOf(appId)) == 1)
            if (previousOrigin != nextOrigin) delete("grants", "app_id = ? AND resource_scope != ''", arrayOf(appId))
        }
        return getInstance(appId) ?: error("App not found")
    }

    fun recordDownload(appId: String, url: String, versionCode: Long?, versionName: String?) {
        writableDatabase.update("instances", ContentValues().apply {
            put("download_url", url)
            if (versionCode == null) putNull("download_version_code") else put("download_version_code", versionCode)
            if (versionName == null) putNull("download_version_name") else put("download_version_name", versionName)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ?", arrayOf(appId))
    }

    /** Refreshes where this instance came from. Null values keep what is already stored. */
    fun recordPackageSource(appId: String, downloadUrl: String?, versionCode: Long?, versionName: String?, updateUrl: String?) {
        if (downloadUrl == null && updateUrl == null) return
        writableDatabase.update("instances", ContentValues().apply {
            if (downloadUrl != null) {
                put("download_url", downloadUrl)
                // The instance now follows an online address, so the local import path is stale.
                putNull("source_path"); putNull("source_uri")
                if (versionCode == null) putNull("download_version_code") else put("download_version_code", versionCode)
                if (versionName == null) putNull("download_version_name") else put("download_version_name", versionName)
            }
            if (updateUrl != null) put("update_url", updateUrl)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ?", arrayOf(appId))
    }

    /** Remembers the on-device location a package was imported from, so it can be reinstalled. */
    fun recordLocalSource(appId: String, sourcePath: String?, retainedPath: String?) {
        if (sourcePath == null && retainedPath == null) return
        writableDatabase.update("instances", ContentValues().apply {
            if (sourcePath != null) put("source_path", sourcePath)
            if (retainedPath != null) put("source_uri", retainedPath)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ?", arrayOf(appId))
    }

    /** Clears a remembered local source when the instance now follows an online address. */
    fun clearLocalSource(appId: String) {
        writableDatabase.update("instances", ContentValues().apply {
            putNull("source_path"); putNull("source_uri")
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ?", arrayOf(appId))
    }

    fun updatePresentation(appId: String, name: String, iconBytes: ByteArray? = null, replaceIcon: Boolean = false): WebAppInstance {
        if (getInstance(appId) == null) throw IllegalArgumentException("App not found")
        val iconUrl = if (replaceIcon && iconBytes != null) images.put(appId, iconBytes, "image/png") else null
        val changed = writableDatabase.update("instances", ContentValues().apply {
            put("name", name.trim().take(80))
            if (replaceIcon) {
                if (iconUrl == null) putNull("icon_url") else put("icon_url", iconUrl)
            }
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
        check(changed == 1) { "App not found" }
        val updated = getInstance(appId) ?: error("App not found")
        if (replaceIcon) images.pruneApp(appId, setOf(updated.iconUrl, updated.defaultIconUrl))
        return updated
    }

    fun updateDefaultIcon(appId: String, bytes: ByteArray?): WebAppInstance {
        val path = bytes?.let { images.put(appId, it, "image/png") }
        val changed = writableDatabase.update("instances", ContentValues().apply {
            if (path == null) putNull("default_icon_url") else put("default_icon_url", path)
            put("updated_at", System.currentTimeMillis())
        }, "app_id = ? AND state = 'ready'", arrayOf(appId))
        check(changed == 1) { "App not found" }
        val updated = getInstance(appId) ?: error("App not found")
        images.pruneApp(appId, setOf(updated.iconUrl, updated.defaultIconUrl))
        return updated
    }

    fun updateReleaseVersion(releaseId: String, versionName: String?) {
        writableDatabase.update("releases", ContentValues().apply {
            if (versionName.isNullOrBlank()) putNull("version_name") else put("version_name", versionName.trim().take(80))
        }, "release_id = ?", arrayOf(releaseId))
    }

    fun getGrant(appId: String, trustRevision: Long, capability: String, scope: String = ""): String? =
        readableDatabase.query("grants", arrayOf("decision"),
            "app_id = ? AND capability = ? AND resource_scope = ?",
            arrayOf(appId, capability, scope), null, null, "updated_at DESC", "1"
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putGrant(appId: String, trustRevision: Long, capability: String, decision: String, scope: String = "") {
        writableDatabase.delete("grants", "app_id = ? AND capability = ? AND resource_scope = ?", arrayOf(appId, capability, scope))
        val values = ContentValues().apply {
            put("app_id", appId); put("trust_revision", 0); put("capability", capability)
            put("resource_scope", scope); put("decision", decision); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("grants", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun grantsJson(appId: String, trustRevision: Long): JSONObject {
        val items = JSONArray()
        readableDatabase.query("grants", arrayOf("capability", "resource_scope", "decision", "updated_at"),
            "app_id = ?", arrayOf(appId), null, null, "capability, resource_scope"
        ).use { c ->
            while (c.moveToNext()) items.put(JSONObject()
                .put("capability", c.getString(0)).put("scope", c.getString(1))
                .put("decision", c.getString(2)).put("updatedAt", c.getLong(3)))
        }
        return JSONObject().put("grants", items)
    }

    fun clearGrant(appId: String, trustRevision: Long, capability: String, scope: String = ""): Boolean =
        writableDatabase.delete("grants", "app_id = ? AND capability = ? AND resource_scope = ?",
            arrayOf(appId, capability, scope)) > 0

    fun observeSystemPermission(permission: String, granted: Boolean) {
        writableDatabase.insertWithOnConflict("system_permission_observations", null, ContentValues().apply {
            put("permission", permission); put("granted", if (granted) 1 else 0)
            put("observed_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun systemPermissionsJson(): JSONArray {
        val items = JSONArray()
        readableDatabase.query("system_permission_observations", null, null, null, null, null, "permission").use { cursor ->
            while (cursor.moveToNext()) items.put(JSONObject()
                .put("permission", cursor.string("permission"))
                .put("granted", cursor.int("granted") != 0)
                .put("observedAt", cursor.long("observed_at")))
        }
        return items
    }

    fun recordDiagnostic(appId: String?, sessionId: String, method: String, decision: String, resultCode: String?, durationMs: Long) {
        runCatching {
            writableDatabase.inTransaction {
                insert("diagnostic_events", null, ContentValues().apply {
                    put("app_id", appId); put("session_id", sessionId.take(80)); put("method", method.take(128))
                    put("decision", decision.take(32)); put("result_code", resultCode?.take(64))
                    put("duration_ms", durationMs.coerceAtLeast(0)); put("created_at", System.currentTimeMillis())
                })
                execSQL("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY id DESC LIMIT 1000)")
            }
        }
    }

    fun diagnosticEventCount(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM diagnostic_events", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    fun createOperation(appId: String?, kind: String, idempotencyKey: String?, inputHash: String?, expected: String?): String {
        if (idempotencyKey != null) {
            val selection = if (appId == null) "app_id IS NULL AND kind = ? AND idempotency_key = ?" else "app_id = ? AND kind = ? AND idempotency_key = ?"
            val args = if (appId == null) arrayOf(kind, idempotencyKey) else arrayOf(appId, kind, idempotencyKey)
            readableDatabase.query("operations", arrayOf("operation_id", "input_hash"), selection,
                args, null, null, null
            ).use {
                if (it.moveToFirst()) {
                    if (it.getString(1) != inputHash) throw IllegalStateException("Idempotency key reused")
                    return it.getString(0)
                }
            }
        }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("operations", null, ContentValues().apply {
            put("operation_id", id); put("app_id", appId); put("kind", kind); put("state", "queued")
            put("idempotency_key", idempotencyKey); put("input_hash", inputHash); put("expected_release_id", expected)
            put("created_at", now); put("updated_at", now)
        })
        return id
    }

    fun updateOperation(id: String, state: String, resultReleaseId: String? = null, errorCode: String? = null, errorMessage: String? = null) {
        writableDatabase.update("operations", ContentValues().apply {
            put("state", state); put("result_release_id", resultReleaseId); put("error_code", errorCode)
            put("error_message", errorMessage?.take(400)); put("updated_at", System.currentTimeMillis())
        }, "operation_id = ?", arrayOf(id))
    }

    fun operationJson(id: String): org.json.JSONObject? =
        readableDatabase.query("operations", null, "operation_id = ?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) null else org.json.JSONObject()
                .put("operationId", c.string("operation_id")).put("appId", c.stringOrNull("app_id"))
                .put("kind", c.string("kind")).put("state", c.string("state"))
                .put("resultReleaseId", c.stringOrNull("result_release_id"))
                .put("errorCode", c.stringOrNull("error_code")).put("errorMessage", c.stringOrNull("error_message"))
                .put("createdAt", c.long("created_at")).put("updatedAt", c.long("updated_at"))
        }

    fun deleteInstance(appId: String): WebAppInstance? {
        val instance = getInstance(appId) ?: return null
        writableDatabase.inTransaction {
            execSQL("UPDATE instances SET state = 'deleting', updated_at = ? WHERE app_id = ?", arrayOf<Any>(System.currentTimeMillis(), appId))
            insertWithOnConflict("profile_cleanup", null, ContentValues().apply {
                put("profile_name", instance.webProfileName); put("app_id", appId); put("state", "pending")
                put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return instance
    }

    fun archiveInstance(appId: String): WebAppInstance? {
        val instance = getInstance(appId) ?: return null
        writableDatabase.inTransaction {
            delete("releases", "app_id = ?", arrayOf(appId))
            check(update("instances", ContentValues().apply {
                put("state", "archived"); putNull("active_release_id"); put("runtime_mode", "LOCAL")
                put("notification_enabled", 0); put("updated_at", System.currentTimeMillis())
            }, "app_id = ? AND state = 'ready'", arrayOf(appId)) == 1)
        }
        return instance
    }

    fun finishDelete(appId: String) {
        writableDatabase.delete("instances", "app_id = ? AND state = 'deleting'", arrayOf(appId))
    }

    fun pendingProfileCleanup(): List<Pair<String, String>> =
        readableDatabase.query("profile_cleanup", arrayOf("profile_name", "app_id"), "state = ?", arrayOf("pending"), null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    fun completeProfileCleanup(profileName: String) {
        writableDatabase.delete("profile_cleanup", "profile_name = ?", arrayOf(profileName))
    }

    fun recoverInterruptedOperations() {
        writableDatabase.execSQL(
            "UPDATE operations SET state = 'failed', error_code = 'E_CANCELLED', error_message = 'Process restarted; retry with the same idempotency key', updated_at = ? WHERE state IN ('queued','transferring','validating','committing')",
            arrayOf(System.currentTimeMillis())
        )
    }

    private fun WebAppInstance.values() = ContentValues().apply {
        put("app_id", appId); put("name", name)
        put("source_kind", source.name); put("runtime_mode", runtimeMode.name)
        put("launch_channel", launchChannel.name)
        put("start_url", startUrl); put("live_url", liveUrl)
        put("primary_origin", primaryOrigin); put("web_profile_name", webProfileName); put("trust_revision", trustRevision)
        put("active_release_id", activeReleaseId); put("active_data_generation", activeDataGeneration)
        put("source_adapter", sourceAdapter); put("source_spec", sourceSpec)
        put("developer_enabled", if (developerEnabled) 1 else 0); put("state", "ready")
        put("favorite", if (favorite) 1 else 0)
        put("icon_url", iconUrl)
        put("default_icon_url", defaultIconUrl)
        put("happ_id", happId); put("publisher_key_id", publisherKeyId)
        put("download_url", downloadUrl); put("download_version_code", downloadVersionCode)
        put("download_version_name", downloadVersionName); put("update_url", updateUrl)
        if (sourcePath == null) putNull("source_path") else put("source_path", sourcePath)
        if (sourceUri == null) putNull("source_uri") else put("source_uri", sourceUri)
        put("notification_enabled", if (notificationEnabled) 1 else 0)
        put("allow_cross_origin_network", if (allowCrossOriginNetwork) 1 else 0)
        put("created_at", createdAt); put("updated_at", updatedAt)
    }

    private fun CodeRelease.values() = ContentValues().apply {
        put("release_id", releaseId); put("app_id", appId); put("tree_hash", treeHash); put("provenance", provenance)
        put("version_code", versionCode); put("version_name", versionName); put("source_revision", sourceRevision)
        put("entry_path", entryPath)
        put("routing", routing); put("happ_id", happId); put("publisher_key_id", publisherKeyId)
        put("declared_build", declaredBuild)
        put("relative_root", relativeRoot); put("created_at", createdAt)
    }

    private fun Cursor.toInstance() = WebAppInstance(
        appId = string("app_id"), name = string("name"), source = HappSource.valueOf(string("source_kind")),
        runtimeMode = HappRuntimeMode.valueOf(string("runtime_mode")),
        launchChannel = LaunchChannel.valueOf(string("launch_channel")),
        startUrl = string("start_url"), liveUrl = stringOrNull("live_url"),
        primaryOrigin = string("primary_origin"), webProfileName = string("web_profile_name"),
        trustRevision = long("trust_revision"), activeReleaseId = stringOrNull("active_release_id"),
        activeDataGeneration = string("active_data_generation"), sourceAdapter = string("source_adapter"),
        sourceSpec = string("source_spec"), developerEnabled = int("developer_enabled") != 0,
        favorite = int("favorite") != 0,
        iconUrl = stringOrNull("icon_url"),
        createdAt = long("created_at"), updatedAt = long("updated_at"),
        happId = stringOrNull("happ_id"), publisherKeyId = stringOrNull("publisher_key_id"),
        downloadUrl = stringOrNull("download_url"), downloadVersionCode = longOrNull("download_version_code"),
        downloadVersionName = stringOrNull("download_version_name"), updateUrl = stringOrNull("update_url"),
        notificationEnabled = int("notification_enabled") != 0,
        allowCrossOriginNetwork = int("allow_cross_origin_network") != 0,
        defaultIconUrl = stringOrNull("default_icon_url"),
        sourcePath = stringOrNull("source_path"),
        sourceUri = stringOrNull("source_uri"),
    )

    private fun Cursor.toRelease() = CodeRelease(
        releaseId = string("release_id"), appId = string("app_id"), treeHash = string("tree_hash"),
        provenance = string("provenance"), versionCode = longOrNull("version_code"),
        versionName = stringOrNull("version_name"), sourceRevision = stringOrNull("source_revision"),
        entryPath = string("entry_path"),
        relativeRoot = string("relative_root"), createdAt = long("created_at"),
        routing = string("routing"), happId = stringOrNull("happ_id"), publisherKeyId = stringOrNull("publisher_key_id"),
        declaredBuild = stringOrNull("declared_build")
    )

    private fun DevWorkspace.values() = ContentValues().apply {
        put("app_id", appId); put("base_release_id", baseReleaseId); put("generation", generation)
        put("revision", revision); put("tree_hash", treeHash); put("dirty", if (dirty) 1 else 0)
        put("created_at", createdAt); put("updated_at", updatedAt)
    }

    private fun Cursor.toDevWorkspace() = DevWorkspace(
        appId = string("app_id"), baseReleaseId = string("base_release_id"), generation = string("generation"),
        revision = long("revision"), treeHash = string("tree_hash"), dirty = int("dirty") != 0,
        createdAt = long("created_at"), updatedAt = long("updated_at"),
    )

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.stringOrNull(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try { return block().also { setTransactionSuccessful() } } finally { endTransaction() }
    }

    private fun createSystemPermissionObservations(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS system_permission_observations (
              permission TEXT PRIMARY KEY,
              granted INTEGER NOT NULL CHECK(granted IN (0,1)),
              observed_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun createSettings(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS settings (
              setting_key TEXT PRIMARY KEY,
              setting_value TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    fun setting(key: String): String? = readableDatabase.query("settings", arrayOf("setting_value"),
        "setting_key = ?", arrayOf(key), null, null, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    fun setSetting(key: String, value: String) {
        val values = ContentValues().apply {
            put("setting_key", key); put("setting_value", value); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun createDevWorkspaces(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dev_workspaces (
              app_id TEXT PRIMARY KEY REFERENCES instances(app_id) ON DELETE CASCADE,
              base_release_id TEXT NOT NULL,
              generation TEXT NOT NULL,
              revision INTEGER NOT NULL,
              tree_hash TEXT NOT NULL,
              dirty INTEGER NOT NULL CHECK(dirty IN (0,1)),
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun localOrigin(appId: String) = "https://$appId.apps.haminn.invalid"

    private fun originOf(url: String): String {
        val uri = Uri.parse(url)
        val port = if (uri.port != -1 && !((uri.scheme.equals("https", true) && uri.port == 443) ||
                    (uri.scheme.equals("http", true) && uri.port == 80))) ":${uri.port}" else ""
        check(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "页面地址无效" }
        return "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}$port"
    }

    companion object {
        // 14 adds instances.source_path / source_uri so a locally imported package can be reinstalled.
        private const val VERSION = 15
        const val SETTING_THEME = "theme"
    }
}
