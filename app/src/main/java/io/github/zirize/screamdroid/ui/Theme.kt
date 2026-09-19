package io.github.zirize.screamdroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The few colours Material's scheme has no slot for.
 *
 * 🔑 **Three greys, not one.** The screens lean on the difference between a label, a value and a
 *    heading far more than on hue: [Dim] names things, [Muted] carries values, `onBackground`
 *    is for what is being said. Collapsing them would flatten every card on every screen.
 */
object Tone {
    /**
     * 🔑 One accent colour, everything else fixed - the rule the screens are drawn to. The
     *    launcher icon uses a darker shade of the same accent.
     * ℹ️ A light theme is not drawn yet; until then the scheme is dark either way.
     */
    val Accent = Color(0xFF4FC3A1)

    /** Page behind the cards. */
    val Background = Color(0xFF101316)

    /** A card. */
    val Card = Color(0xFF191D22)

    /** A card that is a suggestion rather than a statement - the dashed one on the main screen. */
    val SoftCard = Color(0xFF15181C)

    /** Card edges, dividers, and the empty part of a bar. */
    val Border = Color(0xFF2C333A)

    /** A control sitting on a card: a button, an unselected segment. */
    val Control = Color(0xFF21262C)

    /** Values, icons, secondary lines. */
    val Muted = Color(0xFFA3ACB5)

    /** Labels and units - one step quieter than [Muted]. */
    val Dim = Color(0xFF8F979F)

    /** Something is off, and it is the person's business rather than a fault. */
    val Warn = Color(0xFFD9A441)

    /** A fault. */
    val Error = Color(0xFFE2725B)

    val Text = Color(0xFFEAEDF0)
}

private val DarkScheme = darkColorScheme(
    primary = Tone.Accent,
    onPrimary = Color(0xFF00201A),
    background = Tone.Background,
    onBackground = Tone.Text,
    surface = Tone.Card,
    onSurface = Tone.Text,
    surfaceVariant = Tone.Control,
    onSurfaceVariant = Tone.Muted,
    outline = Tone.Border,
    outlineVariant = Tone.Border,
    error = Tone.Error,
)

/**
 * 🔑 **The device's own font, deliberately.** The mockup was drawn in IBM Plex, which is a web
 *    font; shipping it would add a megabyte and, worse, would ignore the system font-size setting
 *    that some people rely on to read anything at all.
 * 🔑 Numbers that are read as numbers - addresses, latency, counters - are monospaced at the call
 *    site, because a figure that shifts sideways as it changes is hard to watch.
 */
private val AppTypography = Typography()

@Composable
fun ScreamdroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}

/** For anything showing a measurement rather than a word. */
val Mono: FontFamily = FontFamily.Monospace
