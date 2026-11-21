package rynfell.islom.shah

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val dushanbeZone: ZoneId = ZoneId.of("Asia/Dushanbe")
private const val scheduleUrl = "https://taqvim.tj/main_namaz_tj.php"
private val websiteDateFormat = DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

internal data class PrayerSchedule(
    val date: LocalDate,
    val fajr: LocalTime,
    val dhuhr: LocalTime,
    val asr: LocalTime,
    val maghrib: LocalTime,
    val isha: LocalTime,
) {
    fun timeOf(prayer: Prayer): LocalTime = when (prayer) {
        Prayer.Fajr -> fajr
        Prayer.Dhuhr -> dhuhr
        Prayer.Asr -> asr
        Prayer.Maghrib -> maghrib
        Prayer.Isha -> isha
    }
}

internal val Prayer.nameResource: Int
    get() = when (this) {
        Prayer.Fajr -> R.string.prayer_fajr
        Prayer.Dhuhr -> R.string.prayer_dhuhr
        Prayer.Asr -> R.string.prayer_asr
        Prayer.Maghrib -> R.string.prayer_maghrib
        Prayer.Isha -> R.string.prayer_isha
    }

internal sealed interface PrayerScheduleState {
    data object Loading : PrayerScheduleState
    data object Unavailable : PrayerScheduleState
    data class Ready(val day: PrayerDay, val fromCache: Boolean = false) : PrayerScheduleState
}

internal enum class Prayer(val websiteKey: String) {
    Fajr("bomdod"),
    Dhuhr("peshin"),
    Asr("asr"),
    Maghrib("shom"),
    Isha("khuftan"),
}

internal data class PrayerWindow(
    val prayer: Prayer,
    val start: LocalDateTime,
    val end: LocalDateTime,
)

internal data class PrayerCountdown(val prayer: Prayer, val untilEnd: Boolean, val seconds: Long)

internal data class PrayerDay(val mosqueSchedule: PrayerSchedule, val windows: List<PrayerWindow>) {
    fun countdownAt(now: LocalDateTime): PrayerCountdown? {
        val window = windows.firstOrNull { now < it.end } ?: return null
        val active = now >= window.start
        val remaining = Duration.between(now, if (active) window.end else window.start)
        val seconds = remaining.seconds + if (remaining.nano == 0) 0 else 1
        return PrayerCountdown(window.prayer, active, seconds)
    }
}

internal suspend fun loadPrayerSchedule(): PrayerDay = parsePrayerDay(fetchPrayerSchedule())

internal suspend fun loadMosqueSchedule(): PrayerSchedule = parseDushanbeMosqueSchedule(fetchPrayerSchedule())

private suspend fun fetchPrayerSchedule(): String = withContext(Dispatchers.IO) {
    val connection = URL(scheduleUrl).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.useCaches = false
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

internal fun parsePrayerDay(html: String): PrayerDay {
    val mosqueSchedule = parseDushanbeMosqueSchedule(html)
    val timetable = dushanbeValues(html, "wb_element_namaz_time_for_today")
    return parseDatesAndTimes {
        val date = LocalDate.parse(timetable.requireField("date_imruz"), websiteDateFormat)
        val tomorrow = LocalDate.parse(timetable.requireField("date_pagoh"), websiteDateFormat)
        if (date != mosqueSchedule.date || tomorrow != date.plusDays(1)) {
            throw IOException("The prayer timetables have inconsistent dates")
        }

        fun window(prayer: Prayer, day: LocalDate, suffix: String): PrayerWindow {
            val start = LocalTime.parse(timetable.requireField("${prayer.websiteKey}_from_$suffix"))
            val end = LocalTime.parse(timetable.requireField("${prayer.websiteKey}_to_$suffix"))
            val endDate = if (prayer == Prayer.Isha && end < start) day.plusDays(1) else day
            val window = PrayerWindow(prayer, day.atTime(start), endDate.atTime(end))
            if (window.end <= window.start) throw IOException("Invalid window for $prayer")
            return window
        }

        val today = Prayer.entries.map { window(it, date, "imruz") }
        val tomorrowFajr = window(Prayer.Fajr, tomorrow, "pagoh")
        val windows = buildList {
            // When the site ends Isha one minute before Fajr, apply the same cutoff before today's dawn.
            if (today.last().end.plusMinutes(1) == tomorrowFajr.start) {
                val beforeDawn = today.first().start.minusMinutes(1)
                if (beforeDawn.toLocalDate() == date && beforeDawn > date.atStartOfDay()) {
                    add(PrayerWindow(Prayer.Isha, date.atStartOfDay(), beforeDawn))
                }
            }
            addAll(today)
            add(tomorrowFajr)
        }
        if (windows.zipWithNext().any { (first, second) -> first.end > second.start }) {
            throw IOException("Overlapping prayer windows")
        }
        PrayerDay(mosqueSchedule, windows)
    }
}

internal fun parseDushanbeMosqueSchedule(html: String): PrayerSchedule {
    val values = dushanbeValues(html, "wb_element_read_namaz_in_time")
    return parseDatesAndTimes {
        PrayerSchedule(
            date = LocalDate.parse(values.requireField("date_imruz"), websiteDateFormat),
            fajr = LocalTime.parse(values.requireField("bomdod_from_imruz")),
            dhuhr = LocalTime.parse(values.requireField("peshin_from_imruz")),
            asr = LocalTime.parse(values.requireField("asr_in_masjid_imruz")),
            maghrib = LocalTime.parse(values.requireField("shom_from_imruz")),
            isha = LocalTime.parse(values.requireField("khuftan_from_imruz")),
        )
    }
}

private fun dushanbeValues(html: String, element: String): Map<String, String> {
    val script = html.substringAfter("var $element", "")
        .substringBefore("</script>")
    val dushanbeCase = Regex(
        """\bswitch\s*\(\s*shift_for_namaz\s*\)\s*\{.*?\bcase\s+0\s*:(.*?)\bbreak\s*;""",
        RegexOption.DOT_MATCHES_ALL,
    ).find(script)?.groupValues?.get(1)
        ?: throw IOException("Prayer schedule section $element was not found")

    return Regex("""\bvar\s+(\w+)\s*=\s*["']([\d:-]+)["']""")
        .findAll(dushanbeCase)
        .associate { it.groupValues[1] to it.groupValues[2] }
}

private fun Map<String, String>.requireField(key: String): String =
    this[key] ?: throw IOException("Missing schedule field $key")

private inline fun <T> parseDatesAndTimes(parse: () -> T): T {
    return try {
        parse()
    } catch (error: DateTimeParseException) {
        throw IOException("Invalid prayer schedule", error)
    }
}
