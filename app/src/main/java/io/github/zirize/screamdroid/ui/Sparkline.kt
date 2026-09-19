package io.github.zirize.screamdroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Latency over the last few minutes, against the two figures that govern it.
 *
 * 🔑 **One series, so no axes, no grid and no legend.** The title says what it is and the two
 *    dashed lines say what "good" means; anything else would be furniture. What the eye is
 *    looking for here is shape - a slow climb, or a sawtooth that says the receiver is trimming
 *    over and over - and that reads better without a frame around it.
 *
 * 🔑 The vertical scale is fixed to the ceiling rather than to the data, so the line does not
 *    silently rescale when it is calm and make a flat stretch look dramatic.
 */
@Composable
fun BufferSparkline(
    points: List<Int>,
    targetMs: Int,
    maxMs: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxWidth().height(72.dp)) {
        val top = maxOf(maxMs, 1) * HEADROOM
        fun y(ms: Int): Float = size.height * (1f - (ms / top).coerceIn(0f, 1f))

        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
        for (line in listOf(maxMs, targetMs)) {
            if (line <= 0) continue
            drawLine(
                color = Tone.Border,
                start = Offset(0f, y(line)),
                end = Offset(size.width, y(line)),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }

        if (points.size < 2) return@Canvas
        val step = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, ms ->
            val px = i * step
            val py = y(ms)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color = Tone.Accent, style = Stroke(width = 1.8.dp.toPx()))
    }
}

/** 🔑 A little room above the ceiling, so an excursion past it is visible rather than clipped. */
private const val HEADROOM = 1.35f
