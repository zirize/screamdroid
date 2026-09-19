package io.github.zirize.screamdroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The main screen's level meter: the last two seconds of the stream, both channels at once.
 *
 * 🔑 **Every bar is a measurement.** The drawn mockup animates its bars at random, which is fine
 *    for a picture and useless on a phone: the one question this screen answers is "is sound
 *    actually coming out", and a decoration that moves whether or not audio is arriving answers
 *    it wrongly. Each bar here is a peak that was really written to the device (see Peaks).
 *
 * 🔑 **Newest in the middle, flowing outwards, L on the left and R on the right.** That is what
 *    keeps the drawn shape - a solid block of bars labelled L and R at its ends - while giving
 *    the strip a time axis: a dropout appears as a notch that then drifts out to the edge, so a
 *    stutter half a second ago is still on screen when you look up.
 *
 * 🔑 It samples on its own clock rather than on recomposition, so a stream sitting at one level
 *    still scrolls, and one that stops decays to the floor instead of freezing mid-bar.
 */
private const val COLUMNS_PER_SIDE = 11

/** Matches the service's publish interval: one bar per snapshot, no interpolation, no aliasing. */
private const val FRAME_MS = 100L

@Composable
fun LevelMeter(
    left: Float,
    right: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 104.dp,
) {
    // 🔑 Plain arrays and an index, not a list that is rebuilt ten times a second. The arrays are
    //    written by the sampling loop and read by the draw, both on the main thread.
    val leftBars = remember { FloatArray(COLUMNS_PER_SIDE) }
    val rightBars = remember { FloatArray(COLUMNS_PER_SIDE) }
    var head by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(Triple(left, right, active))

    LaunchedEffect(Unit) {
        while (true) {
            delay(FRAME_MS)
            val (l, r, on) = latest
            head = (head + 1) % COLUMNS_PER_SIDE
            leftBars[head] = if (on) l else 0f
            rightBars[head] = if (on) r else 0f
        }
    }

    Canvas(modifier.fillMaxWidth().height(height)) {
        // `head` is read here so that advancing it redraws; the arrays alone would not.
        val newest = head
        val gap = 3.dp.toPx()
        val columns = COLUMNS_PER_SIDE * 2
        val barWidth = ((size.width - gap * (columns - 1)) / columns).coerceAtLeast(1f)
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        val floor = 3.dp.toPx()
        val colour = if (active) Tone.Accent else Tone.Border

        for (i in 0 until columns) {
            // Column 0 is the far left: the oldest left-channel sample. The middle two columns are
            // the newest of each side.
            val fromCentre = if (i < COLUMNS_PER_SIDE) COLUMNS_PER_SIDE - 1 - i else i - COLUMNS_PER_SIDE
            val source = if (i < COLUMNS_PER_SIDE) leftBars else rightBars
            val value = source[Math.floorMod(newest - fromCentre, COLUMNS_PER_SIDE)]
            val barHeight = (value * size.height).coerceAtLeast(floor)
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = if (value > 0f) colour else Tone.Border.copy(alpha = ZERO_BAR_ALPHA),
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/** A column with nothing in it is drawn as a stub, so the strip keeps its shape while silent. */
private const val ZERO_BAR_ALPHA = 0.7f
