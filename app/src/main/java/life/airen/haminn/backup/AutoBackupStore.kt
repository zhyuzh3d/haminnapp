package life.airen.haminn.backup

import android.content.Context

/**
 * Local configuration and last-result state of the automatic full backup.
 * Everything here describes this device only; it is never part of a backup.
 */
class AutoBackupStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("haminn-auto-backup", Context.MODE_PRIVATE)

    data class Config(
        val enabled: Boolean,
        val treeUri: String?,
        val directoryName: String?,
        /** How many daily archives are kept; every day is one file. */
        val keepCount: Int,
        val hour: Int,
        val minute: Int,
        val configuredAt: Long,
        val lastRunDate: String?,
        val lastAttemptAt: Long,
        val lastStatus: String?,
        val lastMessage: String?,
        val lastFileName: String?,
        val lastBytes: Long,
        val lastFingerprint: String?,
        val needsPermission: Boolean,
    )

    fun snapshot(): Config = Config(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        treeUri = preferences.getString(KEY_TREE_URI, null),
        directoryName = preferences.getString(KEY_DIRECTORY_NAME, null),
        keepCount = keepCount(),
        hour = preferences.getInt(KEY_HOUR, DEFAULT_HOUR).coerceIn(0, 23),
        minute = preferences.getInt(KEY_MINUTE, DEFAULT_MINUTE).coerceIn(0, 59),
        configuredAt = preferences.getLong(KEY_CONFIGURED_AT, 0L),
        lastRunDate = preferences.getString(KEY_LAST_RUN_DATE, null),
        lastAttemptAt = preferences.getLong(KEY_LAST_ATTEMPT_AT, 0L),
        lastStatus = preferences.getString(KEY_LAST_STATUS, null),
        lastMessage = preferences.getString(KEY_LAST_MESSAGE, null),
        lastFileName = preferences.getString(KEY_LAST_FILE_NAME, null),
        lastBytes = preferences.getLong(KEY_LAST_BYTES, 0L),
        lastFingerprint = preferences.getString(KEY_LAST_FINGERPRINT, null),
        needsPermission = preferences.getBoolean(KEY_NEEDS_PERMISSION, false),
    )

    /** Day-based retention, always inside the range the picker offers. */
    fun keepCount(): Int = AutoBackupPlan.normalizeKeepCount(
        preferences.getInt(KEY_KEEP_COUNT, AutoBackupPlan.DEFAULT_KEEP_COUNT),
    )

    fun save(enabled: Boolean, treeUri: String?, directoryName: String?, keepCount: Int, hour: Int, minute: Int) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TREE_URI, treeUri)
            .putString(KEY_DIRECTORY_NAME, directoryName)
            .putInt(KEY_KEEP_COUNT, AutoBackupPlan.normalizeKeepCount(keepCount))
            .putInt(KEY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, minute.coerceIn(0, 59))
            .putLong(KEY_CONFIGURED_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordAttempt(at: Long) {
        preferences.edit().putLong(KEY_LAST_ATTEMPT_AT, at).apply()
    }

    fun recordSuccess(date: String, fileName: String, bytes: Long, fingerprint: String, message: String) {
        preferences.edit()
            .putString(KEY_LAST_RUN_DATE, date)
            .putString(KEY_LAST_STATUS, STATUS_SUCCESS)
            .putString(KEY_LAST_MESSAGE, message)
            .putString(KEY_LAST_FILE_NAME, fileName)
            .putLong(KEY_LAST_BYTES, bytes)
            .putString(KEY_LAST_FINGERPRINT, fingerprint)
            .putBoolean(KEY_NEEDS_PERMISSION, false)
            .apply()
    }

    fun recordSkip(date: String, fingerprint: String, message: String) {
        preferences.edit()
            .putString(KEY_LAST_RUN_DATE, date)
            .putString(KEY_LAST_STATUS, STATUS_SKIPPED)
            .putString(KEY_LAST_MESSAGE, message)
            .putString(KEY_LAST_FINGERPRINT, fingerprint)
            .putBoolean(KEY_NEEDS_PERMISSION, false)
            .apply()
    }

    fun recordFailure(message: String, needsPermission: Boolean) {
        preferences.edit()
            .putString(KEY_LAST_STATUS, STATUS_FAILED)
            .putString(KEY_LAST_MESSAGE, message)
            .putBoolean(KEY_NEEDS_PERMISSION, needsPermission)
            .apply()
    }

    fun recordInvalidPermission(message: String) {
        preferences.edit()
            .putString(KEY_LAST_STATUS, STATUS_FAILED)
            .putString(KEY_LAST_MESSAGE, message)
            .putBoolean(KEY_NEEDS_PERMISSION, true)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_SKIPPED = "skipped"
        const val STATUS_FAILED = "failed"
        const val DEFAULT_HOUR = 3
        const val DEFAULT_MINUTE = 0
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TREE_URI = "treeUri"
        private const val KEY_DIRECTORY_NAME = "directoryName"
        private const val KEY_KEEP_COUNT = "keepCount"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"
        private const val KEY_CONFIGURED_AT = "configuredAt"
        private const val KEY_LAST_RUN_DATE = "lastRunDate"
        private const val KEY_LAST_ATTEMPT_AT = "lastAttemptAt"
        private const val KEY_LAST_STATUS = "lastStatus"
        private const val KEY_LAST_MESSAGE = "lastMessage"
        private const val KEY_LAST_FILE_NAME = "lastFileName"
        private const val KEY_LAST_BYTES = "lastBytes"
        private const val KEY_LAST_FINGERPRINT = "lastFingerprint"
        private const val KEY_NEEDS_PERMISSION = "needsPermission"
    }
}
