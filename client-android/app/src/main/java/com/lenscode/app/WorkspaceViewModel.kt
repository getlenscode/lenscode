package com.lenscode.app

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lenscode.app.net.BridgeClient
import com.lenscode.app.net.PairingStore

/**
 * Owns the bridge connection for the workspace UI. Living in a ViewModel (with
 * viewModelScope) means the WebSocket and all collected panel state survive
 * Activity recreation — only a real process death or explicit finish tears it
 * down, via [onCleared].
 */
class WorkspaceViewModel(app: Application) : AndroidViewModel(app) {

    private val deviceId = Settings.Secure.getString(
        app.contentResolver,
        Settings.Secure.ANDROID_ID,
    ) ?: "android-xr-dev"

    // BuildConfig.BRIDGE_URL defaults to 10.0.2.2 (emulator host-loopback
    // alias); pass -PbridgeUrl=ws://<lan-ip>:8137 to gradle for a real device.
    val bridge = BridgeClient(
        viewModelScope,
        BuildConfig.BRIDGE_URL,
        deviceId,
        PairingStore(app.applicationContext),
    )

    init {
        bridge.connect()
    }

    override fun onCleared() {
        bridge.close()
    }
}
