package rynfell.islom.shah

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

private val maximumCacheAge = Duration.ofHours(2)

internal data class PrayerVisit(val startedAt: Instant, val previousEntry: Instant?)

internal class PrayerVisitViewModel : ViewModel() {
    var visit: PrayerVisit? = null
}

internal data class CachedPrayerDay(val day: PrayerDay, val savedAt: Instant) {
    fun usableUntil(visit: PrayerVisit, now: Instant): Instant? {
        val today = now.atZone(dushanbeZone).toLocalDate()
        if (day.mosqueSchedule.date != today || savedAt > now || visit.startedAt > now) return null
        val relevantEntry = if (savedAt >= visit.startedAt) savedAt else visit.previousEntry ?: return null
        if (relevantEntry > now) return null
        return minOf(savedAt, relevantEntry).plus(maximumCacheAge).takeIf { it > now }
    }
}

internal class PrayerScheduleCache(context: Context) {
    private val preferences = context.getSharedPreferences("prayer_schedule_cache", Context.MODE_PRIVATE)

    fun beginVisit(now: Instant): PrayerVisit {
        val previous = preferences.getLong("last_entry_ms", 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
        preferences.edit { putLong("last_entry_ms", now.toEpochMilli()) }
        return PrayerVisit(now, previous)
    }

    fun save(day: PrayerDay, now: Instant) {
        val schedule = day.mosqueSchedule
        val times = JSONArray(Prayer.entries.map { schedule.timeOf(it).toString() })
        val windows = JSONArray(day.windows.map { window ->
            JSONArray(listOf(window.prayer.name, window.start.toString(), window.end.toString()))
        })
        val data = JSONObject()
            .put("savedAt", now.toEpochMilli())
            .put("date", schedule.date.toString())
            .put("times", times)
            .put("windows", windows)
        preferences.edit { putString("schedule", data.toString()) }
    }

    fun read(): CachedPrayerDay? {
        val data = preferences.getString("schedule", null) ?: return null
        return runCatching {
            val json = JSONObject(data)
            val date = LocalDate.parse(json.getString("date"))
            val times = json.getJSONArray("times")
            val (fajr, dhuhr, asr, maghrib, isha) = Prayer.entries.map { LocalTime.parse(times.getString(it.ordinal)) }
            val windows = json.getJSONArray("windows")
            val intervals = List(windows.length()) { index ->
                val window = windows.getJSONArray(index)
                PrayerWindow(
                    Prayer.valueOf(window.getString(0)),
                    LocalDateTime.parse(window.getString(1)),
                    LocalDateTime.parse(window.getString(2)),
                )
            }
            CachedPrayerDay(
                PrayerDay(PrayerSchedule(date, fajr, dhuhr, asr, maghrib, isha), intervals),
                Instant.ofEpochMilli(json.getLong("savedAt")),
            )
        }.getOrNull()
    }
}
