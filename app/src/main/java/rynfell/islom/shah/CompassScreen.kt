package rynfell.islom.shah

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import rynfell.islom.shah.ui.theme.ShahTheme

@Composable
internal fun CompassScreen(
    locationStatus: LocationStatus,
    headingStatus: HeadingStatus,
    bearing: Float?,
    trueHeading: Float?,
    aligned: Boolean,
    placeName: String?,
    onLocationAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val darkTheme = isSystemInDarkTheme()
    val message = when (locationStatus) {
        LocationStatus.PermissionRequired -> R.string.location_permission_message
        LocationStatus.Locating -> R.string.locating
        LocationStatus.Disabled -> R.string.location_disabled
        LocationStatus.Failed -> R.string.location_failed
        LocationStatus.Ready -> when (headingStatus) {
            HeadingStatus.Waiting -> R.string.waiting_for_heading
            HeadingStatus.HoldFlat -> R.string.hold_flat
            HeadingStatus.Calibrate -> R.string.calibrate_compass
            HeadingStatus.Unavailable -> R.string.sensors_unavailable
            HeadingStatus.Available -> if (aligned) R.string.aligned else R.string.turn_toward_qibla
        }
    }
    val action = when (locationStatus) {
        LocationStatus.PermissionRequired -> R.string.allow_location
        LocationStatus.Disabled -> R.string.open_location_settings
        LocationStatus.Failed -> R.string.retry_location
        else -> null
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
    ) {
        MosqueBackground()

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val dialSize = minOf(maxWidth, (maxHeight * 0.55f).coerceIn(160.dp, 360.dp))
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.qibla_title),
                        color = colors.onBackground,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (bearing == null) stringResource(R.string.mecca)
                        else stringResource(R.string.mecca_with_bearing, bearing.roundToInt() % 360),
                        color = colors.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Spacer(Modifier.height(24.dp))
                    CompassDial(
                        heading = trueHeading,
                        target = bearing,
                        aligned = aligned,
                        darkTheme = darkTheme,
                        modifier = Modifier.size(dialSize),
                    )
                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = stringResource(message),
                        color = if (aligned) colors.primary else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.heightIn(min = 48.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (aligned) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (action != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onLocationAction) {
                            Text(stringResource(action))
                        }
                    }
                }
            }
            if (locationStatus == LocationStatus.Ready) {
                Text(
                    text = placeName ?: stringResource(R.string.resolving_place),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun MosqueBackground() {
    val darkTheme = isSystemInDarkTheme()
    val tint = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val artworkWidth = minOf(maxWidth, maxHeight * 0.56f)
        Image(
            painter = painterResource(R.drawable.mosque_background),
            contentDescription = null,
            modifier = Modifier.statusBarsPadding()
                .padding(top = 8.dp)
                .width(artworkWidth)
                .aspectRatio(380f / 593f),
            colorFilter = ColorFilter.tint(tint),
            alpha = if (darkTheme) 0.24f else 0.19f,
        )
    }
}

@Composable
private fun CompassDial(
    heading: Float?,
    target: Float?,
    aligned: Boolean,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ring = colors.primary.copy(alpha = 0.25f)
    val arrow = if (aligned) colors.primary else colors.tertiary
    val dialDescription = stringResource(R.string.compass_dial)

    Box(modifier.semantics { contentDescription = dialDescription }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(colors.surface.copy(alpha = 0.87f), radius = size.minDimension * 0.49f)
            drawCircle(ring, radius = size.minDimension * 0.49f, style = Stroke(1.dp.toPx()))
            val index = Path().apply {
                moveTo(center.x - 4.dp.toPx(), 1.dp.toPx())
                lineTo(center.x + 4.dp.toPx(), 1.dp.toPx())
                lineTo(center.x, 7.dp.toPx())
                close()
            }
            drawPath(index, colors.primary)
        }
        Image(
            painter = painterResource(R.drawable.compass_dial),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.94f).graphicsLayer {
                rotationZ = -(heading ?: 0f)
                alpha = if (heading == null) 0.38f else 0.92f
            },
            colorFilter = if (darkTheme) null else ColorFilter.tint(colors.onSurface),
        )

        if (heading != null && target != null) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = signedDegrees(target - heading) }) {
                val radius = size.minDimension
                val tip = Offset(center.x, center.y - radius * 0.205f)
                val left = Offset(center.x - radius * 0.065f, center.y + radius * 0.086f)
                val right = Offset(center.x + radius * 0.065f, left.y)
                val notch = Offset(center.x, center.y + radius * 0.041f)
                val needle = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(notch.x, notch.y)
                    lineTo(right.x, right.y)
                    close()
                }
                drawPath(needle, arrow)
                val shade = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(notch.x, notch.y)
                    lineTo(right.x, right.y)
                    close()
                }
                drawPath(shade, colors.surface.copy(alpha = 0.18f))
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CompassPreview() {
    ShahTheme {
        CompassScreen(
            locationStatus = LocationStatus.Ready,
            headingStatus = HeadingStatus.Available,
            bearing = 246f,
            trueHeading = 220f,
            aligned = false,
            placeName = "Таджикистан · Душанбе",
            onLocationAction = {},
        )
    }
}
