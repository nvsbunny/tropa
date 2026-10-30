package app.tropa.core

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.tropa.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Плитка в шторке: включает и выключает VPN, не открывая приложение. */
class TropaTileService : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch {
            CoreState.status.collect { render(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
    }

    override fun onClick() {
        if (CoreState.isOn) {
            TropaVpnService.stop(this)
            return
        }
        // Разрешение на VPN спрашивается только из приложения.
        if (TropaVpnService.needsPermission(this)) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        TropaVpnService.start(this)
    }

    private fun render(s: CoreState.Status) {
        val tile = qsTile ?: return
        tile.state = when (s) {
            is CoreState.Status.On, CoreState.Status.Connecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (s) {
                is CoreState.Status.On -> "Подключено"
                CoreState.Status.Connecting -> "Подключаемся"
                is CoreState.Status.Error -> "Ошибка"
                CoreState.Status.Off -> "Выключено"
            }
        }
        tile.updateTile()
    }
}
