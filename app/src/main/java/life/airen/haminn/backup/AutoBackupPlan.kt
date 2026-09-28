package life.airen.haminn.backup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure planning rules of the automatic backup: one archive per day, which
 * directory entries belong to this feature, how many days are kept, and when
 * the daily run is due. Free of Android APIs so the safety rules stay testable.
 *
 * A day owns exactly one file, so running several times in one day never costs
 * a previously stored day: the run only replaces today's own archive.
 */
internal object AutoBackupPlan {
    const val PREFIX = "Haminn-auto-"
    const val DAY = "yyyyMMdd"
    const val PART = ".part"
    const val DEFAULT_KEEP_COUNT = 3
    const val MIN_KEEP_COUNT = 1
    const val MAX_KEEP_COUNT = 10
    private const val SUFFIX = ".haminn-backup.zip"
    private val DAY_FORMAT = DateTimeFormatter.ofPattern(DAY)

    fun fileName(day: String) = "$PREFIX$day$SUFFIX"

    /** Today's key in the same shape [fileName] expects. */
    fun dayKey(date: LocalDate): String = date.format(DAY_FORMAT)

    fun normalizeKeepCount(value: Int) = value.coerceIn(MIN_KEEP_COUNT, MAX_KEEP_COUNT)

    /**
     * The day key stored in an archive name, or null when the entry is not an
     * archive of this feature. Both the current `yyyyMMdd` names and the
     * time-stamped names written by earlier versions are recognised, so an
     * upgraded install still folds a day down to a single file.
     */
    fun dayOf(name: String): String? {
        if (!name.startsWith(PREFIX) || !name.endsWith(".zip") || name.contains(PART)) return null
        val rest = name.removePrefix(PREFIX)
        if (rest.length < DAY.length) return null
        val day = rest.take(DAY.length)
        if (!day.all { it.isDigit() }) return null
        // What follows the day separates our names (`…20260921.haminn-backup.zip`,
        // the older `…20260921-030000…`) from anything that merely shares the prefix.
        return when (rest.getOrNull(DAY.length)) {
            null, '-', '.', ' ' -> day
            else -> null
        }
    }

    fun isTemporary(name: String) = name.startsWith(PREFIX) && name.contains(PART)

    /**
     * Entries a finished run no longer needs: leftover archives of the same day
     * that [committedName] has just replaced, and whole days older than the
     * newest [keepCount] days. [committedName] itself is never returned, and
     * files this feature did not write are never touched.
     */
    fun selectForRemoval(names: List<String>, keepCount: Int, committedName: String): List<String> {
        val committedDay = dayOf(committedName)
        val days = names.mapNotNull { dayOf(it) }.distinct().sortedDescending()
        val expired = days.drop(normalizeKeepCount(keepCount)).toSet()
        return names.filter { name ->
            if (name == committedName) return@filter false
            val day = dayOf(name) ?: return@filter false
            day == committedDay || day in expired
        }
    }

    /** Next occurrence of a local wall-clock time, recomputed across DST. */
    fun nextRunAt(now: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val localDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val today = localDate.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        if (today > now) return today
        return localDate.plusDays(1).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }
}
