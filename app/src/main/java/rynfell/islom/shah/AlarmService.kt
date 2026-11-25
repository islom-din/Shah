package rynfell.islom.shah

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Foreground service that rings the Fajr alarm until the user stops or snoozes it. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopAlarm() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            actionStop -> stopAlarm()
            actionSnooze -> {
                PrayerNotifications(this).snoozeFajrAlarm()
                stopAlarm()
            }
            else -> startAlarm()
        }
        return START_NOT_STICKY
    }

    private fun startAlarm() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.alarm_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )
        startForeground(
            notificationId, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (player == null) {
            playSound()
            vibrate()
            handler.postDelayed(timeout, ringDurationMillis)
        }
    }

    private fun buildNotification(): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 0, Intent(this, AlarmActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_prayer_notification)
            .setContentTitle(getString(R.string.alarm_title))
            .setContentText(getString(R.string.alarm_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(Notification.Action.Builder(null, getString(R.string.alarm_snooze, PrayerNotifications.snoozeMinutes), action(actionSnooze)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.alarm_stop), action(actionStop)).build())
            .build()
    }

    private fun action(name: String) = PendingIntent.getService(
        this, name.hashCode(), Intent(this, AlarmService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun playSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                if (uri != null) setDataSource(this@AlarmService, uri)
                else setDataSource(this@AlarmService, android.net.Uri.parse("android.resource://$packageName/raw/prayer_chime"))
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun vibrate() {
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
    }

    private fun stopAlarm() {
        handler.removeCallbacks(timeout)
        player?.release()
        player = null
        vibrator?.cancel()
        sendBroadcast(Intent(actionStopped).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        player?.release()
        player = null
        vibrator?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val channelId = "fajr_alarm"
        private const val notificationId = 300
        private const val ringDurationMillis = 3 * 60_000L
        internal const val actionStop = "rynfell.islom.shah.ALARM_STOP"
        internal const val actionSnooze = "rynfell.islom.shah.ALARM_SNOOZE"
        internal const val actionStopped = "rynfell.islom.shah.ALARM_STOPPED"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AlarmService::class.java))
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
