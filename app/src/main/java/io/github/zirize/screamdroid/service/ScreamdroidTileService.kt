package io.github.zirize.screamdroid.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.zirize.screamdroid.R
import io.github.zirize.screamdroid.net.ScreamReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * One press, from anywhere.
 *
 * 🔴 **This tile exists because starting on boot is not allowed.** From target SDK 35 a
 *    `mediaPlayback` foreground service cannot be started from `BOOT_COMPLETED`, so there is no
 *    "start automatically" to offer. Something has to take its place that is reachable without
 *    hunting for the app, and the quick settings panel is where a person already goes to turn
 *    things on.
 */
class ScreamdroidTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        // The tile is only visible while the panel is open, so this collector lives exactly that
        // long - it is the cheapest way to have the tile follow the receiver rather than poll it.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { ReceiverService.snapshot.collectLatest { render(it) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        ReceiverService.toggle(applicationContext)
    }

    private fun render(snapshot: ReceiverSnapshot) {
        val tile = qsTile ?: return
        tile.state = if (snapshot.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (snapshot.running) R.drawable.ic_notification else R.drawable.ic_tile_off,
        )
        tile.label = getString(R.string.app_name)
        tile.contentDescription = tile.label
        // 🔑 The subtitle says what is true, not what was asked for: "playing" while nothing is
        //    arriving would be the tile telling a small lie every time the PC goes quiet.
        tile.subtitle = getString(
            when {
                !snapshot.running -> R.string.tile_off
                snapshot.blocked != Blocked.NONE -> R.string.state_blocked
                snapshot.paused -> R.string.notification_paused
                snapshot.state == ScreamReceiver.State.PLAYING -> R.string.state_playing
                snapshot.state == ScreamReceiver.State.ERROR -> R.string.state_error
                else -> R.string.state_waiting
            }
        )
        tile.updateTile()
    }
}
