package rynfell.islom.shah

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val reminderOptions = listOf(5, 10, 15, 20, 30, 45, 60)

internal data class PrayerNotificationSettings(
    val reminderEnabled: Boolean = false,
    val leadMinutes: Int = 15,
    val atPrayerEnabled: Boolean = false,
) {
    val enabled get() = reminderEnabled || atPrayerEnabled
}

internal enum class PrayerAlertKind { Reminder, Start }

internal data class PrayerAlert(
    val prayer: Prayer,
    val kind: PrayerAlertKind,
    val prayerTime: ZonedDateTime,
    val triggerTime: Instant,
)

internal fun PrayerSchedule.alertFor(
    prayer: Prayer,
    kind: PrayerAlertKind,
    settings: PrayerNotificationSettings,
): PrayerAlert? {
    val start = date.atTime(timeOf(prayer)).atZone(dushanbeZone)
    return when (kind) {
        PrayerAlertKind.Reminder -> if (settings.reminderEnabled) {
            PrayerAlert(prayer, kind, start, start.minusMinutes(settings.leadMinutes.toLong()).toInstant())
        } else null
        PrayerAlertKind.Start -> if (settings.atPrayerEnabled) {
            PrayerAlert(prayer, kind, start, start.toInstant())
        } else null
    }
}

