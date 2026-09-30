package life.airen.haminn.install

import android.net.Uri
import android.util.Base64
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class PackageManifest(
    val schema: Int,
    val happId: String?,
    val name: String?,
    val author: String?,
    val versionCode: Long?,
    val versionName: String?,
    val entry: String,
    val routing: String,
    val icon: String?,
    val liveUrl: String?,
    val updateUrl: String?,
    val displayOrientation: String = "unspecified",
    val keyboardMode: String = "resize",
    /** Who last packaged this happ, as a stable place for it inside the committed manifest. */
    val maintainer: String? = null,
)

data class VerifiedPublisher(val keyId: String)

object PackageManifestReader {
    private val HAPP_ID = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")
    private val RESERVED_HAPP_IDS = setOf(
        "life.airen.haminn",
        "life.airen.haminnui",
    )
    private val HTTP_SCHEMES = setOf("http", "https")

    fun read(root: File): PackageManifest? {
        val file = File(root, "haminn.json")
        if (!file.exists()) return null
        if (file.length() > MAX_MANIFEST_BYTES) fail(ErrorCodes.QUOTA, "haminn.json 过大")
        return readText(file.readText(Charsets.UTF_8))
    }

    /** Parses a manifest that is already in memory, e.g. read straight out of a package. */
    fun readText(text: String): PackageManifest {
        val json = runCatching { JSONObject(text) }
            .getOrElse { fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 不是有效 JSON") }
        return when (val schema = json.optInt("schema", -1)) {
            1 -> readV1(json)
            2 -> readV2(json)
            else -> fail(ErrorCodes.UNSUPPORTED, "不支持的 haminn.json 版本：$schema")
        }
    }

    /**
     * What the packager chose to say about this package, for showing to a human — nothing else.
     *
     * Optional in every sense: absent, oversized or unparseable all mean "no declaration", never a
     * failed installation. A declaration is not evidence: the only facts worth acting on are the
     * ones this device computes itself (the content hash) and the ones it recorded itself (which
     * hashes it published). It is kept next to the release so a later question can show it.
     */
    fun readDeclaredBuild(root: File): String? {
        val file = File(root, DECLARED_BUILD_FILE)
        if (!file.isFile) return null
        if (file.length() > MAX_DECLARED_BYTES) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return if (json.length() == 0) null else json.toString()
    }

    fun verifyPublisher(root: File, treeHash: String): VerifiedPublisher? {
        val file = File(root, "haminn.sig")
        if (!file.exists()) return null
        if (file.length() > MAX_SIGNATURE_BYTES) fail(ErrorCodes.QUOTA, "haminn.sig 过大")
        val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
            .getOrElse { fail(ErrorCodes.INVALID_ARGUMENT, "haminn.sig 不是有效 JSON") }
        rejectUnknown(json, setOf("algorithm", "publicKey", "signature"), "haminn.sig")
        if (json.optString("algorithm") != "ECDSA_P256_SHA256") {
            fail(ErrorCodes.UNSUPPORTED, "haminn.sig 仅支持 ECDSA_P256_SHA256")
        }
        val keyBytes = decode(json.optString("publicKey"), "publicKey", 512)
        val signatureBytes = decode(json.optString("signature"), "signature", 256)
        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        }.getOrElse { fail(ErrorCodes.INVALID_ARGUMENT, "haminn.sig 公钥无效") }
        val signed = ("haminn-release-v1\u0000" + treeHash).toByteArray(Charsets.UTF_8)
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(signed) }.verify(signatureBytes)
        }.getOrDefault(false)
        if (!valid) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.sig 签名校验失败")
        return VerifiedPublisher(MessageDigest.getInstance("SHA-256").digest(keyBytes).hex())
    }

    private fun readV1(json: JSONObject): PackageManifest {
        rejectUnknown(json, setOf("schema", "name", "entry", "routing", "version"), "haminn.json")
        val name = optionalName(json)
        val version = optionalVersion(json, required = false)
        val entry = optionalPath(json, "entry") ?: "index.html"
        val routing = routing(json)
        return PackageManifest(1, null, name, null, version.first, version.second, entry, routing, null, null, null)
    }

    private fun readV2(json: JSONObject): PackageManifest {
        rejectUnknown(json, setOf("schema", "happId", "name", "author", "maintainer", "version", "entry", "routing", "icon", "liveUrl", "updateUrl", "display"), "haminn.json")
        val happId = requiredString(json, "happId")
        if (!HAPP_ID.matches(happId) || happId.length > 160) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 happId 无效")
        if (happId in RESERVED_HAPP_IDS) fail(ErrorCodes.PROTECTED_TARGET, "此 happId 保留给 HaminnUI，不能用于普通 happ")
        val name = requiredString(json, "name")
        if (name.length > 80) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 name 无效")
        val author = optionalText(json, "author", 80)
        val version = optionalVersion(json, required = true)
        val entry = optionalPath(json, "entry") ?: "index.html"
        val icon = optionalPath(json, "icon")
        val display = display(json)
        return PackageManifest(
            2, happId, name, author, version.first, version.second, entry, routing(json), icon,
            optionalNetworkUrl(json, "liveUrl"), optionalNetworkUrl(json, "updateUrl"),
            display.first, display.second, optionalText(json, "maintainer", 80),
        )
    }

    private fun display(json: JSONObject): Pair<String, String> {
        if (!json.has("display")) return "unspecified" to "resize"
        val display = json.optJSONObject("display")
            ?: fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 display 无效")
        rejectUnknown(display, setOf("orientation", "keyboard"), "haminn.json.display")
        val orientation = display.optString("orientation", "unspecified")
        val keyboard = display.optString("keyboard", "resize")
        if (orientation !in setOf("unspecified", "portrait", "landscape")) {
            fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json.display.orientation 无效")
        }
        if (keyboard !in setOf("resize", "overlay")) {
            fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json.display.keyboard 无效")
        }
        return orientation to keyboard
    }

    private fun optionalVersion(json: JSONObject, required: Boolean): Pair<Long?, String?> {
        val version = json.optJSONObject("version")
        if (version == null) {
            if (required || json.has("version")) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 version 无效")
            return null to null
        }
        rejectUnknown(version, setOf("code", "name"), "haminn.json.version")
        val code = if (version.has("code") && version.opt("code") is Number) version.getLong("code") else null
        val name = version.optString("name").takeIf { it.isNotBlank() }
        if (required && (code == null || name == null) || code != null && code <= 0 || name != null && name.length > 80) {
            fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 version 无效")
        }
        return code to name
    }

    private fun optionalName(json: JSONObject): String? {
        if (!json.has("name")) return null
        val value = json.opt("name") as? String
        if (value.isNullOrBlank() || value.length > 80) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 name 无效")
        return value
    }

    private fun optionalText(json: JSONObject, field: String, maxLength: Int): String? {
        if (!json.has(field)) return null
        val value = json.opt(field) as? String
        if (value.isNullOrBlank() || value.length > maxLength) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 $field 无效")
        return value.trim()
    }

    private fun routing(json: JSONObject): String = json.optString("routing", "hash").also {
        if (it !in setOf("hash", "history")) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 routing 无效")
    }

    private fun optionalPath(json: JSONObject, field: String): String? {
        if (!json.has(field)) return null
        val path = json.opt(field) as? String ?: fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 $field 无效")
        validatePath(path, field)
        return path
    }

    private fun optionalNetworkUrl(json: JSONObject, field: String): String? {
        if (!json.has(field)) return null
        val raw = json.opt(field) as? String ?: fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 $field 无效")
        val uri = Uri.parse(raw.trim())
        val host = uri.host?.lowercase()
        if (raw.length > 4096 || uri.scheme?.lowercase() !in HTTP_SCHEMES || host.isNullOrBlank() ||
            uri.userInfo != null || uri.fragment != null || isLocalHost(host) || host.endsWith(".haminn.invalid")) {
            fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 $field 必须是非本机 HTTP(S) 绝对地址")
        }
        return uri.buildUpon().scheme(uri.scheme!!.lowercase()).encodedAuthority(uri.encodedAuthority!!.lowercase()).build().toString()
    }

    private fun validatePath(path: String, field: String) {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." } || path.startsWith("__haminn/")) {
            fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 $field 路径无效")
        }
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "0.0.0.0" ||
            host.startsWith("127.") || host == "[::1]") return true
        if (':' !in host && !host.matches(Regex("[0-9.]+"))) return false
        return runCatching { java.net.InetAddress.getByName(host.removePrefix("[").removeSuffix("]")) }
            .getOrNull()?.let { it.isLoopbackAddress || it.isAnyLocalAddress } == true
    }

    private fun requiredString(json: JSONObject, field: String): String {
        val value = json.opt(field) as? String
        if (value.isNullOrBlank()) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 缺少有效 $field")
        return value
    }

    private fun rejectUnknown(json: JSONObject, allowed: Set<String>, label: String) {
        val unknown = json.keys().asSequence().firstOrNull { it !in allowed }
        if (unknown != null) fail(ErrorCodes.INVALID_ARGUMENT, "$label 包含未知字段：$unknown")
    }

    private fun decode(value: String, field: String, max: Int): ByteArray {
        val bytes = runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > max) fail(ErrorCodes.INVALID_ARGUMENT, "haminn.sig 的 $field 无效")
        return bytes
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun fail(code: String, message: String): Nothing = throw HaminnException(code, message)
    private const val MAX_MANIFEST_BYTES = 64L * 1024
    private const val MAX_SIGNATURE_BYTES = 8L * 1024
    private const val MAX_DECLARED_BYTES = 16L * 1024
    private const val DECLARED_BUILD_FILE = "haminn-build.json"
}
