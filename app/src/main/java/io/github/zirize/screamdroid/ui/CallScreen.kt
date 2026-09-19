package io.github.zirize.screamdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zirize.screamdroid.R
import io.github.zirize.screamdroid.service.PauseCause
import io.github.zirize.screamdroid.service.ReceiverSnapshot

/**
 * What the main screen turns into while something else has the speaker.
 *
 * 🔴 **The middle card is the reason this screen was drawn at all.** Muting here means *keep
 *    reading the socket and throw the audio away* - and the obvious implementation, stopping the
 *    reads, is wrong in a way that only shows up after a three-minute call: the kernel queue and
 *    the ring fill up, and hanging up plays three minutes of stale audio. Writing the reason on
 *    the screen is how the next person to touch this finds out before changing it.
 *
 * ℹ️ **Reached from the main screen as a preview until M5.** Audio focus is what will bring it up
 *    by itself; the counters below are live either way, because muting by hand uses exactly the
 *    same machinery a call will.
 */
@Composable
fun CallScreen(snapshot: ReceiverSnapshot, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = PageMargin, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Tone.Muted,
            )
            IconAction(R.drawable.ic_back, stringResource(R.string.action_back), onBack, tint = Tone.Text)
        }

        Column(
            Modifier.padding(horizontal = PageMargin),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            Banner(snapshot)

            Card(padding = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Tone.Warn, CircleShape))
                    Text(
                        text = stringResource(R.string.state_paused),
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AppSwitch(checked = false, onCheckedChange = {}, enabled = false)
                }
                // 🔑 The meter is deliberately flat rather than hidden: the card must keep its
                //    shape, because what it is saying is "still here, just silent".
                LevelMeter(
                    left = 0f,
                    right = 0f,
                    active = false,
                    modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("L", style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = Tone.Dim)
                    Spacer(Modifier.weight(1f))
                    Text("R", style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = Tone.Dim)
                }
                // 🔑 The fader stays, greyed: it has not been moved, and hiding it would suggest
                //    that coming back means setting the volume again.
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_off),
                        contentDescription = null,
                        tint = Tone.Dim,
                        modifier = Modifier.size(19.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Tone.Border),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(snapshot.settings.volumePercent / 100f)
                                .height(6.dp)
                                .background(Tone.Dim),
                        )
                    }
                    Text(
                        text = stringResource(R.string.percent, snapshot.settings.volumePercent),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = Mono,
                        color = Tone.Dim,
                        textAlign = TextAlign.End,
                    )
                }
            }

            Card(padding = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_discard),
                        contentDescription = null,
                        tint = Tone.Accent,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = stringResource(R.string.call_discard_title),
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Note(stringResource(R.string.call_discard_body), Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 14.dp)) {
                    Text(
                        text = stringResource(R.string.call_discarded, Format.count(snapshot.discarded)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = Mono,
                        color = Tone.Muted,
                    )
                    Text(
                        text = "    " + stringResource(
                            R.string.call_elapsed,
                            Format.duration(snapshot.mutedForMs),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = Mono,
                        color = Tone.Muted,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.call_footer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageMargin),
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 20.sp,
            color = Tone.Dim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * 🔑 Says which of the two it is. Muting by hand and being muted by a call look the same from
 *    underneath, and the one sentence a person needs - "you do not have to do anything" - is only
 *    true for one of them.
 */
@Composable
private fun Banner(snapshot: ReceiverSnapshot) {
    val muted = snapshot.paused
    // 🔑 Three different pieces of news, and only one of them ends by itself. Audio focus is what
    //    notices; the audio mode is what can say it was a call rather than an alarm or another app.
    val title = when (snapshot.pauseCause) {
        PauseCause.CALL -> R.string.call_banner_title
        PauseCause.FOCUS -> R.string.focus_banner_title
        PauseCause.USER -> R.string.mute_banner_title
        PauseCause.NONE -> R.string.call_preview_title
    }
    val body = when (snapshot.pauseCause) {
        PauseCause.CALL -> R.string.call_banner_body
        PauseCause.FOCUS -> R.string.focus_banner_body
        PauseCause.USER -> R.string.mute_banner_body
        PauseCause.NONE -> R.string.call_preview_body
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (muted) Tone.Warn.copy(alpha = 0.12f) else Tone.SoftCard)
            .border(BorderStroke(1.dp, if (muted) Tone.Warn else Tone.Border), shape)
            .padding(18.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_phone),
            contentDescription = null,
            tint = if (muted) Tone.Warn else Tone.Dim,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (muted) Tone.Warn else Tone.Muted,
            )
            Note(stringResource(body), Modifier.padding(top = 4.dp))
        }
    }
}
