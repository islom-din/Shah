package rynfell.islom.shah

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rynfell.islom.shah.ui.theme.ShahTheme

/** Full-screen screen shown over the lock screen while the Fajr alarm is ringing. */
class AlarmActivity : ComponentActivity() {
    private val stopped = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val filter = IntentFilter(AlarmService.actionStopped)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopped, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopped, filter)
        }
        setContent {
            ShahTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.alarm_title),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(stringResource(R.string.alarm_text), style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = { AlarmService.send(this@AlarmActivity, AlarmService.actionStop); finish() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        ) { Text(stringResource(R.string.alarm_stop)) }
                        OutlinedButton(
                            onClick = { AlarmService.send(this@AlarmActivity, AlarmService.actionSnooze); finish() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) { Text(stringResource(R.string.alarm_snooze, PrayerNotifications.snoozeMinutes)) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(stopped)
        super.onDestroy()
    }
}
