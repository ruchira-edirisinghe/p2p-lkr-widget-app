package dev.dfanso.lkrp2p.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.Trend
import dev.dfanso.lkrp2p.render.Palette

/** The widget's palette, extended with surfaces for the app. */
object Ui {
    val Bg = Color(0xFF0E0E0E)
    val Surface = Color(0xFF161616)
    val Raised = Color(0xFF1F1F1F)
    val Line = Color(0xFF2A2A2A)
    val Text = Color.White
    val Dim = Color(Palette.TEXT_DIM)
    val Faint = Color(0x61FFFFFF)
    val Up = Color(Palette.UP)
    val Down = Color(Palette.DOWN)
    val Amber = Color(Palette.STALE)
    val Coin = Color(0xFF1F9D78)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ui.Up,
            onPrimary = Color(0xFF00210F),
            primaryContainer = Color(0xFF173A2A),
            onPrimaryContainer = Color(0xFFCFF5E2),
            secondaryContainer = Color(0xFF1E3A2C),
            onSecondaryContainer = Color(0xFFCFF5E2),
            background = Ui.Bg,
            surface = Ui.Bg,
            surfaceContainer = Ui.Surface,
            surfaceContainerLow = Ui.Surface,
            surfaceContainerHigh = Ui.Raised,
            outline = Ui.Line,
            error = Ui.Down,
        ),
        content = content,
    )
}

/** A card with the app's one surface style. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Ui.Surface, RoundedCornerShape(20.dp))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Ui.Dim,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        modifier = modifier,
    )
}

/**
 * The reference's Week | Month | Year control, reused for every either/or
 * choice in the app so they all read the same way.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    sublabel: ((T) -> String?)? = null,
) {
    val track = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF111111), track)
            .border(1.dp, Ui.Line, track)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isSelected) Color(0xFF262626) else Color.Transparent)
                    .clickable { onSelect(option) }
                    .semantics { role = Role.Tab; this.selected = isSelected },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label(option),
                    color = if (isSelected) Ui.Text else Ui.Dim,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                sublabel?.invoke(option)?.let {
                    Text(
                        it,
                        color = if (isSelected) Ui.Up else Ui.Faint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun TrendChip(trend: Trend?, modifier: Modifier = Modifier) {
    if (trend == null) return
    val (color, arrow) = when (trend.direction) {
        Trend.Direction.UP -> Ui.Up to "▲"
        Trend.Direction.DOWN -> Ui.Down to "▼"
        Trend.Direction.FLAT -> Ui.Dim to "■"
    }
    Text(
        "$arrow ${Formatting.percent(trend.percent).trimStart('+', '-')}",
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
fun CurrencyBadge(code: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (code == "USDT") {
            Box(Modifier.size(24.dp).background(Ui.Coin, CircleShape), contentAlignment = Alignment.Center) {
                Text("₮", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text("🇱🇰", fontSize = 19.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(code, color = Ui.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/** A label and value stacked, for stat rows. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Ui.Text) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Ui.Dim, fontSize = 12.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A tappable settings row: title and help on the left, current value on the right. */
@Composable
fun SettingRow(title: String, value: String, help: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ui.Text, fontSize = 16.sp)
            help?.let { Text(it, color = Ui.Dim, fontSize = 13.sp) }
        }
        Spacer(Modifier.width(12.dp))
        Text(value, color = Ui.Up, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
