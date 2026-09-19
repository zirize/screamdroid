package io.github.zirize.screamdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zirize.screamdroid.R
import io.github.zirize.screamdroid.service.EventLog
import io.github.zirize.screamdroid.service.ReceiverService
import io.github.zirize.screamdroid.service.ReceiverSnapshot

/**
 * Where to look when it is not working.
 *
 * 🔴 **Network audio is almost entirely a question of "why can I not hear it".** The processes
 *    are fine, the logs are fine, and there is no sound - so this screen exists to turn that into
 *    numbers: how much is arriving, how deep the cushion is, how often it ran dry, and what
 *    happened recently. Each of the four tiles distinguishes a different failure.
 *
 * 🔑 **"Copy" is a first-class button.** The person who can fix a sender misconfiguration is
 *    usually at the PC, not holding the phone, and reading twelve figures out loud is how
 *    diagnosis goes wrong.
 */
@Composable
fun DiagnosticsScreen(
    snapshot: ReceiverSnapshot,
    history: List<Int>,
    events: List<EventLog.Entry>,
    batteryExempt: Boolean,
    onBack: () -> Unit,
    onResetStats: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val report = buildReport(snapshot, events, batteryExempt)

    Column(Modifier.fillMaxWidth()) {
        SubScreenBar(stringResource(R.string.screen_diagnostics), onBack)

        Column(
            Modifier.padding(horizontal = PageMargin),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                StatTile(
                    label = stringResource(R.string.diag_rate),
                    value = Format.mbps(snapshot.kbitPerSecond),
                    unit = stringResource(R.string.unit_mbps),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.diag_packets),
                    value = snapshot.packetsPerSecond.toString(),
                    unit = stringResource(R.string.unit_per_second),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                StatTile(
                    label = stringResource(R.string.diag_buffer_now),
                    value = snapshot.latencyMs.toString(),
                    unit = stringResource(R.string.unit_ms_bare),
                    modifier = Modifier.weight(1f),
                    accent = true,
                )
                StatTile(
                    label = stringResource(R.string.diag_dropouts),
                    value = snapshot.underruns.toString(),
                    unit = "",
                    modifier = Modifier.weight(1f),
                )
            }

            Card(padding = PaddingValues(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.diag_buffer_trend),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.diag_last_minutes, ReceiverService.HISTORY_SPAN_MINUTES),
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Dim,
                    )
                }
                BufferSparkline(
                    points = history,
                    targetMs = snapshot.effectiveTargetMs,
                    maxMs = snapshot.effectiveMaxMs,
                    modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.diag_trend_scale,
                            snapshot.effectiveMaxMs,
                            snapshot.effectiveTargetMs,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        color = Tone.Dim,
                    )
                    Text(
                        text = stringResource(R.string.diag_trend_now, snapshot.latencyMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        color = Tone.Accent,
                    )
                }
            }

            Card(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                ValueRow(
                    stringResource(R.string.diag_last_sender),
                    snapshot.sender?.let { "$it:${snapshot.senderPort}" }
                        ?: stringResource(R.string.value_none),
                )
                RowDivider()
                ValueRow(
                    stringResource(R.string.diag_format),
                    snapshot.format?.let {
                        "${it.sampleRate} · ${it.bitsPerSample} · ${it.channels} · " +
                            "mask 0x%x".format(it.channelMask)
                    } ?: stringResource(R.string.value_none),
                )
                RowDivider()
                // 🔑 Both figures, always. The trimmed size alone says nothing; next to what the
                //    device wanted to hand out it is the whole of what the latency work bought.
                ValueRow(
                    stringResource(R.string.diag_device_buffer),
                    if (snapshot.deviceBufferInitialMs > 0) {
                        stringResource(
                            R.string.diag_device_buffer_value,
                            snapshot.deviceBufferMs,
                            snapshot.deviceBufferInitialMs,
                        )
                    } else {
                        stringResource(R.string.value_none)
                    },
                )
                RowDivider()
                ValueRow(stringResource(R.string.diag_format_changes), Format.count(snapshot.formatChanges))
                RowDivider()
                ValueRow(stringResource(R.string.diag_discarded), Format.count(snapshot.discarded))
                RowDivider()
                ValueRow(stringResource(R.string.diag_rejected), Format.count(snapshot.malformed))
                RowDivider()
                // 🔑 Overflow is bytes the ring had no room for, not packets: the receiving thread
                //    writes what fits and counts the rest, so a packet can be half lost.
                ValueRow(stringResource(R.string.diag_overflow), Format.count(snapshot.overflowBytes))
                RowDivider()
                ValueRow(
                    stringResource(R.string.diag_trimmed),
                    stringResource(R.string.unit_ms, snapshot.droppedMs.toInt()),
                )
                RowDivider()
                // 🔑 Here because "it went quiet overnight" is answered by this line and nothing
                //    else on the screen: a frozen app leaves no counter moving, no dropout and no
                //    error - see service/PowerExemption.kt.
                ValueRow(
                    stringResource(R.string.diag_battery),
                    stringResource(
                        if (batteryExempt) {
                            R.string.diag_battery_unrestricted
                        } else {
                            R.string.diag_battery_optimised
                        },
                    ),
                )
                RowDivider()
                ValueRow(stringResource(R.string.diag_uptime), Format.duration(snapshot.uptimeMs))
            }

            Card(padding = PaddingValues(18.dp)) {
                Text(
                    text = stringResource(R.string.diag_recent),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.Dim,
                )
                Column(
                    Modifier.padding(top = 10.dp).heightIn(min = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (events.isEmpty()) {
                        Text(
                            text = stringResource(R.string.diag_recent_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tone.Dim,
                        )
                    }
                    for (event in events.take(VISIBLE_EVENTS)) {
                        EventLine(event)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                FlatButton(
                    text = stringResource(R.string.action_copy_report),
                    modifier = Modifier.weight(1f),
                    onClick = { clipboard.setText(AnnotatedString(report)) },
                )
                FlatButton(
                    text = stringResource(R.string.action_reset_counters),
                    modifier = Modifier.weight(1f),
                    onClick = onResetStats,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Enough to see what just happened without the card becoming the screen. */
private const val VISIBLE_EVENTS = 8

@Composable
private fun EventLine(event: EventLog.Entry) {
    val text = stringResource(event.kind.labelRes())
    Row {
        Text(
            text = Format.clock(event.atMillis),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = Mono,
            color = Tone.Dim,
        )
        Text(
            text = " " + if (event.detail != null) "$text  ${event.detail}" else text,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
            fontFamily = Mono,
            color = if (event.kind.isNotable()) Tone.Warn else Tone.Muted,
        )
    }
}

@Composable
private fun FlatButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Tone.Control)
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}

/** 🔑 Drawn in the warning colour: these are the lines somebody is scanning the list for. */
private fun EventLog.Kind.isNotable(): Boolean = when (this) {
    EventLog.Kind.UNDERRUN, EventLog.Kind.ERROR, EventLog.Kind.BLOCKED, EventLog.Kind.MUTED,
    EventLog.Kind.CALL_MUTED, EventLog.Kind.FOCUS_MUTED, EventLog.Kind.STOPPED_BY_FOCUS,
    EventLog.Kind.SUSPENDED -> true
    else -> false
}

internal fun EventLog.Kind.labelRes(): Int = when (this) {
    EventLog.Kind.STARTED -> R.string.event_started
    EventLog.Kind.STOPPED -> R.string.event_stopped
    EventLog.Kind.REBOUND -> R.string.event_rebound
    EventLog.Kind.FORMAT_CHANGED -> R.string.event_format_changed
    EventLog.Kind.DEVICE_BUFFER -> R.string.event_device_buffer
    EventLog.Kind.UNDERRUN -> R.string.event_underrun
    EventLog.Kind.DEVICE_RELEASED -> R.string.event_device_released
    EventLog.Kind.RESUMED -> R.string.event_resumed
    EventLog.Kind.SUSPENDED -> R.string.event_suspended
    EventLog.Kind.MUTED -> R.string.event_muted
    EventLog.Kind.UNMUTED -> R.string.event_unmuted
    EventLog.Kind.CALL_MUTED -> R.string.event_call_muted
    EventLog.Kind.FOCUS_MUTED -> R.string.event_focus_muted
    EventLog.Kind.DUCKED -> R.string.event_ducked
    EventLog.Kind.INTERRUPTION_OVER -> R.string.event_interruption_over
    EventLog.Kind.STOPPED_BY_FOCUS -> R.string.event_stopped_by_focus
    EventLog.Kind.BLOCKED -> R.string.event_blocked
    EventLog.Kind.COUNTERS_RESET -> R.string.event_counters_reset
    EventLog.Kind.ERROR -> R.string.event_error
}

/**
 * 🔑 **Plain ASCII, no translation.** This ends up pasted into a terminal or a message to
 *    somebody looking at the sender, where a localised label helps nobody.
 */
private fun buildReport(
    snapshot: ReceiverSnapshot,
    events: List<EventLog.Entry>,
    batteryExempt: Boolean,
): String =
    buildString {
        appendLine("screamdroid diagnostics")
        appendLine("battery     ${if (batteryExempt) "unrestricted" else "optimised (can be frozen)"}")
        appendLine("uptime      ${Format.duration(snapshot.uptimeMs)}")
        appendLine("state       ${snapshot.state}  paused=${snapshot.paused}  blocked=${snapshot.blocked}")
        appendLine("group       ${snapshot.settings.multicastGroup}:${snapshot.settings.multicastPort}")
        appendLine(
            "unicast     " + if (snapshot.settings.unicastEnabled) {
                ":${snapshot.settings.unicastPort}"
            } else {
                "off"
            },
        )
        appendLine("sender      ${snapshot.sender ?: "-"}:${snapshot.senderPort}")
        appendLine("format      ${snapshot.format ?: "-"}")
        appendLine("latency     ${snapshot.latencyMs} ms (device ${snapshot.deviceMs} ms)")
        appendLine("dev buffer  ${snapshot.deviceBufferMs} ms " +
            "(device offered ${snapshot.deviceBufferInitialMs} ms)")
        appendLine("targets     ${snapshot.effectiveTargetMs}/${snapshot.effectiveMaxMs} ms " +
            "(preset ${snapshot.settings.bufferPolicy})")
        appendLine("rate        ${snapshot.kbitPerSecond} kbit/s, ${snapshot.packetsPerSecond} pkt/s")
        appendLine("packets     ${snapshot.packets}")
        appendLine("underruns   ${snapshot.underruns}")
        appendLine("trimmed     ${snapshot.droppedMs} ms")
        appendLine("overflow    ${snapshot.overflowBytes} bytes")
        appendLine("rejected    ${snapshot.malformed}")
        appendLine("discarded   ${snapshot.discarded}")
        appendLine("fmt changes ${snapshot.formatChanges}")
        appendLine()
        for (event in events) {
            appendLine("${Format.clock(event.atMillis)}  ${event.kind}${event.detail?.let { "  $it" } ?: ""}")
        }
    }
