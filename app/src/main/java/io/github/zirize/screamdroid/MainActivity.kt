package io.github.zirize.screamdroid

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zirize.screamdroid.net.LocalAddress
import io.github.zirize.screamdroid.service.EventLog
import io.github.zirize.screamdroid.service.PowerExemption
import io.github.zirize.screamdroid.service.ReceiverService
import io.github.zirize.screamdroid.service.ScreamdroidTileService
import io.github.zirize.screamdroid.settings.Settings
import io.github.zirize.screamdroid.ui.CallScreen
import io.github.zirize.screamdroid.ui.DiagnosticsScreen
import io.github.zirize.screamdroid.ui.GuideScreen
import io.github.zirize.screamdroid.ui.MainScreen
import io.github.zirize.screamdroid.ui.ScreamdroidTheme
import io.github.zirize.screamdroid.ui.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 🔑 **The screen does not own anything.** It asks the service to start or stop, and it watches
 *    what the service publishes. That is what lets audio survive rotation, the back button and the
 *    launcher - the activity is now just one of three things looking at the same snapshot, beside
 *    the notification and the quick settings tile.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ScreamdroidTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ReceiverRoute()
                }
            }
        }
    }
}

/**
 * 🔑 **Five screens and no navigation library.** They form a star, not a graph: four leaves, all
 *    reached from the main screen and all leading back to it. A back stack would be a dependency
 *    and a lifecycle to get wrong in exchange for nothing this shape needs.
 */
private enum class Screen { MAIN, CALL, SETTINGS, DIAGNOSTICS, GUIDE }