internal class PrayerNotifications(private val context: Context) {
    private val preferences = context.getSharedPreferences("prayer_notifications", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    val notificationsAllowed get() = notifications.areNotificationsEnabled()
    val exactAlarmsAllowed get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun settings() = PrayerNotificationSettings(
        reminderEnabled = preferences.getBoolean("reminder", false),
        leadMinutes = preferences.getInt("lead_minutes", 15).takeIf { it in reminderOptions } ?: 15,
        atPrayerEnabled = preferences.getBoolean("at_prayer", false),
    )

    fun updateSettings(settings: PrayerNotificationSettings) {
        preferences.edit {
            putBoolean("reminder", settings.reminderEnabled)
            putInt("lead_minutes", settings.leadMinutes)
            putBoolean("at_prayer", settings.atPrayerEnabled)
        }
        sync()
    }

    fun updateSchedule(schedule: PrayerSchedule) {
        if (schedule.date != ZonedDateTime.now(dushanbeZone).toLocalDate()) return
        preferences.edit {
            val times = Prayer.entries.map { schedule.timeOf(it).toString() }
            putString("schedule", (listOf(schedule.date.toString()) + times).joinToString(","))
        }
        sync()
    }

    fun cachedSchedule(today: LocalDate): PrayerSchedule? {
        val fields = preferences.getString("schedule", null)?.split(',') ?: return null
        if (fields.size != 6 || fields[0] != today.toString()) return null
        return runCatching {
            val (fajr, dhuhr, asr, maghrib, isha) = fields.drop(1).map(LocalTime::parse)
            PrayerSchedule(today, fajr, dhuhr, asr, maghrib, isha)
        }.getOrNull()
    }

    fun sync() {
        val settings = settings()
        val enabled = settings.enabled && notificationsAllowed
        val now = ZonedDateTime.now(dushanbeZone)
        val schedule = if (enabled) cachedSchedule(now.toLocalDate()) else null
        val exact = enabled && exactAlarmsAllowed
        for (prayer in Prayer.entries) {
            for (kind in PrayerAlertKind.entries) {
                val alert = schedule?.alertFor(prayer, kind, settings)
                val key = alertKey(prayer, kind)
                if (alert == null) {
                    cancel(key)
                } else if (alert.triggerTime > now.toInstant()) {
                    val atMillis = alert.triggerTime.toEpochMilli()
                    val operation = operation(key, atMillis)
                    scheduleAlert(atMillis, operation, exact)
                }
            }
        }
        if (enabled) {
            createChannels()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(dushanbeZone).toInstant()
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextMidnight.toEpochMilli(),
                operation(refreshKey),
            )
            if (schedule == null && jobs.getPendingJob(refreshJobId) == null) {
                jobs.schedule(
                    JobInfo.Builder(refreshJobId, ComponentName(context, PrayerScheduleRefreshJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build(),
                )
            }
        } else {
            cancel(refreshKey)
            jobs.cancel(refreshJobId)
        }
    }

    fun onAlert(key: Int, triggerMillis: Long) {
        if (!notificationsAllowed || key !in 0 until alertCount) return
        val prayer = Prayer.entries[key / PrayerAlertKind.entries.size]
        val kind = PrayerAlertKind.entries[key % PrayerAlertKind.entries.size]
        val now = ZonedDateTime.now(dushanbeZone)
        val alert = cachedSchedule(now.toLocalDate())?.alertFor(prayer, kind, settings()) ?: return
        if (alert.triggerTime.toEpochMilli() != triggerMillis || now.toInstant() < alert.triggerTime) return
        if (kind == PrayerAlertKind.Reminder && now >= alert.prayerTime) return
        if (kind == PrayerAlertKind.Start && now >= alert.prayerTime.plusMinutes(10)) return
        postAlert(alert, key)
    }

    private fun postAlert(alert: PrayerAlert, key: Int) {
        createChannels()
        val channel = if (alert.kind == PrayerAlertKind.Start) prayerChannel else reminderChannel
        val title = when (alert.kind) {
            PrayerAlertKind.Reminder -> R.string.prayer_notification_reminder
            PrayerAlertKind.Start -> R.string.prayer_notification_start
        }
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_prayer_notification)
            .setContentTitle(context.getString(title, context.getString(alert.prayer.nameResource)))
            .setContentText(context.getString(R.string.prayer_notification_time, alert.prayerTime.format(alertTimeFormat)))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        notifications.notify(key, notification)
    }

    fun createChannels() {
        val reminder = NotificationChannel(
            reminderChannel,
            context.getString(R.string.prayer_channel_reminder),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val prayer = NotificationChannel(
            prayerChannel,
            context.getString(R.string.prayer_channel_start),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            val sound = Uri.parse("android.resource://${context.packageName}/raw/prayer_chime")
            val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            setSound(sound, audio)
        }
        notifications.createNotificationChannels(listOf(reminder, prayer))
    }

    private fun alertKey(prayer: Prayer, kind: PrayerAlertKind) =
        prayer.ordinal * PrayerAlertKind.entries.size + kind.ordinal

    private fun alarmIntent(key: Int) = Intent(context, PrayerAlarmReceiver::class.java).apply {
        data = Uri.parse("shah://prayer-alert/$key")
        putExtra("key", key)
    }

    private fun operation(key: Int, triggerMillis: Long = 0L) = PendingIntent.getBroadcast(
        context, key,
        alarmIntent(key).putExtra("trigger", triggerMillis),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(key: Int) {
        PendingIntent.getBroadcast(
            context, key, alarmIntent(key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )?.let(alarms::cancel)
    }

    @SuppressLint("MissingPermission", "ScheduleExactAlarm")
    private fun scheduleAlert(atMillis: Long, operation: PendingIntent, exact: Boolean) {
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                return
            } catch (_: SecurityException) {
                // The user may revoke exact alarms while the schedule is being updated.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    companion object {
        private const val reminderChannel = "prayer_reminders"
        private const val prayerChannel = "prayer_starts"
        private const val refreshJobId = 71
        internal const val refreshKey = 100
        private val alertCount = Prayer.entries.size * PrayerAlertKind.entries.size
        private val alertTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}

class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifications = PrayerNotifications(context)
        val key = intent.getIntExtra("key", -1)
        if (intent.action != null || key == PrayerNotifications.refreshKey) {
            notifications.sync()
        } else {
            notifications.onAlert(key, intent.getLongExtra("trigger", 0L))
        }
    }
}

class PrayerScheduleRefreshJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val notifications = PrayerNotifications(this)
        if (!notifications.settings().enabled || !notifications.notificationsAllowed) return false
        work = CoroutineScope(Dispatchers.Main).launch {
            val today = ZonedDateTime.now(dushanbeZone).toLocalDate()
            try {
                val schedule = notifications.cachedSchedule(today) ?: loadMosqueSchedule()
                if (schedule.date != ZonedDateTime.now(dushanbeZone).toLocalDate()) {
                    throw IOException("The site returned an outdated schedule")
                }
                notifications.updateSchedule(schedule)
                jobFinished(params, false)
            } catch (_: IOException) {
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return PrayerNotifications(this).settings().enabled
    }
}
