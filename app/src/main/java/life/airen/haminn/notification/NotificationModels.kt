package life.airen.haminn.notification

import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime

enum class Recurrence { ONCE, DAILY, WEEKLY, MONTHLY, YEARLY }

data class NotificationSpec(
    val id: String,
    val title: String,
    val body: String,
    val data: JSONObject,
) {
    fun toJson() = JSONObject().put("id", id).put("title", title).put("body", body).put("data", data)

    companion object {
        fun fromJson(json: JSONObject): NotificationSpec {
            rejectUnknown(json, setOf("id", "title", "body", "data"), "notification")
            val id = json.optString("id")
            val title = json.optString("title")
            val body = json.optString("body")
            if (!id.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) fail("通知 id 无效")
            if (title.isBlank() || title.length > 120 || body.length > 500) fail("通知标题或正文无效")
            val data = json.optJSONObject("data") ?: JSONObject()
            if (data.toString().toByteArray().size > 8 * 1024) fail("通知 data 过大")
            return NotificationSpec(id, title, body, data)
        }

        private fun fail(message: String): Nothing = throw HaminnException(ErrorCodes.INVALID_ARGUMENT, message)
    }
}

data class ScheduledNotification(
    val instanceId: String,
    val spec: NotificationSpec,
    val firstTriggerAt: Long,
    val anchorLocal: LocalDateTime,
    val nextTriggerAt: Long,
    val recurrence: Recurrence,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson() = spec.toJson().put("triggerAt", firstTriggerAt)
        .put("nextTriggerAt", nextTriggerAt).put("recurrence", recurrence.name.lowercase()).put("enabled", enabled)
}

object NotificationTime {
    fun nextAfter(firstTriggerAt: Long, recurrence: Recurrence, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val anchorLocal = Instant.ofEpochMilli(firstTriggerAt).atZone(zone).toLocalDateTime()
        return nextAfter(anchorLocal, recurrence, now, zone)
    }

    fun nextAfter(anchorLocal: LocalDateTime, recurrence: Recurrence, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (recurrence == Recurrence.ONCE) return null
        val first = anchorLocal.atZone(zone)
        var candidate = when (recurrence) {
            Recurrence.DAILY -> nextDaily(first, now, zone)
            Recurrence.WEEKLY -> nextWeekly(first, now, zone)
            Recurrence.MONTHLY -> nextMonthly(first, now, zone)
            Recurrence.YEARLY -> nextYearly(first, now, zone)
            Recurrence.ONCE -> return null
        }
        while (candidate.toInstant().toEpochMilli() <= now) candidate = when (recurrence) {
            Recurrence.DAILY -> candidate.plusDays(1)
            Recurrence.WEEKLY -> candidate.plusWeeks(1)
            Recurrence.MONTHLY -> monthlyCandidate(first, candidate.plusMonths(1).year, candidate.plusMonths(1).monthValue, zone)
            Recurrence.YEARLY -> yearlyCandidate(first, candidate.plusYears(1).year, zone)
            Recurrence.ONCE -> return null
        }
        return candidate.toInstant().toEpochMilli()
    }

    private fun nextDaily(first: ZonedDateTime, now: Long, zone: ZoneId): ZonedDateTime {
        val localNow = Instant.ofEpochMilli(now).atZone(zone)
        var value = localNow.toLocalDate().atTime(first.toLocalTime()).atZone(zone)
        if (value.toInstant().toEpochMilli() <= now) value = value.plusDays(1)
        return value
    }

    private fun nextWeekly(first: ZonedDateTime, now: Long, zone: ZoneId): ZonedDateTime {
        var value = nextDaily(first, now, zone)
        while (value.dayOfWeek != first.dayOfWeek) value = value.plusDays(1)
        return value
    }

    private fun nextMonthly(first: ZonedDateTime, now: Long, zone: ZoneId): ZonedDateTime {
        val localNow = Instant.ofEpochMilli(now).atZone(zone)
        var value = monthlyCandidate(first, localNow.year, localNow.monthValue, zone)
        if (value.toInstant().toEpochMilli() <= now) {
            val next = localNow.plusMonths(1)
            value = monthlyCandidate(first, next.year, next.monthValue, zone)
        }
        return value
    }

    private fun monthlyCandidate(first: ZonedDateTime, year: Int, month: Int, zone: ZoneId): ZonedDateTime {
        val date = java.time.LocalDate.of(year, month, 1)
        return date.withDayOfMonth(minOf(first.dayOfMonth, date.lengthOfMonth())).atTime(first.toLocalTime()).atZone(zone)
    }

    private fun nextYearly(first: ZonedDateTime, now: Long, zone: ZoneId): ZonedDateTime {
        val localNow = Instant.ofEpochMilli(now).atZone(zone)
        var value = yearlyCandidate(first, localNow.year, zone)
        if (value.toInstant().toEpochMilli() <= now) value = yearlyCandidate(first, localNow.year + 1, zone)
        return value
    }

    private fun yearlyCandidate(first: ZonedDateTime, year: Int, zone: ZoneId): ZonedDateTime {
        val monthDay = MonthDay.of(first.monthValue, minOf(first.dayOfMonth, first.month.length(java.time.Year.isLeap(year.toLong()))))
        return monthDay.atYear(year).atTime(first.toLocalTime()).atZone(zone)
    }
}

internal fun rejectUnknown(json: JSONObject, allowed: Set<String>, label: String) {
    val unknown = json.keys().asSequence().firstOrNull { it !in allowed }
    if (unknown != null) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "$label 包含未知字段：$unknown")
}
