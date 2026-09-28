package life.airen.haminn.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class FileStore(private val context: Context) {
    data class StoredFile(val logicalId: String, val name: String, val mime: String, val size: Long, val sha256: String, val url: String)

    private class PendingWrite(
        val owner: String,
        val appId: String,
        val generation: String,
        val logicalId: String,
        val name: String,
        val mime: String,
        val temporary: File,
        val stream: FileOutputStream,
        val digest: MessageDigest,
        val appBytesAtStart: Long,
        var bytes: Long = 0L,
        var lastAccessAt: Long = System.currentTimeMillis(),
    )

    private val writes = LinkedHashMap<String, PendingWrite>()

    fun import(appId: String, generation: String, input: InputStream, name: String, mime: String): JSONObject {
        return importWithId(appId, generation, UUID.randomUUID().toString(), input, name, mime)
    }

    @Synchronized
    fun importWithId(appId: String, generation: String, logicalId: String, input: InputStream, name: String, mime: String): JSONObject {
        validateId(logicalId)
        val safeName = sanitizeName(name)
        val root = root(appId, generation)
        val temporary = File(root, ".incoming-$logicalId")
        val target = File(root, logicalId)
        if (target.exists() || metadata(appId, generation, logicalId) != null) {
            throw HaminnException(ErrorCodes.CONFLICT, "文件 ID 已存在")
        }
        val (currentBytes, currentFiles) = usage(appId, generation)
        if (currentFiles >= MAX_APP_FILES) throw HaminnException(ErrorCodes.QUOTA, "应用文件数量已达上限")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > MAX_FILE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "单个文件超过 $FILE_LIMIT_MIB MiB")
                    if (currentBytes + size > MAX_APP_FILE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "应用文件总量超过 256 MiB")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return commit(appId, generation, logicalId, temporary, safeName, mime, size, digest.digest().hex())
    }

    /**
     * The blocked alternative to a single oversized RPC message. A page opens a
     * write handle, appends 64 KiB chunks, then commits; nothing about the file
     * has to fit in one bridge message. Handles are owned by the calling page
     * session, so a navigation can never continue someone else's write, and an
     * abandoned handle is reaped by [pruneWrites] or by the next [usage] sweep.
     */
    @Synchronized
    fun beginWrite(owner: String, appId: String, generation: String, name: String, mime: String): JSONObject {
        pruneWrites()
        if (writes.values.count { it.owner == owner } >= MAX_WRITES_PER_SESSION || writes.size >= MAX_WRITES_TOTAL) {
            throw HaminnException(ErrorCodes.QUOTA, "未完成的分块写入数量已达上限")
        }
        val logicalId = UUID.randomUUID().toString()
        val root = root(appId, generation)
        if (File(root, logicalId).exists() || metadata(appId, generation, logicalId) != null) {
            throw HaminnException(ErrorCodes.CONFLICT, "文件 ID 已存在")
        }
        val (currentBytes, currentFiles) = usage(appId, generation)
        if (currentFiles >= MAX_APP_FILES) throw HaminnException(ErrorCodes.QUOTA, "应用文件数量已达上限")
        val writeId = UUID.randomUUID().toString()
        val temporary = File(root, ".writing-$logicalId")
        writes[writeId] = PendingWrite(
            owner = owner, appId = appId, generation = generation, logicalId = logicalId,
            name = sanitizeName(name), mime = normalizeMime(mime),
            temporary = temporary, stream = FileOutputStream(temporary),
            digest = MessageDigest.getInstance("SHA-256"), appBytesAtStart = currentBytes,
            lastAccessAt = System.currentTimeMillis(),
        )
        return JSONObject().put("writeId", writeId).put("maxChunkBytes", MAX_CHUNK_BYTES)
    }

    @Synchronized
    fun appendBytes(owner: String, appId: String, generation: String, writeId: String, chunkBase64: String): JSONObject {
        val handle = ownedWrite(writeId, owner, appId, generation)
        val chunk = if (chunkBase64.isEmpty()) ByteArray(0) else runCatching { Base64.decode(chunkBase64, Base64.DEFAULT) }
            .getOrElse { throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "chunkBase64 无效") }
        if (chunk.size > MAX_CHUNK_BYTES) throw HaminnException(ErrorCodes.QUOTA, "单个数据块超过 64 KiB")
        val total = handle.bytes + chunk.size
        if (total > MAX_FILE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "单个文件超过 $FILE_LIMIT_MIB MiB")
        if (handle.appBytesAtStart + total > MAX_APP_FILE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "应用文件总量超过 256 MiB")
        if (chunk.isNotEmpty()) {
            handle.stream.write(chunk)
            handle.digest.update(chunk)
            handle.bytes = total
        }
        handle.lastAccessAt = System.currentTimeMillis()
        return JSONObject().put("writeId", writeId).put("receivedBytes", handle.bytes).put("maxChunkBytes", MAX_CHUNK_BYTES)
    }

    @Synchronized
    fun finishWrite(owner: String, appId: String, generation: String, writeId: String): JSONObject {
        val handle = ownedWrite(writeId, owner, appId, generation)
        try {
            handle.stream.flush()
            handle.stream.fd.sync()
        } finally {
            runCatching { handle.stream.close() }
        }
        // The handle stays registered until the commit, because the sweep inside
        // usage() must not mistake this still-open temporary file for garbage.
        val (currentBytes, currentFiles) = usage(appId, generation)
        writes.remove(writeId)
        if (currentFiles >= MAX_APP_FILES) {
            handle.temporary.delete()
            throw HaminnException(ErrorCodes.QUOTA, "应用文件数量已达上限")
        }
        if (currentBytes + handle.bytes > MAX_APP_FILE_BYTES) {
            handle.temporary.delete()
            throw HaminnException(ErrorCodes.QUOTA, "应用文件总量超过 256 MiB")
        }
        return commit(appId, generation, handle.logicalId, handle.temporary, handle.name, handle.mime, handle.bytes, handle.digest.digest().hex())
    }

    @Synchronized
    fun abortWrite(owner: String, appId: String, generation: String, writeId: String): JSONObject {
        val handle = ownedWrite(writeId, owner, appId, generation)
        writes.remove(writeId)
        runCatching { handle.stream.close() }
        handle.temporary.delete()
        return JSONObject().put("writeId", writeId).put("aborted", true)
    }

    fun writeText(appId: String, generation: String, name: String, text: String): JSONObject {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_INLINE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "文本超过 256 KiB，请改用 files.beginWrite 分块写入")
        return import(appId, generation, bytes.inputStream(), name, "text/plain")
    }

    fun readText(appId: String, generation: String, logicalId: String, maxBytes: Int = MAX_INLINE_BYTES): JSONObject {
        val metadata = metadata(appId, generation, logicalId) ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "文件不存在")
        if (!metadata.mime.startsWith("text/") && metadata.mime !in setOf("application/json", "application/xml")) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "该文件不是可内联读取的文本")
        }
        val boundedMax = maxBytes.coerceIn(1, MAX_EXTENDED_INLINE_BYTES)
        if (metadata.size > boundedMax) throw HaminnException(ErrorCodes.QUOTA, "文件超过允许的内联读取上限")
        val text = file(appId, generation, logicalId).readText(Charsets.UTF_8)
        return metadata.json().put("text", text)
    }

    fun list(appId: String, generation: String): JSONObject {
        val array = JSONArray()
        openIndex(appId, generation).use { db ->
            db.query("files", null, null, null, null, null, "created_at ASC").use { c ->
                while (c.moveToNext()) array.put(StoredFile(
                    c.getString(c.getColumnIndexOrThrow("logical_id")),
                    c.getString(c.getColumnIndexOrThrow("display_name")),
                    c.getString(c.getColumnIndexOrThrow("mime")),
                    c.getLong(c.getColumnIndexOrThrow("size")),
                    c.getString(c.getColumnIndexOrThrow("sha256")),
                    c.getString(c.getColumnIndexOrThrow("object_url")),
                ).json())
            }
        }
        return JSONObject().put("files", array)
    }

    @Synchronized
    fun delete(appId: String, generation: String, logicalId: String): JSONObject {
        validateId(logicalId)
        if (metadata(appId, generation, logicalId) == null) return JSONObject().put("deleted", false)
        val source = file(appId, generation, logicalId)
        val stagedDelete = File(source.parentFile, ".deleted-${UUID.randomUUID()}")
        if (!source.renameTo(stagedDelete)) throw HaminnException(ErrorCodes.STORAGE, "无法准备删除文件")
        val removed = try {
            openIndex(appId, generation).use { db ->
                db.beginTransaction()
                try {
                    val rows = db.delete("files", "logical_id = ?", arrayOf(logicalId))
                    check(rows == 1) { "文件索引已经变化" }
                    db.setTransactionSuccessful()
                    true
                } finally { db.endTransaction() }
            }
        } catch (error: Throwable) {
            stagedDelete.renameTo(source)
            throw error
        }
        stagedDelete.delete()
        return JSONObject().put("deleted", removed)
    }

    fun metadata(appId: String, generation: String, logicalId: String): StoredFile? {
        validateId(logicalId)
        return openIndex(appId, generation).use { db ->
            db.query("files", null, "logical_id = ?", arrayOf(logicalId), null, null, null).use { c ->
                if (!c.moveToFirst()) null else StoredFile(
                    logicalId,
                    c.getString(c.getColumnIndexOrThrow("display_name")),
                    c.getString(c.getColumnIndexOrThrow("mime")),
                    c.getLong(c.getColumnIndexOrThrow("size")),
                    c.getString(c.getColumnIndexOrThrow("sha256")),
                    c.getString(c.getColumnIndexOrThrow("object_url")),
                )
            }
        }
    }

    fun open(appId: String, generation: String, logicalId: String): FileInputStream {
        if (metadata(appId, generation, logicalId) == null) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "文件不存在")
        return FileInputStream(file(appId, generation, logicalId))
    }

    fun listStored(appId: String, generation: String): List<StoredFile> {
        val items = mutableListOf<StoredFile>()
        openIndex(appId, generation).use { db ->
            db.query("files", null, null, null, null, null, "logical_id").use { c ->
                while (c.moveToNext()) items += StoredFile(
                    c.getString(c.getColumnIndexOrThrow("logical_id")),
                    c.getString(c.getColumnIndexOrThrow("display_name")),
                    c.getString(c.getColumnIndexOrThrow("mime")),
                    c.getLong(c.getColumnIndexOrThrow("size")),
                    c.getString(c.getColumnIndexOrThrow("sha256")),
                    c.getString(c.getColumnIndexOrThrow("object_url")),
                )
            }
        }
        return items
    }

    fun prepareShare(appId: String, generation: String, logicalId: String): Pair<Uri, StoredFile> {
        val metadata = metadata(appId, generation, logicalId)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "文件不存在")
        val shareRoot = File(context.filesDir, "shared").apply { mkdirs() }
        shareRoot.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > SHARE_TTL_MS }
            ?.forEach { it.deleteRecursively() }
        val directory = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(directory, sanitizeName(metadata.name))
        FileInputStream(file(appId, generation, logicalId)).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target) to metadata
    }

    private fun openIndex(appId: String, generation: String): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(File(root(appId, generation), "files.sqlite"), null).also { db ->
            db.enableWriteAheadLogging()
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS files (
                  logical_id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
                  mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                  object_url TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
            """.trimIndent())
            val hasObjectUrl = db.rawQuery("PRAGMA table_info(files)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "object_url") found = true
                found
            }
            if (!hasObjectUrl) {
                // File bodies are intentionally not reconstructed through a
                // legacy index. This test-device cutover removes stale metadata
                // and starts with the single current object-storage contract.
                db.execSQL("DROP TABLE IF EXISTS files")
                db.execSQL("""
                    CREATE TABLE files (
                      logical_id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
                      mime TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                      object_url TEXT NOT NULL,
                      created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                root(appId, generation).listFiles()
                    ?.filter { it.isFile && it.name.matches(ID) }
                    ?.forEach(File::delete)
            }
        }

    private fun usage(appId: String, generation: String): Pair<Long, Long> = openIndex(appId, generation).use { db ->
        root(appId, generation).listFiles { file -> file.name.startsWith(".deleted-") }?.forEach { it.delete() }
        // A temporary file is protected by its own modification time, not by the
        // in-memory handle table: an idle-but-live write keeps touching its file,
        // while a temporary file left behind by a killed process does not.
        val cutoff = System.currentTimeMillis() - WRITE_IDLE_TTL_MS
        root(appId, generation).listFiles { file -> file.name.startsWith(".writing-") && file.lastModified() < cutoff }
            ?.forEach { it.delete() }
        db.rawQuery("SELECT COALESCE(SUM(size), 0), COUNT(*) FROM files", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0) to cursor.getLong(1)
        }
    }

    /** Atomically publishes a fully written temporary file under its logical ID. */
    private fun commit(appId: String, generation: String, logicalId: String, temporary: File, safeName: String, mime: String, size: Long, hash: String): JSONObject {
        val target = File(root(appId, generation), logicalId)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw HaminnException(ErrorCodes.STORAGE, "无法提交文件")
        }
        try {
            openIndex(appId, generation).use { db ->
                db.insertOrThrow("files", null, ContentValues().apply {
                    put("logical_id", logicalId); put("display_name", safeName); put("mime", normalizeMime(mime))
                    put("size", size); put("sha256", hash); put("object_url", objectUrl(logicalId)); put("created_at", System.currentTimeMillis())
                })
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return StoredFile(logicalId, safeName, normalizeMime(mime), size, hash, objectUrl(logicalId)).json()
    }

    private fun ownedWrite(writeId: String, owner: String, appId: String, generation: String): PendingWrite {
        val handle = writes[writeId] ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分块写入不存在或已经结束")
        if (handle.owner != owner || handle.appId != appId || handle.generation != generation) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "分块写入不属于当前页面会话")
        }
        return handle
    }

    private fun pruneWrites() {
        val cutoff = System.currentTimeMillis() - WRITE_IDLE_TTL_MS
        writes.entries.toList().forEach { (id, handle) ->
            if (handle.lastAccessAt >= cutoff) return@forEach
            writes.remove(id)
            runCatching { handle.stream.close() }
            handle.temporary.delete()
        }
    }

    private fun root(appId: String, generation: String): File {
        require(appId.matches(ID))
        require(generation.matches(ID))
        return File(context.filesDir, "instances/$appId/data/$generation/files").apply { mkdirs() }
    }
    private fun file(appId: String, generation: String, id: String): File {
        validateId(id)
        return File(root(appId, generation), id)
    }
    private fun validateId(id: String) {
        if (!id.matches(ID)) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "非法文件 ID")
    }
    private fun sanitizeName(name: String): String =
        name.replace(Regex("[\\/\u0000-\u001F]"), "_").trim().take(120).ifBlank { "file" }
    private fun normalizeMime(mime: String): String =
        mime.lowercase().takeIf { it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) } ?: "application/octet-stream"
    private fun StoredFile.json() = JSONObject().put("logicalFileId", logicalId).put("url", url).put("name", name)
        .put("mime", mime).put("size", size).put("sha256", sha256)
    private fun objectUrl(logicalId: String) = "/__haminn/files/$logicalId"
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private val ID = Regex("[0-9a-fA-F-]{36}")
        /**
         * A single logical file may be as large as the whole per-application budget: a screen
         * recording that the caller asked to keep in one piece is exactly the size of the
         * capture, and it is the caller's job to segment it if it wants room for anything
         * else. The message names the limit instead of repeating the number.
         */
        private const val MAX_FILE_BYTES = 256L * 1024 * 1024
        private const val MAX_APP_FILE_BYTES = 256L * 1024 * 1024
        private val FILE_LIMIT_MIB = MAX_FILE_BYTES / (1024 * 1024)
        private const val MAX_APP_FILES = 10_000L
        private const val MAX_INLINE_BYTES = 256 * 1024
        private const val MAX_EXTENDED_INLINE_BYTES = 8 * 1024 * 1024
        private const val MAX_CHUNK_BYTES = 64 * 1024
        private const val MAX_WRITES_PER_SESSION = 2
        private const val MAX_WRITES_TOTAL = 4
        private const val WRITE_IDLE_TTL_MS = 2 * 60 * 1000L
        private const val SHARE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
