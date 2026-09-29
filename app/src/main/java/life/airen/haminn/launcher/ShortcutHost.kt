package life.airen.haminn.launcher

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import life.airen.haminn.MainActivity
import life.airen.haminn.data.HostImageStore
import life.airen.haminn.install.IconProcessor
import life.airen.haminn.R
import life.airen.haminn.model.WebAppInstance

class ShortcutHost(private val context: Context) {
    private val manager = context.getSystemService(ShortcutManager::class.java)
    private val images = HostImageStore(context)

    enum class PinState(val value: String) {
        PINNED("pinned"),
        NOT_PINNED("notPinned"),
        UNSUPPORTED("unsupported"),
        UNKNOWN("unknown"),
    }

    /**
     * ShortcutManager is the authoritative public API for shortcuts owned by
     * this package. Call this from a worker thread: some launchers may take
     * noticeable time to return their pinned shortcut list.
     */
    fun pinStates(instances: Collection<WebAppInstance>): Map<String, PinState> {
        val supported = runCatching { manager.isRequestPinShortcutSupported }.getOrNull()
        val pinned = runCatching { manager.pinnedShortcuts.filter(ShortcutInfo::isPinned) }
            .getOrElse { return instances.associate { it.appId to PinState.UNKNOWN } }
        val byAppId = instances.associateBy(WebAppInstance::appId)
        val pinnedAppIds = buildSet {
            pinned.forEach { shortcut ->
                val intent = shortcut.intent
                val direct = intent?.getStringExtra(MainActivity.EXTRA_APP_ID)?.let(byAppId::get)
                val resolved = direct ?: resolveStableIdentity(
                    instances,
                    intent?.getStringExtra(MainActivity.EXTRA_HAPP_ID),
                    intent?.getStringExtra(MainActivity.EXTRA_PUBLISHER_KEY_ID),
                )
                if (resolved != null) {
                    add(resolved.appId)
                    val identityChanged = direct == null ||
                        intent.getStringExtra(MainActivity.EXTRA_HAPP_ID) != resolved.happId ||
                        intent.getStringExtra(MainActivity.EXTRA_PUBLISHER_KEY_ID) != resolved.publisherKeyId
                    if (identityChanged) runCatching { updateShortcut(shortcut.id, resolved) }
                }
            }
        }
        val pinnedIds = pinned.map(ShortcutInfo::getId).toSet()
        return instances.associate { instance ->
            val appId = instance.appId
            when {
                appId in pinnedAppIds || shortcutId(appId) in pinnedIds -> appId to PinState.PINNED
                supported == true -> appId to PinState.NOT_PINNED
                supported == false -> appId to PinState.UNSUPPORTED
                else -> appId to PinState.UNKNOWN
            }
        }
    }

    fun requestPin(instance: WebAppInstance): Boolean {
        if (!manager.isRequestPinShortcutSupported) return false
        val shortcut = shortcut(shortcutId(instance.appId), instance)
        return manager.requestPinShortcut(shortcut, null)
    }

    enum class PinOutcome(val value: String) {
        REQUESTED("requested"),
        ALREADY_PINNED("alreadyPinned"),
        UNSUPPORTED("unsupported"),
    }

    /**
     * Installing something means wanting to open it, so a freshly installed happ
     * asks for its desktop icon straight away. The pinned set is read first, so an
     * app that already has its icon is left alone and one the user removed on
     * purpose is not put back behind their back. The launcher still owns the final
     * tap: requestPinShortcut is a request, not an insertion.
     */
    fun requestPinIfAbsent(instance: WebAppInstance): PinOutcome {
        when (pinStates(listOf(instance))[instance.appId] ?: PinState.UNKNOWN) {
            PinState.PINNED -> return PinOutcome.ALREADY_PINNED
            PinState.UNSUPPORTED -> return PinOutcome.UNSUPPORTED
            PinState.NOT_PINNED, PinState.UNKNOWN -> Unit
        }
        return if (requestPin(instance)) PinOutcome.REQUESTED else PinOutcome.UNSUPPORTED
    }

    fun update(instance: WebAppInstance) {
        val ids = runCatching {
            manager.pinnedShortcuts.asSequence()
                .filter { it.intent?.getStringExtra(MainActivity.EXTRA_APP_ID) == instance.appId }
                .map(ShortcutInfo::getId)
                .toMutableSet()
        }.getOrDefault(mutableSetOf())
        ids += shortcutId(instance.appId)
        manager.updateShortcuts(ids.map { shortcut(it, instance) })
    }

    fun retarget(previousAppId: String?, instance: WebAppInstance) {
        val oldId = previousAppId?.takeIf { it.isNotBlank() }?.let(::shortcutId) ?: return
        updateShortcut(oldId, instance)
    }

    fun disable(appId: String) {
        val ids = runCatching {
            manager.pinnedShortcuts.asSequence()
                .filter { it.intent?.getStringExtra(MainActivity.EXTRA_APP_ID) == appId }
                .map(ShortcutInfo::getId)
                .toMutableSet()
        }.getOrDefault(mutableSetOf())
        ids += shortcutId(appId)
        manager.disableShortcuts(ids.toList(), "此页面应用已被删除")
    }

    private fun shortcutId(appId: String) = "webapp-$appId"

    private fun updateShortcut(id: String, instance: WebAppInstance) {
        manager.updateShortcuts(listOf(shortcut(id, instance)))
    }

    private fun shortcut(id: String, instance: WebAppInstance) = ShortcutInfo.Builder(context, id)
        .setShortLabel(instance.name.take(10))
        .setLongLabel(instance.name.take(25))
        .setIcon(iconFor(instance))
        .setIntent(HappTaskHost.intent(context, instance))
        .build()

    private fun resolveStableIdentity(
        instances: Collection<WebAppInstance>,
        happId: String?,
        publisherKeyId: String?,
    ): WebAppInstance? {
        val stableId = happId?.takeIf { it.isNotBlank() } ?: return null
        val matches = instances.filter { instance ->
            instance.happId == stableId && (publisherKeyId.isNullOrBlank() || instance.publisherKeyId == publisherKeyId)
        }
        return matches.singleOrNull()
    }

    private fun iconFor(instance: WebAppInstance): Icon {
        val source = images.open(instance.effectiveIconUrl)?.let { image ->
            runCatching { BitmapFactory.decodeFile(image.file.absolutePath) }.getOrNull()
        } ?: return Icon.createWithResource(context, R.mipmap.ic_launcher)
        // The pin dialog and the desktop tile both take this bitmap and clip nothing
        // themselves, so the corner has to be in the pixels. The launcher may mask the
        // tile afterwards; this code does not try to predict or match that mask, and
        // CORNER_RATIO stays below any plausible one so the system's shape wins.
        val rounded = IconProcessor.rounded(source)
        source.recycle()
        return Icon.createWithBitmap(rounded)
    }
}
