package com.lenscode.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.xr.compose.platform.requestFullSpace
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.scenecore.scene
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // The ViewModel (not this Activity) owns the bridge connection — it
    // survives Activity recreation, so a config change can't drop the socket.
    private val viewModel: WorkspaceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // All UI lives inside Subspace, which SceneCore renders through its own
        // spatial compositor rather than the Activity's normal window content
        // view — so the system's default splash icon never gets a "first
        // frame drawn" signal to dismiss itself, and sits on screen forever.
        // Dismiss it immediately; there is no real loading gate to wait on.
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { false }

        super.onCreate(savedInstanceState)

        // The Activity's own 2D window is separate from Subspace content and
        // is opaque black by default — without this it renders as a solid
        // black panel with the launcher icon behind the spatial panels.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(AndroidColor.TRANSPARENT))

        setContent {
            LensCodeWorkspace(
                viewModel.bridge,
                // Confirmed live: entering PiP does change the Activity's own
                // window (dumpsys shows mIsInPictureInPictureMode=true), but
                // has no visible effect — every visible panel renders through
                // SceneCore's spatial compositor, not this transparent 2D
                // Activity window, so shrinking it changes nothing on screen.
                onEnterPip = {
                    enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
                },
            )
        }

        // The manifest's FULL_SPACE_MANAGED start mode only sets the default
        // launch mode — actually entering full space (so SpatialPanel content
        // attaches to a real scene node instead of the system Home
        // environment staying HOME_VISIBLE) requires this explicit call.
        lifecycleScope.launch { requestFullSpace() }
    }

    // No onDestroy cleanup — WorkspaceViewModel.onCleared closes the bridge.
}
