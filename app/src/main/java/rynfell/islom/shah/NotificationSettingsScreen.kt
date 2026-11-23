package rynfell.islom.shah

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun NotificationSettingsScreen(
    settings: PrayerNotificationSettings,
    notificationsAllowed: Boolean,
    exactAlarmsAllowed: Boolean,
    onChange: (PrayerNotificationSettings) -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onPlaySound: () -> Unit,
    onTestAlarm: () -> Unit,
    updateState: UpdateState,
    currentVersion: String,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.settings_tab),
                    style = type.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground,
                )
                Text(
                    stringResource(R.string.settings_prayer_notifications),
                    style = type.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface,
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(bottom = if (settings.reminderEnabled) 16.dp else 0.dp)) {
                    NotificationSwitch(
                        title = stringResource(R.string.settings_prayer_reminder),
                        checked = settings.reminderEnabled,
                        onChecked = { onChange(settings.copy(reminderEnabled = it)) },
                    )
                    if (settings.reminderEnabled) {
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            reminderOptions.forEach { minutes ->
                                FilterChip(
                                    selected = settings.leadMinutes == minutes,
                                    onClick = { onChange(settings.copy(leadMinutes = minutes)) },
                                    label = { Text(stringResource(R.string.settings_lead_minutes, minutes)) },
                                )
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface,
                shape = RoundedCornerShape(24.dp),
            ) {
                Column {
                    NotificationSwitch(
                        title = stringResource(R.string.settings_at_prayer),
                        checked = settings.atPrayerEnabled,
                        onChecked = { onChange(settings.copy(atPrayerEnabled = it)) },
                    )
                    TextButton(
                        onClick = onPlaySound,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                    ) {
                        Text(stringResource(R.string.settings_preview_sound))
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface,
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(bottom = 16.dp)) {
                    NotificationSwitch(
                        title = stringResource(R.string.settings_fajr_alarm),
                        checked = settings.fajrAlarmEnabled,
                        onChecked = { onChange(settings.copy(fajrAlarmEnabled = it)) },
                    )
                    Text(
                        stringResource(R.string.settings_fajr_alarm_hint),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = type.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onTestAlarm,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_test_alarm))
                    }
                    if (settings.fajrAlarmEnabled) {
                        FlowRow(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            fajrAlarmOptions.forEach { minutes ->
                                FilterChip(
                                    selected = settings.fajrAlarmLeadMinutes == minutes,
                                    onClick = { onChange(settings.copy(fajrAlarmLeadMinutes = minutes)) },
                                    label = {
                                        Text(
                                            if (minutes == 0) stringResource(R.string.settings_alarm_at_time)
                                            else stringResource(R.string.settings_lead_minutes, minutes),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            UpdateCard(updateState, currentVersion, onCheckUpdate, onInstallUpdate)
            if (settings.notificationsEnabled && !notificationsAllowed) {
                OutlinedButton(onClick = onAllowNotifications) {
                    Text(stringResource(R.string.settings_allow_notifications))
                }
            } else if (settings.notificationsEnabled && !exactAlarmsAllowed) {
                Text(
                    stringResource(R.string.settings_exact_alarm_explanation),
                    style = type.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                OutlinedButton(onClick = onAllowExactAlarms) {
                    Text(stringResource(R.string.settings_allow_exact_alarms))
                }
            }
        }
    }
}

@Composable
private fun NotificationSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), color = colors.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
            val status = when (state) {
                is UpdateState.Available -> stringResource(R.string.update_available, state.update.version)
                is UpdateState.Downloading -> stringResource(R.string.update_downloading, (state.progress * 100).toInt())
                UpdateState.Checking -> stringResource(R.string.update_checking)
                UpdateState.Installing -> stringResource(R.string.update_installing)
                UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, currentVersion)
                UpdateState.Failed -> stringResource(R.string.update_failed)
                UpdateState.Idle -> stringResource(R.string.update_version, currentVersion)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (state is UpdateState.Downloading) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            when (state) {
                is UpdateState.Available -> Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
                is UpdateState.Downloading, UpdateState.Checking, UpdateState.Installing -> Unit
                else -> OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.update_check)) }
            }
        }
    }
}
