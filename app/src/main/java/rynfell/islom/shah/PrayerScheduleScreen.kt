package rynfell.islom.shah

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val displayDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
private val displayTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PrayerScheduleScreen(
    state: PrayerScheduleState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val uriHandler = LocalUriHandler.current
    Box(Modifier.fillMaxSize()) {
        MosqueBackground()
        PullToRefreshBox(
            isRefreshing = refreshing && state != PrayerScheduleState.Loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.prayers_title),
                        style = type.headlineLarge,
                        color = colors.onBackground,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.prayers_mosque),
                        style = type.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }

                when (state) {
                    PrayerScheduleState.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.prayers_loading), color = colors.onBackground)
                    }
                    PrayerScheduleState.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.prayers_unavailable), color = colors.onBackground)
                        Button(onClick = onRefresh) {
                            Text(stringResource(R.string.prayers_retry))
                        }
                    }
                    is PrayerScheduleState.Ready -> {
                        if (state.fromCache) {
                            Text(
                                stringResource(R.string.prayers_cached),
                                style = type.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        val now = rememberDushanbeClock()
                        PrayerCountdownCard(state.day, now)
                        PrayerTimesCard(state.day.mosqueSchedule, now)
                        TextButton(
                            onClick = { uriHandler.openUri("https://taqvim.tj/") },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.prayers_source))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDushanbeClock(): State<LocalDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(LocalDateTime.now(dushanbeZone), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val time = LocalDateTime.now(dushanbeZone)
                value = time
                delay(1_000L - time.nano / 1_000_000L)
            }
        }
    }
}

@Composable
private fun PrayerCountdownCard(day: PrayerDay, now: State<LocalDateTime>) {
    val countdown = day.countdownAt(now.value) ?: return
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val title = if (countdown.untilEnd) R.string.prayers_until_end else R.string.prayers_until_start
    val seconds = countdown.seconds
    val digits = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.primary,
        contentColor = colors.onPrimary,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(stringResource(title, stringResource(countdown.prayer.nameResource)), style = type.titleMedium)
            Text(
                digits,
                style = type.headlineLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PrayerTimesCard(schedule: PrayerSchedule, now: State<LocalDateTime>) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val times = remember(schedule) {
        Prayer.entries.map { it to schedule.timeOf(it) }
    }
    val upcomingPrayer by remember(schedule, now) {
        derivedStateOf {
            val current = now.value
            times.firstOrNull { (_, time) -> current < schedule.date.atTime(time) }?.first
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surface,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.prayers_mosque_schedule),
                    style = type.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    schedule.date.format(displayDate),
                    style = type.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            times.forEach { (prayer, time) ->
                val upcoming = prayer == upcomingPrayer
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(
                            color = colors.primary.copy(alpha = if (upcoming) 0.14f else 0.05f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrayerTimeIcon(prayer, upcoming)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(prayer.nameResource),
                            style = type.titleMedium,
                            fontWeight = if (upcoming) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        if (upcoming) {
                            Text(
                                stringResource(R.string.prayers_next_at_mosque),
                                style = type.labelMedium,
                                color = colors.primary,
                            )
                        }
                    }
                    Text(
                        time.format(displayTime),
                        style = type.titleLarge.copy(fontFeatureSettings = "tnum"),
                        color = if (upcoming) colors.primary else colors.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrayerTimeIcon(prayer: Prayer, upcoming: Boolean) {
    val colors = MaterialTheme.colorScheme
    val ink = if (upcoming) colors.onPrimary else colors.primary
    Box(
        modifier = Modifier.size(34.dp).background(
            color = if (upcoming) colors.primary else colors.primary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(10.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val unit = size.width / 20f
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            when (prayer) {
                Prayer.Fajr, Prayer.Maghrib -> {
                    val horizon = if (prayer == Prayer.Fajr) 12f else 14f
                    drawArc(
                        color = ink,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(5f * unit, (horizon - 5f) * unit),
                        size = Size(10f * unit, 10f * unit),
                        style = stroke,
                    )
                    drawLine(
                        ink, Offset(2f * unit, horizon * unit), Offset(18f * unit, horizon * unit),
                        stroke.width, StrokeCap.Round,
                    )
                    drawLine(
                        ink, Offset(6f * unit, 17f * unit), Offset(14f * unit, 17f * unit),
                        stroke.width, StrokeCap.Round,
                    )
                }
                Prayer.Dhuhr, Prayer.Asr -> {
                    val sunCenter = if (prayer == Prayer.Dhuhr) center else Offset(11f * unit, 11f * unit)
                    drawCircle(ink, radius = 4f * unit, center = sunCenter, style = stroke)
                    for (direction in sunRays) {
                        drawLine(
                            ink, sunCenter + direction * (6f * unit), sunCenter + direction * (8f * unit),
                            stroke.width, StrokeCap.Round,
                        )
                    }
                }
                Prayer.Isha -> {
                    val moon = Path().apply {
                        moveTo(10f * unit, 3f * unit)
                        quadraticTo(4f * unit, 12f * unit, 17f * unit, 12f * unit)
                        quadraticTo(15f * unit, 18f * unit, 9f * unit, 17f * unit)
                        quadraticTo(3f * unit, 16f * unit, 3f * unit, 10f * unit)
                        quadraticTo(3f * unit, 4f * unit, 10f * unit, 3f * unit)
                        close()
                    }
                    drawPath(moon, ink, style = stroke)
                }
            }
        }
    }
}

private val sunRays = listOf(
    Offset(0f, -1f), Offset(0.707f, -0.707f), Offset(1f, 0f), Offset(0.707f, 0.707f),
    Offset(0f, 1f), Offset(-0.707f, 0.707f), Offset(-1f, 0f), Offset(-0.707f, -0.707f),
)
