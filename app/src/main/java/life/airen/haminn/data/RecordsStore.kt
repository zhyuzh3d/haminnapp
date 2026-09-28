package life.airen.haminn.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class RecordsStore(private val context: Context) {
    fun get(appId: String, generation: String, collection: String, key: String): JSONObject? {
        validateNames(collection, key)
        return open(appId, generation).use { db ->
            db.query("records", arrayOf("json_value", "revision"), "collection = ? AND record_key = ?",
                arrayOf(collection, key), null, null, null
            ).use { c -> if (!c.moveToFirst()) null else recordJson(generation, collection, key, c) }
        }
    }

    fun put(appId: String, generation: String, collection: String, key: String, value: Any?, expected: String?): JSONObject {
        validateNames(collection, key)
        val encoded = encodeValue(value)
        return open(appId, generation).use { db ->
            db.transaction {
                checkExpected(db, generation, collection, key, expected)
                ensureRecordQuota(db, collection, key, encoded)
                val revision = nextRevision(db)
                db.insertWithOnConflict("records", null, ContentValues().apply {
                    put("collection", collection); put("record_key", key); put("json_value", encoded); put("revision", revision)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                JSONObject().put("collection", collection).put("key", key).put("revision", revisionToken(generation, revision))
            }
        }
    }

    fun delete(appId: String, generation: String, collection: String, key: String, expected: String?): JSONObject {
        validateNames(collection, key)
        return open(appId, generation).use { db ->
            db.transaction {
                val current = checkExpected(db, generation, collection, key, expected)
                if (current == null) return@transaction JSONObject().put("deleted", false)
                nextRevision(db)
                db.delete("records", "collection = ? AND record_key = ?", arrayOf(collection, key))
                JSONObject().put("deleted", true)
            }
        }
    }

    fun scan(appId: String, generation: String, collection: String, prefix: String, afterKey: String?, limit: Int): JSONObject {
        validateCollection(collection)
        require(prefix.length <= MAX_KEY_LENGTH)
        val safeLimit = limit.coerceIn(1, MAX_SCAN)
        val selection = buildString {
            append("collection = ? AND record_key LIKE ? ESCAPE '\\'")
            if (afterKey != null) append(" AND record_key > ?")
        }
        val escapedPrefix = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val args = if (afterKey == null) arrayOf(collection, escapedPrefix) else arrayOf(collection, escapedPrefix, afterKey)
        return open(appId, generation).use { db ->
            val items = JSONArray()
            var last: String? = null
            db.query("records", arrayOf("record_key", "json_value", "revision"), selection, args, null, null,
                "record_key ASC", (safeLimit + 1).toString()
            ).use { c ->
                while (c.moveToNext() && items.length() < safeLimit) {
                    last = c.getString(0)
                    items.put(JSONObject().put("collection", collection).put("key", last)
                        .put("value", JSONObject(c.getString(1)).get("value"))
                        .put("revision", revisionToken(generation, c.getLong(2))))
                }
                val hasMore = !c.isAfterLast
                JSONObject().put("items", items).put("nextAfterKey", if (hasMore) last else JSONObject.NULL)
            }
        }
    }

    fun batch(appId: String, generation: String, operations: JSONArray): JSONObject {
        if (operations.length() !in 1..MAX_BATCH) throw HaminnException(ErrorCodes.QUOTA, "批量操作数量超限")
        val seen = HashSet<String>()
        val parsed = (0 until operations.length()).map { index ->
            val item = operations.getJSONObject(index)
            val collection = item.getString("collection")
            val key = item.getString("key")
            validateNames(collection, key)
            if (!seen.add("$collection\u0000$key")) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "批量操作包含重复键")
            item
        }
        return open(appId, generation).use { db ->
            db.transaction {
                parsed.forEach { item ->
                    checkExpected(db, generation, item.getString("collection"), item.getString("key"),
                        item.optString("expectedRevision").takeIf { item.has("expectedRevision") })
                }
                val results = JSONArray()
                parsed.forEach { item ->
                    val collection = item.getString("collection")
                    val key = item.getString("key")
                    when (item.getString("op")) {
                        "put" -> {
                            val encoded = encodeValue(item.opt("value"))
                            ensureRecordQuota(db, collection, key, encoded)
                            val revision = nextRevision(db)
                            db.insertWithOnConflict("records", null, ContentValues().apply {
                                put("collection", collection); put("record_key", key); put("json_value", encoded); put("revision", revision)
                            }, SQLiteDatabase.CONFLICT_REPLACE)
                            results.put(JSONObject().put("op", "put").put("key", key).put("revision", revisionToken(generation, revision)))
                        }
                        "delete" -> {
                            val deleted = db.delete("records", "collection = ? AND record_key = ?", arrayOf(collection, key)) > 0
                            nextRevision(db)
                            results.put(JSONObject().put("op", "delete").put("key", key).put("deleted", deleted))
                        }
                        else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "未知批量操作")
                    }
                }
                JSONObject().put("results", results)
            }
        }
    }

    fun exportJsonLines(appId: String, generation: String, output: OutputStream): Long {
        val writer = output.writer(Charsets.UTF_8)
        var count = 0L
        open(appId, generation).use { db ->
            db.query("records", arrayOf("collection", "record_key", "json_value"), null, null, null, null,
                "collection, record_key").use { c ->
                while (c.moveToNext()) {
                    writer.write(JSONObject().put("collection", c.getString(0)).put("key", c.getString(1))
                        .put("value", JSONObject(c.getString(2)).get("value")).toString())
                    writer.write("\n")
                    count++
                }
            }
        }
        writer.flush()
        return count
    }

    fun importJsonLines(appId: String, generation: String, input: InputStream): Long {
        var count = 0L
        open(appId, generation).use { db ->
            db.transaction {
                val existingUsage = recordUsage(db)
                var importedBytes = 0L
                input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.toByteArray(Charsets.UTF_8).size > MAX_EXPORT_LINE_BYTES) {
                            throw HaminnException(ErrorCodes.QUOTA, "备份记录行过大")
                        }
                        if (line.isBlank()) return@forEach
                        count++
                        if (count > MAX_IMPORT_RECORDS) throw HaminnException(ErrorCodes.QUOTA, "备份记录数量过多")
                        val item = JSONObject(line)
                        val collection = item.getString("collection")
                        val key = item.getString("key")
                        validateNames(collection, key)
                        val encoded = encodeValue(item.opt("value"))
                        importedBytes += encoded.toByteArray(Charsets.UTF_8).size
                        if (existingUsage.second + count > MAX_RECORDS || existingUsage.first + importedBytes > MAX_TOTAL_VALUE_BYTES) {
                            throw HaminnException(ErrorCodes.QUOTA, "备份记录超过应用配额")
                        }
                        val revision = nextRevision(db)
                        db.insertOrThrow("records", null, ContentValues().apply {
                            put("collection", collection); put("record_key", key); put("json_value", encoded); put("revision", revision)
                        })
                    }
                }
            }
        }
        return count
    }

    fun deleteGeneration(appId: String, generation: String) {
        require(appId.matches(Regex("[0-9a-fA-F-]{36}")))
        require(generation.matches(Regex("[0-9a-fA-F-]{36}")))
        File(context.filesDir, "instances/$appId/data/$generation").deleteRecursively()
    }

    /** Monotonic change counter of one data generation; 0 when nothing was ever written. */
    fun revision(appId: String, generation: String): Long = open(appId, generation).use { db ->
        db.rawQuery("SELECT revision_seq FROM meta WHERE id = 1", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    private fun open(appId: String, generation: String): SQLiteDatabase {
        require(appId.matches(Regex("[0-9a-fA-F-]{36}")))
        require(generation.matches(Regex("[0-9a-fA-F-]{36}")))
        val root = File(context.filesDir, "instances/$appId/data/$generation").apply { mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(File(root, "records.sqlite"), null).also { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (id INTEGER PRIMARY KEY CHECK(id=1), revision_seq INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO meta(id, revision_seq) VALUES(1, 0)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS records (
                  collection TEXT NOT NULL,
                  record_key TEXT NOT NULL,
                  json_value TEXT NOT NULL,
                  revision INTEGER NOT NULL,
                  PRIMARY KEY(collection, record_key)
                )
            """.trimIndent())
        }
    }

    private fun checkExpected(db: SQLiteDatabase, generation: String, collection: String, key: String, expected: String?): Long? {
        val current = db.query("records", arrayOf("revision"), "collection = ? AND record_key = ?",
            arrayOf(collection, key), null, null, null).use { if (it.moveToFirst()) it.getLong(0) else null }
        when {
            expected == null -> Unit
            expected == "absent" && current != null -> conflict()
            expected == "absent" -> Unit
            current == null -> conflict()
            expected != revisionToken(generation, current) -> conflict()
        }
        return current
    }

    private fun nextRevision(db: SQLiteDatabase): Long {
        db.execSQL("UPDATE meta SET revision_seq = revision_seq + 1 WHERE id = 1")
        return db.rawQuery("SELECT revision_seq FROM meta WHERE id = 1", null).use { it.moveToFirst(); it.getLong(0) }
    }

    private fun ensureRecordQuota(db: SQLiteDatabase, collection: String, key: String, encoded: String) {
        val usage = recordUsage(db)
        val previousBytes = db.rawQuery(
            "SELECT LENGTH(CAST(json_value AS BLOB)) FROM records WHERE collection = ? AND record_key = ?",
            arrayOf(collection, key),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (previousBytes == null && usage.second >= MAX_RECORDS) throw HaminnException(ErrorCodes.QUOTA, "记录数量已达上限")
        val nextBytes = usage.first - (previousBytes ?: 0L) + encoded.toByteArray(Charsets.UTF_8).size
        if (nextBytes > MAX_TOTAL_VALUE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "应用记录总量超过 128 MiB")
    }

    private fun recordUsage(db: SQLiteDatabase): Pair<Long, Long> =
        db.rawQuery("SELECT COALESCE(SUM(LENGTH(CAST(json_value AS BLOB))), 0), COUNT(*) FROM records", null).use {
            it.moveToFirst(); it.getLong(0) to it.getLong(1)
        }

    private fun recordJson(generation: String, collection: String, key: String, cursor: Cursor) =
        JSONObject().put("collection", collection).put("key", key)
            .put("value", JSONObject(cursor.getString(0)).get("value"))
            .put("revision", revisionToken(generation, cursor.getLong(1)))

    private fun encodeValue(value: Any?): String {
        val encoded = JSONObject().put("value", value ?: JSONObject.NULL).toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
            throw HaminnException(ErrorCodes.QUOTA, "JSON 记录超过 64 KiB")
        }
        return encoded
    }

    private fun revisionToken(generation: String, revision: Long) = "$generation:$revision"
    private fun conflict(): Nothing = throw HaminnException(ErrorCodes.CONFLICT, "记录版本冲突", true)

    private fun validateNames(collection: String, key: String) {
        validateCollection(collection)
        if (key.isBlank() || key.length > MAX_KEY_LENGTH || key.contains('\u0000')) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "非法记录键")
        }
    }

    private fun validateCollection(collection: String) {
        if (!collection.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "非法集合名")
        }
    }

    private inline fun <T> SQLiteDatabase.transaction(block: () -> T): T {
        beginTransaction()
        try { return block().also { setTransactionSuccessful() } } finally { endTransaction() }
    }

    companion object {
        private const val MAX_KEY_LENGTH = 512
        private const val MAX_VALUE_BYTES = 64 * 1024
        private const val MAX_SCAN = 100
        private const val MAX_BATCH = 100
        private const val MAX_EXPORT_LINE_BYTES = 128 * 1024
        private const val MAX_IMPORT_RECORDS = 100_000
        private const val MAX_RECORDS = 100_000L
        private const val MAX_TOTAL_VALUE_BYTES = 128L * 1024 * 1024
    }
}
