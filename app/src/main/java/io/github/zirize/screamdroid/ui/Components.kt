package io.github.zirize.screamdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zirize.screamdroid.R

/**
 * The handful of shapes every screen is built from.
 *
 * 🔑 **Written out rather than taken from Material's own components.** The drawn screens use one
 *    card shape, one row height and one segmented control everywhere, and reaching for Card,
 *    ListItem and SegmentedButton would bring three different corner radii and three different
 *    idea of padding with them. This is less code than overriding all of that would be.
 */

/** Gap between cards, and the page's own side margin. Both come from the mockup. */
val PageMargin = 20.dp
val CardGap = 12.dp

@Composable
fun Card(
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    padding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(if (dashed) Tone.SoftCard else Tone.Card)
    // 🔑 A dashed edge says "this is for you to take away", which is exactly what the address
    //    card is; a solid one states what is true. The difference is worth the eight lines.
    val bordered = if (dashed) {
        base.drawBehind {
            drawRoundRect(
                color = Tone.Border,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
            )
        }
    } else {
        base.border(BorderStroke(1.dp, Tone.Border), shape)
    }
    Column(bordered.padding(padding), content = content)
}

/** The small grey heading above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Tone.Dim,
    )
}

/** A note under a card, explaining the thing above it. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = Tone.Dim) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 18.sp,
        color = color,
    )
}

/**
 * One line of a settings card: a name on the left, a control on the right.
 *
 * 🔑 [enabled] `false` greys it *and explains itself* through [supporting] rather than vanishing.
 *    "Start on boot" is the case this exists for - remove it and people go on looking for it.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    control: @Composable RowScope.() -> Unit,
) {
    val row = modifier
        .fillMaxWidth()
        .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = TouchTarget)
        .padding(vertical = 10.dp)
    Row(row, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Tone.Text else Tone.Dim,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    color = Tone.Dim,
                )
            }
        }
        control()
    }
}

/** The divider between rows inside one card. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.Border))
}

/**
 * The pill of two or three choices used for addressing, call behaviour and the buffer presets.
 *
 * 🔑 **Everything is visible at once, which is the whole point.** A dropdown would hide the two
 *    options that are not in force, and these are choices people compare rather than search.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Tone.Control)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (option in options) {
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Tone.Accent else Color.Transparent)
                    .clickable { onSelect(option) }
                    .heightIn(min = 38.dp)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else Tone.Muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Tone.Background,
            checkedTrackColor = Tone.Accent,
            checkedBorderColor = Tone.Accent,
            uncheckedThumbColor = Tone.Background,
            uncheckedTrackColor = Tone.Border,
            uncheckedBorderColor = Tone.Border,
        ),
    )
}

/** A square icon button - the copy and QR buttons, and the ones in the top bar. */
@Composable
fun IconAction(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    boxed: Boolean = false,
    tint: Color = Tone.Muted,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(TouchTarget)
            .clip(shape)
            .then(
                if (boxed) Modifier.background(Tone.Control).border(BorderStroke(1.dp, Tone.Border), shape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
    }
}

/** Title plus a back arrow, for the two screens that are reached from the main one. */
@Composable
fun SubScreenBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = PageMargin, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(R.drawable.ic_back, stringResource(R.string.action_back), onBack, tint = Tone.Text)
        Text(
            text = title,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** A label/value line inside a card, with the value monospaced. */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.Dim,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = Mono,
            color = Tone.Muted,
            textAlign = TextAlign.End,
        )
    }
}

/** A horizontal fill bar - the buffer indicator on the main screen. */
@Composable
fun FillBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Tone.Border),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(Tone.Accent),
        )
    }
}

/** 44 dp, the smallest thing a finger reliably hits - every tappable thing here is at least this. */
val TouchTarget = 44.dp

/** A number with its unit, used for the four tiles at the top of the diagnostics screen. */
@Composable
fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Card(modifier, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Tone.Dim)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                color = if (accent) Tone.Accent else Tone.Text,
            )
            Text(
                text = unit,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = Mono,
                color = Tone.Dim,
            )
        }
    }
}

/** A fixed-width spacer used where the mockup aligns labels in a column. */
@Composable
fun LabelCell(text: String) {
    Text(
        text = text,
        modifier = Modifier.width(62.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Tone.Dim,
    )
}
