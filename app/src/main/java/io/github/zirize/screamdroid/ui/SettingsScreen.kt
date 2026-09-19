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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zirize.screamdroid.BuildConfig
import io.github.zirize.screamdroid.R
import io.github.zirize.screamdroid.audio.BufferPolicy
import io.github.zirize.screamdroid.service.ReceiverSnapshot
import io.github.zirize.screamdroid.settings.CallBehavior
import io.github.zirize.screamdroid.settings.Settings

/**
 * Everything that can be changed, grouped by what it is for.
 *
 * 🔑 **No Apply button anywhere.** A setting that needs confirming is a setting people set
 *    wrongly and never notice. Each of these reaches the running receiver by itself - the buffer
 *    preset on its next turn, the port by rebinding the socket underneath - and the note at the
 *    bottom of the first card says so rather than leaving it to be discovered.
 *
 * 🔑 **A thing that cannot be done is greyed out with its reason beside it, not removed.** "Start
 *    on boot" is the case: take it away and people keep looking for it, and then go and look for
 *    a second app that has it.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    snapshot: ReceiverSnapshot,
    onBack: () -> Unit,
    onUnicastPortChange: (Int) -> Unit,
    onMulticastPortChange: (Int) -> Unit,
    onUnicastEnabledChange: (Boolean) -> Unit,
    onGroupChange: (String) -> Unit,
    onBufferPolicyChange: (BufferPolicy) -> Unit,
    onCallBehaviorChange: (CallBehavior) -> Unit,
    onDuckChange: (Boolean) -> Unit,
    onShareChange: (Boolean) -> Unit,
    onSaveBatteryChange: (Boolean) -> Unit,
    onLowLatencyWifiChange: (Boolean) -> Unit,
    onAllowMobileDataChange: (Boolean) -> Unit,
    onAddTile: () -> Unit,
    onShowGuide: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SubScreenBar(stringResource(R.string.screen_settings), onBack)

        Column(
            Modifier.padding(horizontal = PageMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_group_receiving), Modifier.padding(top = 10.dp))
            // 🔑 **The group comes first because it is the one that is always on.** Reading down
            //    this card is meant to leave the right impression: the group is how the sound
            //    finds this phone anywhere, and unicast is the extra that is tied to one network.
            Card(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                GroupRow(settings, onGroupChange)
                RowDivider()
                PortRow(
                    title = stringResource(R.string.settings_multicast_port),
                    port = settings.multicastPort,
                    onPortChange = onMulticastPortChange,
                )
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_unicast),
                    supporting = stringResource(R.string.settings_unicast_note),
                ) {
                    AppSwitch(settings.unicastEnabled, onUnicastEnabledChange)
                }
                RowDivider()
                PortRow(
                    title = stringResource(R.string.settings_unicast_port),
                    port = settings.unicastPort,
                    onPortChange = onUnicastPortChange,
                    enabled = settings.unicastEnabled,
                )
            }
            Note(stringResource(R.string.settings_receiving_note))

            SectionLabel(stringResource(R.string.settings_buffer), Modifier.padding(top = 10.dp))
            Card(padding = PaddingValues(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (policy in BufferPolicy.entries) {
                        PresetBox(
                            policy = policy,
                            selected = policy == settings.bufferPolicy,
                            onSelect = { onBufferPolicyChange(policy) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Note(stringResource(R.string.settings_buffer_note), Modifier.padding(top = 12.dp))
                // 🔑 Said out loud rather than left to be discovered: on a device whose audio
                //    buffer is deeper than the preset asks for, the preset does not win, and
                //    pretending otherwise would make the numbers above a lie.
                if (snapshot.running && snapshot.effectiveTargetMs > settings.bufferPolicy.targetMs) {
                    Note(
                        stringResource(
                            R.string.settings_buffer_floor,
                            snapshot.effectiveTargetMs,
                            snapshot.effectiveMaxMs,
                        ),
                        Modifier.padding(top = 6.dp),
                        color = Tone.Accent,
                    )
                }
            }
            Note(stringResource(R.string.settings_applies_now))

            SectionLabel(stringResource(R.string.settings_group_interruptions), Modifier.padding(top = 10.dp))
            Card(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                SettingRow(stringResource(R.string.settings_on_call)) {
                    Segmented(
                        options = CallBehavior.entries,
                        selected = settings.callBehavior,
                        label = { stringResource(it.labelRes()) },
                        onSelect = onCallBehaviorChange,
                        modifier = Modifier.width(210.dp),
                    )
                }
                RowDivider()
                SettingRow(stringResource(R.string.settings_duck_on_notification)) {
                    AppSwitch(settings.duckOnNotification, onDuckChange)
                }
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_share),
                    supporting = stringResource(R.string.settings_share_note),
                ) {
                    AppSwitch(settings.shareWithOthers, onShareChange)
                }
            }
            Note(stringResource(R.string.settings_call_note))

            SectionLabel(stringResource(R.string.settings_group_power), Modifier.padding(top = 10.dp))
            Card(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                SettingRow(
                    title = stringResource(R.string.settings_save_battery),
                    supporting = stringResource(R.string.settings_save_battery_note),
                ) {
                    AppSwitch(settings.saveBatteryWhenSilent, onSaveBatteryChange)
                }
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_low_latency_wifi),
                    supporting = stringResource(R.string.settings_low_latency_wifi_note),
                ) {
                    AppSwitch(settings.lowLatencyWifi, onLowLatencyWifiChange)
                }
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_allow_mobile),
                    supporting = stringResource(R.string.settings_allow_mobile_note),
                ) {
                    AppSwitch(settings.allowMobileData, onAllowMobileDataChange)
                }
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_start_on_boot),
                    supporting = stringResource(R.string.settings_start_on_boot_why),
                    enabled = false,
                ) {
                    Box(
                        Modifier
                            .border(BorderStroke(1.dp, Tone.Border), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_not_possible),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tone.Dim,
                        )
                    }
                }
                RowDivider()
                SettingRow(
                    title = stringResource(R.string.settings_add_tile),
                    onClick = onAddTile,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_forward),
                        contentDescription = null,
                        tint = Tone.Dim,
                        modifier = Modifier.size(18.dp),
                    )
                }
                RowDivider()
                // 🔑 Kept reachable rather than shown once and gone: two of its three steps are
                //    done somewhere else on the phone, so it is the page somebody comes back to
                //    after a factory reset, a new phone, or a battery setting that got changed.
                SettingRow(
                    title = stringResource(R.string.settings_show_guide),
                    onClick = onShowGuide,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_forward),
                        contentDescription = null,
                        tint = Tone.Dim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            VersionFooter()
        }
    }
}

/**
 * 🔑 **The version goes on this page rather than behind an "About" screen.** It is wanted
 *    at exactly one moment - something is wrong and somebody is being asked which build this is -
 *    and that is the worst moment to send them hunting for it.
 * ℹ️ The build number is next to the name because a store can carry several builds under one
 *    version name, and it is the number that tells them apart.
 */
