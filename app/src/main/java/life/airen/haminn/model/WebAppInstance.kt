package life.airen.haminn.model

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

enum class HappSource { ONLINE, LOCAL }
enum class HappRuntimeMode { LOCAL, LIVE }
enum class LaunchChannel { STABLE, DEV }

data class WebAppInstance(
    val appId: String,
    val name: String,
    val source: HappSource,
    val runtimeMode: HappRuntimeMode,
    val launchChannel: LaunchChannel,
    val startUrl: String,
    val liveUrl: String?,
    val primaryOrigin: String,
    val webProfileName: String,
    val trustRevision: Long,
    val activeReleaseId: String?,
    val activeDataGeneration: String,
    val sourceAdapter: String,
    val sourceSpec: String,
    val developerEnabled: Boolean,
    val favorite: Boolean,
    val iconUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val happId: String? = null,
    val publisherKeyId: String? = null,
    val downloadUrl: String? = null,
    val downloadVersionCode: Long? = null,
    val downloadVersionName: String? = null,
    val updateUrl: String? = null,
    val notificationEnabled: Boolean = false,
    val allowCrossOriginNetwork: Boolean = false,
    val defaultIconUrl: String? = null,
    /** Human readable location the package was installed from on this device. */
    val sourcePath: String? = null,
    /** Absolute path of the retained copy used to reinstall a locally imported package. */
    val sourceUri: String? = null,
) {
    val instanceId: String get() = appId
    val dataGenerationId: String get() = activeDataGeneration
    val localUrl: String get() = "https://$appId.apps.haminn.invalid/"
    val runtimeUrl: String get() = if (launchChannel == LaunchChannel.DEV) {
        liveUrl ?: localUrl
    } else if (runtimeMode == HappRuntimeMode.LIVE) {
        requireNotNull(liveUrl)
    } else liveUrl ?: localUrl
    val runtimeOrigin: String get() = originOf(runtimeUrl)
    val effectiveIconUrl: String? get() = iconUrl ?: defaultIconUrl
    val customIconUrl: String? get() = iconUrl

    fun toJson(): JSONObject = JSONObject()
        .put("appId", appId)
        .put("instanceId", instanceId)
        .put("dataGenerationId", dataGenerationId)
        .put("happId", happId ?: JSONObject.NULL)
        .put("name", name)
        .put("source", source.name.lowercase())
        .put("runtimeMode", runtimeMode.name.lowercase())
        .put("launchChannel", launchChannel.name.lowercase())
        .put("startUrl", runtimeUrl)
        .put("liveUrl", liveUrl ?: JSONObject.NULL)
        .put("origin", runtimeOrigin)
        .put("trustRevision", trustRevision)
        .put("activeReleaseId", activeReleaseId)
        .put("localAvailable", activeReleaseId != null)
        .put("liveAvailable", liveUrl != null)
        .put("downloadUrl", downloadUrl ?: JSONObject.NULL)
        .put("downloadVersion", versionJson(downloadVersionCode, downloadVersionName))
        .put("sourcePath", sourcePath ?: JSONObject.NULL)
        .put("sourceRetained", sourceUri != null)
        .put("updateUrl", updateUrl ?: JSONObject.NULL)
        .put("notificationEnabled", notificationEnabled)
        .put("allowCrossOriginNetwork", allowCrossOriginNetwork)
        .put("sourceAdapter", sourceAdapter)
        .put("developerEnabled", developerEnabled)
        .put("favorite", favorite)
        .put("iconUrl", effectiveIconUrl ?: JSONObject.NULL)
        .put("customIconUrl", customIconUrl ?: JSONObject.NULL)
        .put("defaultIconUrl", defaultIconUrl ?: JSONObject.NULL)
        .put("hasCustomIcon", iconUrl != null)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun originOf(url: String): String {
            val uri = Uri.parse(url)
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "页面地址无效" }
            require(!uri.host.isNullOrBlank()) { "页面地址无效" }
            val defaultPort = (uri.scheme.equals("https", true) && uri.port == 443) ||
                (uri.scheme.equals("http", true) && uri.port == 80)
            val port = if (uri.port != -1 && !defaultPort) ":${uri.port}" else ""
            return "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}$port"
        }

        private fun versionJson(code: Long?, name: String?): Any = if (code == null && name == null) {
            JSONObject.NULL
        } else JSONObject().put("code", code ?: JSONObject.NULL).put("name", name ?: JSONObject.NULL)

        fun newOnlineLive(name: String, url: String, favorite: Boolean = false): WebAppInstance {
            val appId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val origin = originOf(url)
            return WebAppInstance(
                appId = appId,
                name = name,
                source = HappSource.ONLINE,
                runtimeMode = HappRuntimeMode.LIVE,
                launchChannel = LaunchChannel.STABLE,
                startUrl = url,
                liveUrl = url,
                primaryOrigin = origin,
                webProfileName = "app-${appId.replace("-", "")}",
                trustRevision = 1,
                activeReleaseId = null,
                activeDataGeneration = UUID.randomUUID().toString(),
                sourceAdapter = "online",
                sourceSpec = JSONObject().put("url", url).toString(),
                developerEnabled = false,
                favorite = favorite,
                iconUrl = null,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
