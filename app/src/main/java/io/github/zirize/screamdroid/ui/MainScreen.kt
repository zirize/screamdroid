package io.github.zirize.screamdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zirize.screamdroid.R
import io.github.zirize.screamdroid.net.ScreamReceiver
import io.github.zirize.screamdroid.service.Blocked
import io.github.zirize.screamdroid.service.PauseCause
import io.github.zirize.screamdroid.service.ReceiverSnapshot
import io.github.zirize.screamdroid.service.StopReason

/**
 * The only screen most days.
 *
 * 🔑 **"Is sound coming out" has to be answerable in a second, from across a desk.** So the top
 *    of the screen is a coloured word, a switch and a meter that moves, and the numbers - format,
 *    latency, uptime - sit underneath in one card for when the answer is no.
 *
 * 🔑 **The address is at the bottom because it is the one thing the *PC* needs.** Getting audio
 *    to this phone means writing this exact string into a file on the machine that sends, and a
 *    mistyped octet looks identical to a quiet PC from here. It is copyable and scannable for
 *    that reason alone.
 */
@Composable
fun MainScreen(
    snapshot: ReceiverSnapshot,
    stopReason: StopReason,
    address: String?,
    batteryRestricted: Boolean,
    onToggle: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onFixBattery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCall: () -> Unit,
) {
    var showQr by remember { mutableStateOf(false) }
    val settings = snapshot.settings
    // 🔑 **What the sender has to be told, which is not always this phone's address.** With
    //    unicast off the group is the only way in, so the group is what is shown and put in the
    //    QR code; with it on, the address that has to be typed somewhere is this phone's, and the
    //    group address is a constant that was set once.
    val multicast = !settings.unicastEnabled
    val shown = if (multicast) {
        "${settings.multicastGroup}:${settings.multicastPort}"
    } else {
        address?.let { "$it:${settings.unicastPort}" }
    }

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
            )
            IconAction(R.drawable.ic_activity, stringResource(R.string.screen_diagnostics), onOpenDiagnostics)
            IconAction(R.drawable.ic_settings, stringResource(R.string.screen_settings), onOpenSettings)
        }

        Column(
            Modifier.padding(horizontal = PageMargin),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            StatusCard(snapshot, stopReason, onToggle, onVolumeChange)
            // 🔴 Under the state and above everything else: it is not about this moment, which is
            //    what the card above answers, but it decides whether there will be a next one.
            if (batteryRestricted) PowerWarningCard(onFixBattery)
            StreamCard(snapshot, onOpenDiagnostics)
            AddressCard(shown, multicast) { showQr = true }
        }

        Spacer(Modifier.height(28.dp))
        CallPreviewLink(onOpenCall)
        Spacer(Modifier.height(16.dp))
    }

    if (showQr && shown != null) {
        QrDialog(shown) { showQr = false }
    }
}

/** State, the master switch, the meter and the fader - everything that is about *now*. */
@Composable
private fun StatusCard(
    snapshot: ReceiverSnapshot,
    stopReason: StopReason,
    onToggle: () -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    Card(padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(stateColour(snapshot), CircleShape))
            Text(
                text = stringResource(stateLabel(snapshot)),
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            AppSwitch(checked = snapshot.running, onCheckedChange = { onToggle() })
        }

        // 🔑 Every line here answers "why is it not making a sound *right now*", and each one is
        //    something that does not happen often. Being blocked wins because it is the only one
        //    the person can act on.
        // 🚫 **Waiting is deliberately not among them.** With silence suppression a quiet PC is
        //    the ordinary state, not news - the explanation would be on the screen nearly all the
        //    time and say nothing (2026-09-18). The notification still carries it, where that one
        //    line is the only text there is: see ReceiverNotification.
        val reason = when {
            snapshot.blocked == Blocked.MOBILE_DATA -> R.string.blocked_mobile_data
            snapshot.error != null -> null
            // 🔑 A receiver that switched *itself* off owes an explanation; "it is just off now"
            //    is the silent mystery this app exists to avoid.
            !snapshot.running && stopReason == StopReason.FOCUS_LOST -> R.string.stopped_by_focus
            snapshot.interrupted -> R.string.interrupted_reason
            snapshot.ducked -> R.string.ducked_reason
            else -> null
        }
        if (reason != null) {
            Note(stringResource(reason), Modifier.padding(top = 8.dp))
        }
        snapshot.error?.let {
            Note(it, Modifier.padding(top = 8.dp), color = Tone.Error)
        }

        LevelMeter(
            left = snapshot.peakLeft,
            right = snapshot.peakRight,
            active = snapshot.playing,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("L", style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = Tone.Dim)
            Spacer(Modifier.weight(1f))
            Text("R", style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = Tone.Dim)
        }

        VolumeRow(snapshot, onVolumeChange)
    }
}

