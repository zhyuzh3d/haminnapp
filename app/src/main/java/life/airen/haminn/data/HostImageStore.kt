package life.airen.haminn.data

import android.content.Context
import android.graphics.BitmapFactory
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Private, content-addressed storage for images owned by HaminnApp itself.
 * Persistent metadata stores only the returned relative path, never image bytes
 * or a data URL. Page-owned files continue to use FileStore and logical IDs.
 */
class HostImageStore(private val context: Context) {
    data class StoredImage(val file: File, val mime: String, val objectUrl: String)

    @Synchronized
    fun put(appId: String, bytes: ByteArray, mime: String): String {
        validateAppId(appId)
        val normalizedMime = normalizeMime(mime)
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
            throw HaminnException(ErrorCodes.QUOTA, "图片对象大小无效")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "图片对象格式或尺寸无效")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val extension = EXTENSIONS.getValue(normalizedMime)
        val directory = File(context.filesDir, "objects/images/$appId").apply { mkdirs() }
        val target = File(directory, "$digest.$extension")
        if (!target.exists()) {
            val temporary = File(directory, ".incoming-${UUID.randomUUID()}")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (!temporary.renameTo(target) && !target.exists()) {
                    throw HaminnException(ErrorCodes.STORAGE, "无法提交图片对象")
                }
            } finally {
                temporary.delete()
            }
        }
        return objectUrl(appId, target.name)
    }

    fun open(url: String?): StoredImage? {
        val match = url?.let(URL::matchEntire) ?: return null
        val mime = when (match.groupValues[3]) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> return null
        }
        val relativePath = "objects/images/${match.groupValues[1]}/${match.groupValues[2]}.${match.groupValues[3]}"
        val file = File(context.filesDir, relativePath).canonicalFile
        val root = File(context.filesDir, "objects/images").canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return StoredImage(file, mime, url)
    }

    fun deleteApp(appId: String) {
        validateAppId(appId)
        val root = File(context.filesDir, "objects/images").canonicalFile
        val directory = File(root, appId).canonicalFile
        check(directory.path.startsWith(root.path + File.separator))
        directory.deleteRecursively()
    }

    fun pruneApp(appId: String, retainedUrls: Set<String?>) {
        validateAppId(appId)
        val keep = retainedUrls.mapNotNull(::open).map { it.file.canonicalPath }.toSet()
        val directory = File(context.filesDir, "objects/images/$appId")
        directory.listFiles()?.filter { it.isFile && !it.name.startsWith(".incoming-") }
            ?.filter { it.canonicalPath !in keep }
            ?.forEach(File::delete)
    }

    private fun validateAppId(appId: String) {
        require(appId.matches(APP_ID)) { "Invalid appId" }
    }

    private fun normalizeMime(mime: String): String = mime.lowercase().let {
        if (it == "image/jpg") "image/jpeg" else it
    }.takeIf(EXTENSIONS::containsKey)
        ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不支持的图片对象类型")

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private val APP_ID = Regex("[0-9a-fA-F-]{36}")
        private val URL = Regex("/__haminn/objects/images/([0-9a-fA-F-]{36})/([0-9a-f]{64})\\.(png|jpg|webp)")
        private val EXTENSIONS = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_DIMENSION = 4096

        private fun objectUrl(appId: String, fileName: String) = "/__haminn/objects/images/$appId/$fileName"
    }
}
