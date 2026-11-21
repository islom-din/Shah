package rynfell.islom.shah

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal enum class ShahTab(val label: Int) {
    Prayers(R.string.prayers_tab),
    Compass(R.string.qibla_title),
    Settings(R.string.settings_tab),
}

@Composable
internal fun ShahNavigation(
    selectedTab: ShahTab,
    onSelectTab: (ShahTab) -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.primary,
        selectedTextColor = colors.primary,
        indicatorColor = colors.primary.copy(alpha = 0.14f),
        unselectedIconColor = colors.onSurfaceVariant,
        unselectedTextColor = colors.onSurfaceVariant,
    )
    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                ShahTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        icon = { NavigationIcon(tab) },
                        label = { Text(stringResource(tab.label)) },
                        colors = itemColors,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            content()
        }
    }
}

@Composable
private fun NavigationIcon(tab: ShahTab) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.7.dp.toPx()
        when (tab) {
            ShahTab.Compass -> {
                drawCircle(color, radius = 9.dp.toPx(), style = Stroke(stroke))
                val needle = Path().apply {
                    moveTo(size.width * 0.72f, size.height * 0.28f)
                    lineTo(size.width * 0.57f, size.height * 0.57f)
                    lineTo(size.width * 0.28f, size.height * 0.72f)
                    lineTo(size.width * 0.43f, size.height * 0.43f)
                    close()
                }
                drawPath(needle, color)
            }
            ShahTab.Prayers -> {
                drawCircle(color, radius = 9.dp.toPx(), style = Stroke(stroke))
                drawLine(color, center, center - Offset(0f, 4.dp.toPx()), stroke, StrokeCap.Round)
                drawLine(color, center, center + Offset(3.dp.toPx(), 2.dp.toPx()), stroke, StrokeCap.Round)
            }
            ShahTab.Settings -> {
                drawCircle(color, radius = 7.dp.toPx(), style = Stroke(stroke))
                drawCircle(color, radius = 2.5.dp.toPx(), style = Stroke(stroke))
                repeat(8) { index ->
                    rotate(index * 45f) {
                        drawLine(
                            color, center - Offset(0f, 7.dp.toPx()), center - Offset(0f, 10.dp.toPx()),
                            stroke, StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}
