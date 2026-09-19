package io.github.zirize.screamdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import io.github.zirize.screamdroid.R

/**
 * The one screen that is shown without being asked for.
 *
 * 🔴 **It exists for the step that has no symptom.** Everything else a newcomer can get wrong -
 *    the wrong address, the wrong mode - is silent *immediately*, and the main screen says so.
 *    Battery optimisation is the opposite: it works, and then hours later the phone freezes the
 *    app with the screen off and the sound never comes back (see service/PowerExemption.kt).
 *    Nothing on any screen can explain that at the moment it happens, because the app is not
 *    running to explain it, so it has to be said before it happens.
 *
 * 🔑 **Three things to do and one to know, in the order they bite.** The battery exemption first
 *    because it is invisible; the address second because it is the only thing the *PC* needs; the
 *    tile third because there is no start-on-boot to fall back on. The status bar tip is last and
 *    unnumbered - it is a preference, not a step.
 *
 * 🔑 **Each step carries its own doing.** A guide that says "go to Settings and find..." is a
 *    guide that gets closed, so the two steps that can be done from here have the button that
 *    does them, and the one with a state to check shows that state rather than asking.
 */
@Composable
fun GuideScreen(
    firstRun: Boolean,
    address: String?,
    batteryExempt: Boolean,
    onFixBattery: () -> Unit,
    onAddTile: () -> Unit,
    onDone: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = PageMargin)) {
            Text(
                text = stringResource(R.string.guide_title),
                modifier = Modifier.padding(top = 22.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.guide_intro),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Muted,
            )
        }

        Column(
            Modifier.padding(horizontal = PageMargin, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            // 🔴 First on purpose - the only step whose absence is silent until it is too late.
            Step(
                number = 1,
                title = stringResource(R.string.guide_battery_title),
                body = stringResource(R.string.guide_battery_body),
                done = batteryExempt,
            ) {
                if (batteryExempt) {
                    DoneNote(stringResource(R.string.guide_battery_done))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GuideButton(stringResource(R.string.power_warning_action), onFixBattery)
                        Text(
                            text = stringResource(R.string.power_warning_where),
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tone.Dim,
                        )
                    }
                }
            }

            Step(
                number = 2,
                title = stringResource(R.string.guide_address_title),
                body = stringResource(R.string.guide_address_body),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = address ?: stringResource(R.string.address_unknown),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = Mono,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (address != null) {
                        IconAction(
                            icon = R.drawable.ic_copy,
                            contentDescription = stringResource(R.string.action_copy),
                            onClick = { clipboard.setText(AnnotatedString(address)) },
                            boxed = true,
                            tint = Tone.Text,
                        )
                    }
                }
            }

            Step(
                number = 3,
                title = stringResource(R.string.guide_tile_title),
                body = stringResource(R.string.guide_tile_body),
            ) {
                GuideButton(stringResource(R.string.settings_add_tile), onAddTile)
            }

            // 🔑 Unnumbered: a preference rather than a step, and the only honest answer to
            //    "can I get rid of the status bar icon" - the app cannot remove it itself.
            Card(padding = PaddingValues(18.dp)) {
                Text(
                    text = stringResource(R.string.guide_icon_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Tone.Muted,
                )
                Note(stringResource(R.string.guide_icon_body), Modifier.padding(top = 6.dp))
            }

            Note(stringResource(R.string.guide_reopen_note), Modifier.padding(top = 2.dp))

            GuideButton(
                text = stringResource(
                    if (firstRun) R.string.guide_start else R.string.action_close,
                ),
                onClick = onDone,
                filled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One numbered thing to do: what it is, why, and the button that does it. */
@Composable
private fun Step(
    number: Int,
    title: String,
    body: String,
    done: Boolean = false,
    action: @Composable () -> Unit,
) {
    Card(padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (done) Tone.Accent else Tone.Control)
                    .border(BorderStroke(1.dp, Tone.Border), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    color = if (done) Tone.Background else Tone.Muted,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Note(body, Modifier.padding(top = 6.dp))
            }
        }
        Box(Modifier.padding(top = 14.dp)) { action() }
    }
}

/** 🔑 The step that is already done says so rather than offering a button that changes nothing. */
@Composable
private fun DoneNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Tone.Accent,
    )
}

@Composable
private fun GuideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) Tone.Accent else Tone.Control)
            .clickable(onClick = onClick)
            .heightIn(min = TouchTarget)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
            color = if (filled) Tone.Background else Tone.Text,
        )
    }
}
