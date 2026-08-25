package com.freeturn.app.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.freeturn.app.R
import com.freeturn.app.domain.proxy.ProxyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ProxyTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var running = false

    override fun onStartListening() {
        super.onStartListening()
        // Система пару start/stop соблюдает, но лишний listening-цикл оставил бы
        // второй collector на том же тайле.
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()).also { s ->
            ProxyStore.status
                .map { it.busy }
                .distinctUntilChanged()
                .onEach { busy ->
                    running = busy
                    render()
                }
                .launchIn(s)
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        super.onClick()
        if (running) {
            sendBroadcast(Intent(this, ProxyReceiver::class.java).setAction(ProxyActions.STOP))
            return
        }
        // START - через activity-трамплин: согласие на VPN спрашивается только оттуда.
        val intent = ProxyShortcutActivity.startIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    /** PendingIntent-перегрузка появилась только в API 34, ниже другого способа свернуть шторку нет. */
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) = startActivityAndCollapse(intent)

    private fun render() {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_service_label)
        tile.contentDescription = tile.label
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile_nearby)
        tile.updateTile()
    }
}