@Composable
private fun VersionFooter() {
    Text(
        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = Mono,
        color = Tone.Dim,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 24.dp),
    )
}

/**
 * 🔑 **Narrow, right-aligned and numeric**, because it holds four digits and nothing else. A
 *    full-width field here would read as "type a sentence".
 */
@Composable
private fun PortRow(
    title: String,
    port: Int,
    onPortChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val parsed = Settings.parsePort(text)
    SettingRow(
        title = title,
        supporting = if (enabled && parsed == null) {
            stringResource(R.string.settings_port_invalid, Settings.MIN_PORT, Settings.MAX_PORT)
        } else {
            null
        },
        enabled = enabled,
    ) {
        CompactField(
            value = text,
            onValueChange = {
                text = it
                Settings.parsePort(it)?.let(onPortChange)
            },
            isError = enabled && parsed == null,
            enabled = enabled,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(110.dp),
        )
    }
}

/**
 * 🔴 Validated before it is stored: `joinGroup` throws on an address outside 224.0.0.0/4, and
 *    it would throw on the receive thread. A typo must not be able to stop the audio.
 */
@Composable
private fun GroupRow(settings: Settings, onGroupChange: (String) -> Unit) {
    val enabled = true
    var text by remember(settings.multicastGroup) { mutableStateOf(settings.multicastGroup) }
    val parsed = Settings.parseGroup(text)
    SettingRow(
        title = stringResource(R.string.settings_group_address),
        supporting = if (enabled && parsed == null) stringResource(R.string.settings_group_invalid) else null,
        enabled = enabled,
    ) {
        CompactField(
            value = text,
            onValueChange = {
                text = it
                Settings.parseGroup(it)?.let(onGroupChange)
            },
            isError = enabled && parsed == null,
            enabled = enabled,
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(185.dp),
        )
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        // 🔑 One step down from the row's own type, and monospaced: an address is thirteen
        //    characters of digits and dots, and at body-large it does not fit the box the mockup
        //    draws without the last octet sliding out of sight.
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = Mono,
            textAlign = TextAlign.End,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Tone.Accent,
            unfocusedBorderColor = Tone.Border,
            disabledBorderColor = Tone.Border,
            focusedContainerColor = Tone.Control,
            unfocusedContainerColor = Tone.Control,
            disabledContainerColor = Tone.Control,
            disabledTextColor = Tone.Dim,
        ),
    )
}

/**
 * 🔑 **The pair of numbers is on the button.** The preset only means anything as a trade-off, and
 *    a name alone ("Balanced") does not say what is being traded; the target and the ceiling do.
 */
@Composable
private fun PresetBox(
    policy: BufferPolicy,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Tone.Accent else Tone.Border), shape)
            .background(if (selected) Tone.Card else Tone.Control, shape)
            .clickable(onClick = onSelect)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(policy.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Tone.Text else Tone.Muted,
            maxLines = 1,
        )
        Text(
            text = "${policy.targetMs} / ${policy.maxMs}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = Mono,
            color = if (selected) Tone.Accent else Tone.Dim,
            maxLines = 1,
        )
    }
}

internal fun BufferPolicy.labelRes(): Int = when (this) {
    BufferPolicy.LOW_LATENCY -> R.string.preset_low_latency
    BufferPolicy.BALANCED -> R.string.preset_balanced
    BufferPolicy.STABLE -> R.string.preset_stable
}

internal fun CallBehavior.labelRes(): Int = when (this) {
    CallBehavior.MUTE -> R.string.call_behavior_mute
    CallBehavior.DUCK -> R.string.call_behavior_duck
    CallBehavior.IGNORE -> R.string.call_behavior_ignore
}