@Composable
private fun ReceiverRoute() {
    val context = LocalContext.current
    val snapshot by ReceiverService.snapshot.collectAsStateWithLifecycle()
    val history by ReceiverService.history.collectAsStateWithLifecycle()
    val stopReason by ReceiverService.stopReason.collectAsStateWithLifecycle()
    val events by EventLog.entries.collectAsStateWithLifecycle()

    // 🔑 The screen reads the stored settings directly rather than through the service, so they
    //    are editable while nothing is running - which is exactly when somebody changes a port.
    val repository = remember(context) { ReceiverService.settingsRepository(context) }
    val stored by repository.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val scope = rememberCoroutineScope()

    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var tileHelp by remember { mutableStateOf(false) }

    // 🔑 **Read again on every resume, because it is granted somewhere else.** The button below
    //    leads out to the system settings, and the only way back is through this screen resuming -
    //    there is no callback and nothing to observe. See service/PowerExemption.kt.
    var batteryExempt by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryExempt = PowerExemption.isExempt(context)
    }

    // 🔴 **Decided once, from the stored value, before anything is drawn.** `stored` starts at
    //    the defaults while DataStore is still reading, and "not read yet" and "never seen the
    //    guide" are the same value there - branching on it directly would flash the guide past
    //    on every launch. Null means the answer has not arrived; nothing is drawn until it has.
    var showGuide by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { showGuide = !repository.settings.first().guideSeen }
    val firstRun = showGuide == true && screen == Screen.MAIN

    // 🔑 The guide is left by finishing it, and finishing it is what records that it was seen -
    //    including when it was opened again from the settings, where marking it again costs
    //    nothing and keeps one exit rather than two.
    val leaveGuide = {
        scope.launch { repository.setGuideSeen(true) }
        showGuide = false
        screen = Screen.MAIN
    }

    // 🔑 Asked for once, on the way in, and never insisted on: refusing it costs the notification,
    //    not the audio. Below Android 13 there is nothing to ask.
    val askForNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
    // 🔑 Back out of the first run as well: a guide nobody can leave is a wall, and leaving it
    //    is the same act as finishing it.
    BackHandler(enabled = firstRun) { leaveGuide() }

    // 🔑 One scroller around all four: the settings and diagnostics screens are taller than a
    //    phone by design (the mockups are drawn 1180 and 900 tall), and the main screen has to
    //    scroll too once the system font is set large.
    val scroll = rememberScrollState()
    // 🔴 **One scroller shared by four screens keeps its offset across a change of screen.**
    //    The first run showed it worst: the guide is taller than the phone, so tapping Start at
    //    the bottom of it opened the main screen already scrolled - no title, no toggle, on the
    //    very first thing a new user sees. Every screen starts at its own top instead.
    LaunchedEffect(screen, firstRun) { scroll.scrollTo(0) }
    val page = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(scroll)

    Box(page) {
        when {
            // 🔑 Nothing at all until the stored answer arrives - a frame of the main screen
            //    before the guide would be the flash this is written to avoid.
            showGuide == null -> Unit
            firstRun || screen == Screen.GUIDE -> GuideScreen(
                firstRun = firstRun,
                address = LocalAddress.ipv4()?.let { "$it:${stored.unicastPort}" },
                batteryExempt = batteryExempt,
                onFixBattery = { PowerExemption.open(context) },
                onAddTile = { if (!requestAddTile(context)) tileHelp = true },
                onDone = { leaveGuide() },
            )
            else -> when (screen) {
                Screen.MAIN -> MainScreen(
                    snapshot = snapshot,
                    stopReason = stopReason,
                    address = LocalAddress.ipv4(),
                    batteryRestricted = !batteryExempt,
                    onToggle = { ReceiverService.toggle(context) },
                    onVolumeChange = { scope.launch { repository.setVolume(it) } },
                    onFixBattery = { PowerExemption.open(context) },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
                    onOpenCall = { screen = Screen.CALL },
                )

                Screen.CALL -> CallScreen(snapshot) { screen = Screen.MAIN }

                Screen.SETTINGS -> SettingsScreen(
                    settings = stored,
                    snapshot = snapshot,
                    onBack = { screen = Screen.MAIN },
                    onUnicastPortChange = { scope.launch { repository.setUnicastPort(it) } },
                    onMulticastPortChange = { scope.launch { repository.setMulticastPort(it) } },
                    onUnicastEnabledChange = { scope.launch { repository.setUnicastEnabled(it) } },
                    onGroupChange = { scope.launch { repository.setMulticastGroup(it) } },
                    onBufferPolicyChange = { scope.launch { repository.setBufferPolicy(it) } },
                    onCallBehaviorChange = { scope.launch { repository.setCallBehavior(it) } },
                    onDuckChange = { scope.launch { repository.setDuckOnNotification(it) } },
                    onShareChange = { scope.launch { repository.setShareWithOthers(it) } },
                    onSaveBatteryChange = { scope.launch { repository.setSaveBatteryWhenSilent(it) } },
                    onLowLatencyWifiChange = { scope.launch { repository.setLowLatencyWifi(it) } },
                    onAllowMobileDataChange = { scope.launch { repository.setAllowMobileData(it) } },
                    onAddTile = { if (!requestAddTile(context)) tileHelp = true },
                    onShowGuide = { screen = Screen.GUIDE },
                )

                Screen.DIAGNOSTICS -> DiagnosticsScreen(
                    snapshot = snapshot,
                    history = history,
                    events = events,
                    batteryExempt = batteryExempt,
                    onBack = { screen = Screen.MAIN },
                    onResetStats = { ReceiverService.resetStats(context) },
                )

                Screen.GUIDE -> Unit   // handled above
            }
        }
    }

    if (tileHelp) {
        AlertDialog(
            onDismissRequest = { tileHelp = false },
            confirmButton = {
                TextButton(onClick = { tileHelp = false }) { Text(stringResource(R.string.action_close)) }
            },
            title = { Text(stringResource(R.string.settings_add_tile)) },
            text = { Text(stringResource(R.string.settings_add_tile_manually)) },
        )
    }
}

/**
 * 🔑 **Android 13 is where a tile can ask to be added.** Before that there is no API at all and
 *    the panel has to be edited by hand, so the only honest thing to do is say how - which is
 *    what the caller falls back to when this returns false.
 */
private fun requestAddTile(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val manager = context.getSystemService(StatusBarManager::class.java) ?: return false
    return runCatching {
        manager.requestAddTileService(
            ComponentName(context, ScreamdroidTileService::class.java),
            context.getString(R.string.app_name),
            Icon.createWithResource(context, R.drawable.ic_notification),
            context.mainExecutor,
        ) { }
    }.isSuccess
}
