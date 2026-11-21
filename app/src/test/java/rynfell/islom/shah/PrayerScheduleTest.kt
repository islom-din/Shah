package rynfell.islom.shah

import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PrayerScheduleTest {
    @Test
    fun readsTheCentralMosqueTimesFromTheCorrectScript() {
        val schedule = parseDushanbeMosqueSchedule(html)

        assertEquals(
            PrayerSchedule(
                date = LocalDate.of(2026, 11, 21),
                fajr = LocalTime.of(6, 26),
                dhuhr = LocalTime.of(12, 40),
                asr = LocalTime.of(15, 39),
                maghrib = LocalTime.of(17, 21),
                isha = LocalTime.of(18, 50),
            ),
            schedule,
        )
    }

    @Test(expected = IOException::class)
    fun rejectsAnIncompleteSchedule() {
        parseDushanbeMosqueSchedule(html.replace("khuftan_from_imruz", "other_field"))
    }

    @Test(expected = IOException::class)
    fun rejectsInvalidTimes() {
        parseDushanbeMosqueSchedule(html.replace("15:39", "29:39"))
    }

    @Test
    fun countsDownToTheEndOfTheActivePrayerWindow() {
        val day = parsePrayerDay(html)

        assertEquals(PrayerCountdown(Prayer.Dhuhr, true, 2582), day.countdownAt(at("14:40:58")))
        assertEquals(PrayerCountdown(Prayer.Asr, true, 5580), day.countdownAt(at("15:25:00")))
        assertEquals(LocalTime.of(15, 39), day.mosqueSchedule.asr)
    }

    @Test
    fun countsDownToTheNextStartBetweenPrayerWindows() {
        val day = parsePrayerDay(html)

        assertEquals(PrayerCountdown(Prayer.Asr, false, 60), day.countdownAt(at("15:24:00")))
        assertEquals(PrayerCountdown(Prayer.Maghrib, false, 1260), day.countdownAt(at("16:58:00")))
    }

    @Test
    fun handlesIshaAcrossMidnight() {
        val day = parsePrayerDay(html)

        assertEquals(PrayerCountdown(Prayer.Isha, true, 3660), day.countdownAt(at("04:40")))
        assertEquals(PrayerCountdown(Prayer.Isha, true, 20460), day.countdownAt(at("00:00").plusDays(1)))
        assertEquals(PrayerCountdown(Prayer.Fajr, false, 60), day.countdownAt(at("05:41").plusDays(1)))
    }

    @Test
    fun notificationUsesTheMosqueTimeAndTheChosenReminderLead() {
        val schedule = parseDushanbeMosqueSchedule(html)
        val settings = PrayerNotificationSettings(reminderEnabled = true, leadMinutes = 15, atPrayerEnabled = true)
        val asrAtMosque = at("15:39").atZone(dushanbeZone)

        assertEquals(
            asrAtMosque.minusMinutes(15).toInstant(),
            schedule.alertFor(Prayer.Asr, PrayerAlertKind.Reminder, settings)?.triggerTime,
        )
        assertEquals(
            asrAtMosque.toInstant(),
            schedule.alertFor(Prayer.Asr, PrayerAlertKind.Start, settings)?.triggerTime,
        )
        assertEquals(null, schedule.alertFor(Prayer.Asr, PrayerAlertKind.Reminder, settings.copy(reminderEnabled = false)))
        assertEquals(null, schedule.alertFor(Prayer.Asr, PrayerAlertKind.Start, settings.copy(atPrayerEnabled = false)))
    }

    @Test
    fun cacheRequiresARecentVisitAndRecentDataForTheSameDay() {
        val day = parsePrayerDay(html)
        val now = at("15:00").atZone(dushanbeZone).toInstant()
        val visit = PrayerVisit(now, now.minusSeconds(1800))
        val cached = CachedPrayerDay(day, now.minusSeconds(2100))

        assertEquals(now.plusSeconds(5100), cached.usableUntil(visit, now))
        assertEquals(null, cached.usableUntil(visit.copy(previousEntry = now.minusSeconds(7200)), now))
        assertEquals(null, cached.copy(savedAt = now.minusSeconds(7200)).usableUntil(visit, now))
        assertEquals(
            null,
            cached.copy(savedAt = now.plusSeconds(86400)).usableUntil(
                PrayerVisit(now.plusSeconds(86400), now.plusSeconds(86000)),
                now.plusSeconds(86400),
            ),
        )
    }

    @Test
    fun newlyDownloadedCacheCanCoverAFailedRefreshWithinTheSameVisit() {
        val day = parsePrayerDay(html)
        val now = at("15:00").atZone(dushanbeZone).toInstant()
        val visit = PrayerVisit(now.minusSeconds(60), previousEntry = null)

        assertEquals(now.plusSeconds(7170), CachedPrayerDay(day, now.minusSeconds(30)).usableUntil(visit, now))
        assertEquals(null, CachedPrayerDay(day, now.minusSeconds(120)).usableUntil(visit, now))
    }

    private fun at(time: String): LocalDateTime =
        LocalDate.of(2026, 11, 21).atTime(LocalTime.parse(time))

    private val html = """
        <script>
            var wb_element_namaz_time_for_today = "wb_element_instance16";
            switch (shift_for_namaz) {
                case 0:
                    var date_imruz = "21-11-2026";
                    var bomdod_from_imruz = "05:42";
                    var bomdod_to_imruz = "07:11";
                    var peshin_from_imruz = "12:40";
                    var peshin_to_imruz = "15:24";
                    var asr_from_imruz = "15:25";
                    var asr_to_imruz = "16:58";
                    var shom_from_imruz = "17:19";
                    var shom_to_imruz = "17:59";
                    var khuftan_from_imruz = "18:49";
                    var khuftan_to_imruz = "05:41";
                    var date_pagoh = "22-11-2026";
                    var bomdod_from_pagoh = "05:42";
                    var bomdod_to_pagoh = "07:12";
                    break;
            }
        </script>
        <script>
            var wb_element_read_namaz_in_time = "wb_element_instance27";
            switch (shift_for_namaz) {
                case 0:
                    var date_imruz = "21-11-2026";
                    var bomdod_from_imruz = "06:26";
                    var peshin_from_imruz = "12:40";
                    var asr_in_masjid_imruz = "15:39";
                    var shom_from_imruz = "17:21";
                    var khuftan_from_imruz = "18:50";
                    break;
                case -4:
                    var asr_in_masjid_imruz = "15:35";
                    break;
            }
        </script>
    """.trimIndent()
}
