package com.lightclipboardsync.android

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class SyncTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        ManualSync.start(this)
    }
}
