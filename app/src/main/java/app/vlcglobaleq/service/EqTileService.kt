package app.vlcglobaleq.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.vlcglobaleq.data.PresetStore
import app.vlcglobaleq.ui.MainActivity

class EqTileService : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        val enable = !PresetStore.get(this).enabled
        // If the system refuses a background start, open the app so the user can enable it there.
        if (!EqController.setEnabled(this, enable)) openApp()
        render()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // the Intent overload is only used below API 34
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val store = PresetStore.get(this)
        tile.state = if (store.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (store.enabled) store.active.name else "Off"
        tile.updateTile()
    }
}
