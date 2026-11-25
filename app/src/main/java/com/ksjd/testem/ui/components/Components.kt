package com.ksjd.testem.ui.components

import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.outlined.Tram
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R
import com.ksjd.testem.ui.theme.PlateStyle
import com.ksjd.testem.ui.theme.TransitTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Space to leave under a tab's content so its end can scroll out from under the floating bottom bar. */
@Composable
fun barSpace(): Dp = 96.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars
    .asPaddingValues().calculateBottomPadding()

/** Line number on a small plate, like the ones on stop poles and bus fronts. */
@Composable
fun LinePlate(line: String, modifier: Modifier = Modifier, onBoard: Boolean = false) {
    val colors = TransitTheme.colors
    val background = if (onBoard) colors.boardText else colors.plate
    val content = if (onBoard) colors.board else colors.onPlate
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 28.dp)
            .background(background, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(line.ifBlank { "?" }, style = PlateStyle, color = content, maxLines = 1)
    }
}

/** Large screen title in the signage face; optional supporting line. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

/** A plain grouped list surface; rows inside are separated by hairlines. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(content = content)
    }
}

@Composable
fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val haptic = rememberHaptic()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { haptic(Haptics.TAP); onClick() } else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

enum class NoticeTone { Info, Warning, Error }

/** One-line banner that explains a state and, when possible, how to fix it. */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        NoticeTone.Info -> scheme.surfaceVariant to scheme.onSurface
        NoticeTone.Warning -> TransitTheme.colors.delay.copy(alpha = 0.14f) to scheme.onSurface
        NoticeTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .defaultMinSize(minHeight = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tone == NoticeTone.Warning) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(TransitTheme.colors.delay, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * "+3 min" (late), "−1 min" (early) or "On time". Null delay renders nothing.
 * Colour only flags exceptions: on time stays neutral, under 5 minutes takes the delay colour, 5 or more the late one.
 */
@Composable
fun DelayLabel(delaySeconds: Int?, modifier: Modifier = Modifier, onBoard: Boolean = false, neutral: Color? = null) {
    if (delaySeconds == null) return
    val colors = TransitTheme.colors
    val minutes = (delaySeconds / 60.0).roundToInt()
    // On the dark board the light-mode delay colours are lifted so they keep their contrast.
    fun board(c: Color) = if (onBoard && c.luminance() < 0.5f) androidx.compose.ui.graphics.lerp(c, Color.White, 0.45f) else c
    val neutral = neutral ?: if (onBoard) colors.boardDim else colors.onTime
    val (text, color) = when {
        minutes >= 5 -> stringResource(R.string.delay_late, minutes) to board(colors.late)
        minutes >= 1 -> stringResource(R.string.delay_late, minutes) to board(colors.delay)
        minutes <= -1 -> stringResource(R.string.delay_early, abs(minutes)) to neutral
        else -> stringResource(R.string.delay_on_time) to neutral
    }
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = color,
        fontWeight = if (minutes >= 5) FontWeight.Bold else FontWeight.SemiBold)
}

/** "now", "4 min" or "1 h 5 min" for a countdown in seconds. */
@Composable
fun countdownText(secondsUntil: Int): String {
    val minutes = (secondsUntil / 60.0).roundToInt()
    return when {
        minutes <= 0 -> stringResource(R.string.countdown_now)
        minutes < 60 -> stringResource(R.string.countdown_minutes, minutes)
        else -> stringResource(R.string.countdown_hours, minutes / 60, minutes % 60)
    }
}

@Composable
fun daysLeftText(days: Long): String = when {
    days < 0 -> stringResource(R.string.days_expired)
    days == 0L -> stringResource(R.string.days_today)
    else -> pluralStringResource(R.plurals.days_left, days.toInt(), days.toInt())
}

/**
 * A ticket stub: rounded rectangle with two half-circle notches cut into the
 * sides at [notchY], where the perforation runs.
 */
class TicketStubShape(private val cornerRadius: Dp, private val notchRadius: Dp, private val notchY: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerRadius.toPx() }
        val n = with(density) { notchRadius.toPx() }
        val y = with(density) { notchY.toPx() }.coerceIn(r + n, size.height - r - n)
        val body = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
        val notches = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(0f, y), n))
            addOval(androidx.compose.ui.geometry.Rect(Offset(size.width, y), n))
        }
        return Outline.Generic(Path().apply { op(body, notches, PathOperation.Difference) })
    }
}


// ------------------------------------------------------------ 2.18.0: trains, trams and buses told apart

/** Train lines are written with their kind in front: "Os 7308", "R 845", "IC 501", "REX 1810". */
private val RAIL_LINE = Regex("^(Os|R|REX|RR|Ex|IC|EC|EN|SC|RJ|LE|ZR|Zr|Sp|Sv|Mn|Rx|RE|ICE|NJ|D)\\s*\\d+.*")

private val CITY_LINE = Regex("""\d{1,3}[A-Z]?""")

/** "rail", "tram", "trolleybus", "city" or "bus", from what is known about a line (its kind, vehicle, or how it is written). */
fun lineKind(line: String, group: String = "", vehicle: String = ""): String = when {
    group == "rail" || vehicle == "rail" || RAIL_LINE.matches(line.trim()) -> "rail"
    vehicle == "tram" -> "tram"
    vehicle == "trolleybus" -> "trolleybus"
    group == "city" -> "city"
    // EM-0128: S.A.D. Zvolen's live board gives no kind; its city lines are 1-3 digits ("21"), others 6 ("606104").
    group.isEmpty() && CITY_LINE.matches(line.trim()) -> "city"
    else -> "bus"
}

fun kindIcon(kind: String): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    "rail" -> androidx.compose.material.icons.Icons.Filled.DirectionsRailway
    "tram" -> androidx.compose.material.icons.Icons.Outlined.Tram
    else -> androidx.compose.material.icons.Icons.Outlined.DirectionsBus
}

/** City lines (MHD: city buses, trams, trolleybuses) are purple; buses the app's own colour, trains black (light on dark). */
private val CityColor = Color(0xFF6B3A8E)

/** (plate, text on it) of a kind: buses in the app's main colour, trains black like the old plates, city lines purple. */
@Composable
private fun kindColors(kind: String, onBoard: Boolean = false): Pair<Color, Color> = when (kind) {
    "rail" -> if (onBoard) TransitTheme.colors.boardText to TransitTheme.colors.board else TransitTheme.colors.plate to TransitTheme.colors.onPlate
    "tram", "trolleybus", "city" -> CityColor to Color.White
    else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
}

/** The pictogram of a line's kind, in the kind's colour. */
@Composable
fun KindIcon(kind: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 26.dp) {
    val tint = when (kind) {
        "rail" -> MaterialTheme.colorScheme.onSurface
        "tram", "trolleybus", "city" -> CityColor
        else -> MaterialTheme.colorScheme.primary
    }
    androidx.compose.material3.Icon(kindIcon(kind), null, modifier.size(size), tint = tint)
}

/** A line's plate in its kind's colour: a train keeps its kind in the text ("R 845"), others show the number. */
@Composable
fun KindPlate(line: String, kind: String, modifier: Modifier = Modifier, onBoard: Boolean = false) {
    val (background, content) = kindColors(kind, onBoard)
    val text = if (kind == "rail") line.trim() else line.trim().substringAfterLast(' ')
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 28.dp)
            .background(background, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text.ifBlank { "–" }, style = PlateStyle, color = content, maxLines = 1)
    }
}