/**
 * 🔑 **The one warning that is about later rather than now.** Everything else on this screen
 *    answers "is sound coming out"; this says the phone is allowed to freeze the app the next
 *    time it is left alone, which is a silence that arrives with nothing on screen to explain it
 *    - the app is not running to explain anything. See service/PowerExemption.kt.
 *
 * 🔑 **It disappears by being fixed, so it never has to be dismissed.** A dismiss button would
 *    only offer to hide the one thing that will make the app stop working.
 */
@Composable
private fun PowerWarningCard(onFix: () -> Unit) {
    Card(padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Tone.Warn, CircleShape))
            Text(
                text = stringResource(R.string.power_warning_title),
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tone.Warn,
            )
        }
        Note(stringResource(R.string.power_warning_body), Modifier.padding(top = 8.dp))
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.power_warning_action),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tone.Control)
                    .clickable(onClick = onFix)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            // 🔑 The taps are written out because the button lands on a page, not on the switch:
            //    asking for it directly needs a restricted permission - see PowerExemption.open.
            Text(
                text = stringResource(R.string.power_warning_where),
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Dim,
            )
        }
    }
}

/**
 * 🔑 **This fader is the app's own, not the phone's.** Turning the phone down turns everything
 *    down; this turns down the one stream, which is what somebody reaches for when the PC is
 *    louder than the room wants. The percentage is decibels of travel, not a multiplier - see
 *    audio/Volume.kt.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VolumeRow(snapshot: ReceiverSnapshot, onVolumeChange: (Int) -> Unit) {
    val percent = snapshot.settings.volumePercent
    // 🔑 Dragged locally and published on each change: the setting round-trips through DataStore
    //    and the service, and a thumb that waited for that would stutter under the finger.
    var dragged by remember(percent) { mutableStateOf(percent.toFloat()) }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                if (percent == 0 || snapshot.paused) R.drawable.ic_volume_off else R.drawable.ic_volume
            ),
            contentDescription = stringResource(R.string.volume),
            tint = Tone.Muted,
            modifier = Modifier.size(19.dp),
        )
        // 🔑 Track and thumb are supplied rather than taken from the defaults: Material's own
        //    fader draws a bar-shaped thumb, a gap either side of it and a dot at the far end,
        //    which reads as three controls instead of one. This is the drawn shape - a line and
        //    a dot - and nothing else.
        val sliderColors = SliderDefaults.colors(
            thumbColor = Tone.Accent,
            activeTrackColor = Tone.Accent,
            inactiveTrackColor = Tone.Border,
        )
        Slider(
            value = dragged,
            onValueChange = {
                dragged = it
                onVolumeChange(it.toInt())
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            colors = sliderColors,
            thumb = {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Tone.Accent),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = sliderColors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    modifier = Modifier.height(6.dp),
                )
            },
        )
        Text(
            text = stringResource(R.string.percent, percent),
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = Mono,
            color = Tone.Muted,
            textAlign = TextAlign.End,
        )
    }
}

/** Where it is coming from, in what format, how deep the cushion is, and how long it has held. */
@Composable
private fun StreamCard(snapshot: ReceiverSnapshot, onOpenDiagnostics: () -> Unit) {
    Card(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelCell(stringResource(R.string.stream_from))
            Text(
                text = snapshot.sender ?: stringResource(R.string.value_none),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = Mono,
                color = Tone.Muted,
            )
        }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelCell(stringResource(R.string.stream_format))
            Text(
                text = snapshot.format?.let {
                    stringResource(R.string.format_value, it.sampleRate / 1000, it.bitsPerSample, it.channels)
                } ?: stringResource(R.string.value_none),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = Mono,
                color = Tone.Muted,
            )
        }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelCell(stringResource(R.string.stream_buffer))
            FillBar(
                fraction = if (snapshot.effectiveMaxMs > 0) {
                    snapshot.latencyMs.toFloat() / snapshot.effectiveMaxMs
                } else {
                    0f
                },
                modifier = Modifier.width(88.dp),
            )
            Text(
                text = stringResource(R.string.unit_ms, snapshot.latencyMs),
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = Mono,
                color = Tone.Muted,
            )
            Text(
                text = stringResource(R.string.action_details),
                modifier = Modifier.clickable(onClick = onOpenDiagnostics).padding(6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Accent,
            )
        }
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelCell(stringResource(R.string.stream_uptime))
            Text(
                text = Format.duration(snapshot.uptimeMs) + "  ·  " +
                    stringResource(R.string.stream_dropouts, snapshot.underruns),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = Mono,
                color = Tone.Muted,
            )
        }
    }
}

