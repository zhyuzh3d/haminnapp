package life.airen.haminn.notification

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class NotificationSync(
    val instanceId: String,
    val endpoint: String,
    val endpointOrigin: String,
    val cursor: String?,
    val etag: String?,
)

class NotificationRepository(context: Context) : SQLiteOpenHelper(context, "haminn-notifications.sqlite", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE schedules (
              instance_id TEXT NOT NULL, notification_id TEXT NOT NULL,
              title TEXT NOT NULL, body TEXT NOT NULL, data_json TEXT NOT NULL,
              first_trigger_at INTEGER NOT NULL, anchor_local TEXT NOT NULL, next_trigger_at INTEGER NOT NULL,
              recurrence TEXT NOT NULL CHECK(recurrence IN ('ONCE','DAILY','WEEKLY','MONTHLY','YEARLY')),
              enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
              PRIMARY KEY(instance_id, notification_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX schedules_due ON schedules(enabled, next_trigger_at)")
        db.execSQL("""
            CREATE TABLE sync (
              instance_id TEXT PRIMARY KEY, endpoint TEXT NOT NULL, endpoint_origin TEXT NOT NULL,
              cursor TEXT, etag TEXT, last_sync_at INTEGER, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE delivered (
              instance_id TEXT NOT NULL, notification_id TEXT NOT NULL, delivered_at INTEGER NOT NULL,
              PRIMARY KEY(instance_id, notification_id)
            )
        """.trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE schedules ADD COLUMN anchor_local TEXT")
            val rows = mutableListOf<Triple<String, String, Long>>()
            db.query("schedules", arrayOf("instance_id", "notification_id", "first_trigger_at"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) rows += Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
            rows.forEach { (instanceId, notificationId, triggerAt) ->
                val local = Instant.ofEpochMilli(triggerAt).atZone(ZoneId.systemDefault()).toLocalDateTime().toString()
                db.update("schedules", ContentValues().apply { put("anchor_local", local) },
                    "instance_id = ? AND notification_id = ?", arrayOf(instanceId, notificationId))
            }
        }
        if (newVersion > 2) error("Unsupported notification database version $newVersion")
    }

    fun upsert(instanceId: String, spec: NotificationSpec, triggerAt: Long, recurrence: Recurrence) {
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("schedules", null, ContentValues().apply {
            put("instance_id", instanceId); put("notification_id", spec.id); put("title", spec.title); put("body", spec.body)
            put("data_json", spec.data.toString()); put("first_trigger_at", triggerAt); put("next_trigger_at", triggerAt)
            put("anchor_local", Instant.ofEpochMilli(triggerAt).atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
            put("recurrence", recurrence.name); put("enabled", 1); put("created_at", now); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun due(now: Long): List<ScheduledNotification> = readableDatabase.query("schedules", null,
        "enabled = 1 AND next_trigger_at <= ?", arrayOf(now.toString()), null, null, "next_trigger_at ASC", "100")
        .use { c -> buildList { while (c.moveToNext()) add(c.schedule()) } }

    fun earliest(enabledInstanceIds: Set<String>): Long? {
        if (enabledInstanceIds.isEmpty()) return null
        val marks = enabledInstanceIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery("SELECT MIN(next_trigger_at) FROM schedules WHERE enabled = 1 AND instance_id IN ($marks)", enabledInstanceIds.toTypedArray()).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }

    fun list(instanceId: String): List<ScheduledNotification> = readableDatabase.query("schedules", null,
        "instance_id = ?", arrayOf(instanceId), null, null, "next_trigger_at ASC")
        .use { c -> buildList { while (c.moveToNext()) add(c.schedule()) } }

    fun allSchedules(): List<ScheduledNotification> = readableDatabase.query("schedules", null, null, null, null, null, "next_trigger_at ASC")
        .use { c -> buildList { while (c.moveToNext()) add(c.schedule()) } }

    fun complete(item: ScheduledNotification, now: Long) {
        val next = NotificationTime.nextAfter(item.anchorLocal, item.recurrence, now)
        if (next == null) writableDatabase.delete("schedules", "instance_id = ? AND notification_id = ?", arrayOf(item.instanceId, item.spec.id))
        else writableDatabase.update("schedules", ContentValues().apply { put("next_trigger_at", next); put("updated_at", now) },
            "instance_id = ? AND notification_id = ?", arrayOf(item.instanceId, item.spec.id))
    }

    fun recalculateRecurring(now: Long) {
        readableDatabase.query("schedules", null, "enabled = 1 AND recurrence != 'ONCE'", null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val item = cursor.schedule()
                val next = NotificationTime.nextAfter(item.anchorLocal, item.recurrence, now) ?: continue
                writableDatabase.update("schedules", ContentValues().apply { put("next_trigger_at", next); put("updated_at", now) },
                    "instance_id = ? AND notification_id = ?", arrayOf(item.instanceId, item.spec.id))
            }
        }
    }

    fun cancel(instanceId: String, id: String) = writableDatabase.delete("schedules", "instance_id = ? AND notification_id = ?", arrayOf(instanceId, id)) > 0
    fun cancelAll(instanceId: String) = writableDatabase.delete("schedules", "instance_id = ?", arrayOf(instanceId))
    fun deleteInstance(instanceId: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("schedules", "instance_id = ?", arrayOf(instanceId))
            writableDatabase.delete("sync", "instance_id = ?", arrayOf(instanceId))
            writableDatabase.delete("delivered", "instance_id = ?", arrayOf(instanceId))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun setEndpoint(instanceId: String, endpoint: String, endpointOrigin: String) {
        writableDatabase.insertWithOnConflict("sync", null, ContentValues().apply {
            put("instance_id", instanceId); put("endpoint", endpoint); put("endpoint_origin", endpointOrigin)
            putNull("cursor"); putNull("etag"); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearEndpoint(instanceId: String) = writableDatabase.delete("sync", "instance_id = ?", arrayOf(instanceId)) > 0
    fun sync(instanceId: String): NotificationSync? = readableDatabase.query("sync", null, "instance_id = ?", arrayOf(instanceId), null, null, null)
        .use { if (it.moveToFirst()) it.sync() else null }
    fun allSyncs(): List<NotificationSync> = readableDatabase.query("sync", null, null, null, null, null, "updated_at ASC")
        .use { c -> buildList { while (c.moveToNext()) add(c.sync()) } }
    fun updateSync(instanceId: String, cursor: String?, etag: String?) {
        writableDatabase.update("sync", ContentValues().apply {
            if (cursor == null) putNull("cursor") else put("cursor", cursor)
            if (etag == null) putNull("etag") else put("etag", etag)
            put("last_sync_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis())
        }, "instance_id = ?", arrayOf(instanceId))
    }

    fun markDelivered(instanceId: String, id: String): Boolean = writableDatabase.insertWithOnConflict("delivered", null, ContentValues().apply {
        put("instance_id", instanceId); put("notification_id", id); put("delivered_at", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    fun pruneDelivered(before: Long) = writableDatabase.delete("delivered", "delivered_at < ?", arrayOf(before.toString()))

    private fun Cursor.schedule() = ScheduledNotification(
        getString(getColumnIndexOrThrow("instance_id")),
        NotificationSpec(getString(getColumnIndexOrThrow("notification_id")), getString(getColumnIndexOrThrow("title")),
            getString(getColumnIndexOrThrow("body")), JSONObject(getString(getColumnIndexOrThrow("data_json")))),
        getLong(getColumnIndexOrThrow("first_trigger_at")),
        getString(getColumnIndexOrThrow("anchor_local")).let(LocalDateTime::parse),
        getLong(getColumnIndexOrThrow("next_trigger_at")),
        Recurrence.valueOf(getString(getColumnIndexOrThrow("recurrence"))), getInt(getColumnIndexOrThrow("enabled")) != 0,
        getLong(getColumnIndexOrThrow("created_at")), getLong(getColumnIndexOrThrow("updated_at")),
    )
    private fun Cursor.sync() = NotificationSync(getString(getColumnIndexOrThrow("instance_id")),
        getString(getColumnIndexOrThrow("endpoint")), getString(getColumnIndexOrThrow("endpoint_origin")),
        nullable("cursor"), nullable("etag"))
    private fun Cursor.nullable(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
}
