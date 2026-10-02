package life.airen.haminn.install

import android.content.Context
import life.airen.haminn.model.HappSource
import life.airen.haminn.registry.AppRegistry
import org.json.JSONObject
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installs only missing apps carried by the Official Integrated Version APK. */
class OfficialIntegratedAppsInstaller(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
) {
    private data class Entry(
        val happId: String,
        val name: String,
        val packagePath: String,
        val sha256: String,
    )

    fun hasBundle(): Boolean = try {
        context.assets.open(CATALOG_PATH).use { true }
    } catch (_: FileNotFoundException) {
        false
    }

    suspend fun installMissingApps() = withContext(Dispatchers.IO) {
        val entries = readCatalog() ?: return@withContext
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val handledIds = preferences.getStringSet(KEY_HANDLED_IDS, emptySet()).orEmpty().toMutableSet()
        entries.forEach { entry ->
            if (entry.happId in handledIds) return@forEach
            // Treat archived entries as user data too. An OIV upgrade must not silently
            // restore a package that the user previously archived.
            if (registry.findAnyReady(entry.happId) != null || registry.findAnyArchived(entry.happId) != null) {
                markHandled(preferences, handledIds, entry.happId)
                return@forEach
            }
            context.assets.open("$ASSET_ROOT/${entry.packagePath}").use { archive ->
                installer.installZip(
                    input = archive,
                    suggestedName = entry.name,
                    provenance = PROVENANCE,
                    declaredSha256 = entry.sha256,
                    source = HappSource.LOCAL,
                )
            }
            check(registry.findAnyReady(entry.happId) != null) {
                "集成包安装后未找到 ${entry.name}（${entry.happId}）"
            }
            markHandled(preferences, handledIds, entry.happId)
        }
    }

    private fun markHandled(
        preferences: android.content.SharedPreferences,
        handledIds: MutableSet<String>,
        happId: String,
    ) {
        handledIds += happId
        check(preferences.edit().putStringSet(KEY_HANDLED_IDS, handledIds.toSet()).commit()) {
            "无法保存官方集成应用安装状态"
        }
    }

    private fun readCatalog(): List<Entry>? {
        val json = try {
            context.assets.open(CATALOG_PATH).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } catch (_: FileNotFoundException) {
            return null
        }
        require(json.optInt("schema") == 1) { "官方集成包清单版本不受支持" }
        val apps = json.optJSONArray("apps") ?: error("官方集成包清单缺少 apps")
        require(apps.length() in 1..32) { "官方集成包清单中的应用数量无效" }
        val ids = mutableSetOf<String>()
        return (0 until apps.length()).map { index ->
            val app = apps.optJSONObject(index) ?: error("官方集成包清单第 ${index + 1} 项无效")
            val happId = app.optString("happId").trim()
            val name = app.optString("name").trim()
            val packagePath = app.optString("package").trim()
            val sha256 = app.optString("sha256").trim().lowercase()
            require(happId.matches(HAPP_ID_PATTERN) && ids.add(happId)) { "官方集成包中 happId 无效或重复" }
            require(name.isNotBlank() && name.length <= 80) { "官方集成包中的应用名称无效" }
            require(packagePath.matches(PACKAGE_PATH_PATTERN)) { "官方集成包中的 ZIP 路径无效" }
            require(sha256.matches(SHA256_PATTERN)) { "官方集成包中的 SHA-256 无效" }
            Entry(happId, name, packagePath, sha256)
        }
    }

    companion object {
        private const val ASSET_ROOT = "oiv"
        private const val CATALOG_PATH = "$ASSET_ROOT/catalog.json"
        private const val PROVENANCE = "official-integrated"
        private const val PREFERENCES_NAME = "official-integrated-apps"
        private const val KEY_HANDLED_IDS = "handled-happ-ids"
        private val HAPP_ID_PATTERN = Regex("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*")
        private val PACKAGE_PATH_PATTERN = Regex("happs/[A-Za-z0-9._-]+\\.zip")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