/** What has to be written on the PC, in one line, copyable and scannable. */
@Composable
private fun AddressCard(shown: String?, multicast: Boolean, onShowQr: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Card(
        dashed = true,
        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(if (multicast) R.string.address_group_title else R.string.address_title),
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Dim,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shown ?: stringResource(R.string.address_unknown),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = Mono,
                fontWeight = FontWeight.SemiBold,
            )
            if (shown != null) {
                IconAction(
                    icon = R.drawable.ic_copy,
                    contentDescription = stringResource(R.string.action_copy),
                    onClick = { clipboard.setText(AnnotatedString(shown)) },
                    boxed = true,
                    tint = Tone.Text,
                )
                Spacer(Modifier.width(10.dp))
                IconAction(
                    icon = R.drawable.ic_qr,
                    contentDescription = stringResource(R.string.action_qr),
                    onClick = onShowQr,
                    boxed = true,
                    tint = Tone.Text,
                )
            }
        }
    }
}

@Composable
private fun CallPreviewLink(onOpenCall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenCall)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_phone),
            contentDescription = null,
            tint = Tone.Accent,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = stringResource(R.string.call_preview_link),
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp,
            color = Tone.Accent,
        )
    }
}

internal fun stateLabel(snapshot: ReceiverSnapshot): Int = when {
    !snapshot.running -> R.string.state_stopped
    snapshot.blocked == Blocked.MOBILE_DATA -> R.string.state_blocked
    snapshot.pauseCause == PauseCause.CALL -> R.string.state_call
    snapshot.pauseCause == PauseCause.FOCUS -> R.string.state_focus
    snapshot.paused -> R.string.state_paused
    snapshot.state == ScreamReceiver.State.PLAYING -> R.string.state_playing
    snapshot.state == ScreamReceiver.State.ERROR -> R.string.state_error
    else -> R.string.state_waiting
}

internal fun stateColour(snapshot: ReceiverSnapshot): Color = when {
    !snapshot.running -> Tone.Border
    snapshot.blocked == Blocked.MOBILE_DATA -> Tone.Warn
    snapshot.paused -> Tone.Warn
    snapshot.ducked -> Tone.Warn
    snapshot.state == ScreamReceiver.State.PLAYING -> Tone.Accent
    snapshot.state == ScreamReceiver.State.ERROR -> Tone.Error
    else -> Tone.Dim
}
