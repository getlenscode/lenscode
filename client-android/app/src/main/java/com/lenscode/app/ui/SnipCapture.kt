package com.lenscode.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * Flattens whatever's currently rendered across the workspace's spatial
 * panels into one screenshot. SceneCore renders SpatialPanel content
 * through its own spatial compositor rather than a normal Android Surface —
 * `adb shell screencap` was confirmed live to return a fixed
 * environment-mirror image regardless of app/spatial state (see
 * herdr-ipc-gotchas memory) — so per-panel GraphicsLayer capture of each
 * panel's own 2D Compose content is the only way to get real pixels out.
 *
 * The composite stacks capture groups top-to-bottom in [GROUP_ORDER] (agent
 * strip, primary panel, quick dock) — the same visual order they already
 * appear on screen — with items inside a group placed left-to-right. This
 * doesn't reproduce true 3D placement (Subspace exposes no 2D screen-space
 * position for a panel), it's an honest flattened montage, not a real
 * spatial screenshot.
 */
class SnipCaptureController {
    private val groups = linkedMapOf<String, LinkedHashMap<String, androidx.compose.ui.graphics.layer.GraphicsLayer>>()

    fun register(group: String, itemId: String, layer: androidx.compose.ui.graphics.layer.GraphicsLayer) {
        groups.getOrPut(group) { linkedMapOf() }[itemId] = layer
    }

    fun unregister(group: String, itemId: String) {
        groups[group]?.remove(itemId)
    }

    suspend fun captureComposite(): Bitmap? {
        val rows = GROUP_ORDER.mapNotNull { group ->
            val items = groups[group]?.values.orEmpty()
            val bitmaps = items.mapNotNull { layer ->
                runCatching {
                    // toImageBitmap() hands back a GPU-backed HARDWARE bitmap —
                    // confirmed live, drawing that straight onto the software
                    // Canvas below crashed with "Software rendering doesn't
                    // support hardware bitmaps". Copy to a software config first.
                    val bmp = layer.toImageBitmap().asAndroidBitmap()
                    if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                }.getOrNull()
            }
            if (bitmaps.isEmpty()) return@mapNotNull null
            val rowWidth = bitmaps.sumOf { it.width } + (bitmaps.size - 1) * GAP_PX
            val rowHeight = bitmaps.maxOf { it.height }
            val row = Bitmap.createBitmap(rowWidth, rowHeight, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(row)
            var x = 0f
            for (bmp in bitmaps) {
                canvas.drawBitmap(bmp, x, ((rowHeight - bmp.height) / 2).toFloat(), null)
                x += bmp.width + GAP_PX
            }
            row
        }
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.width }
        val height = rows.sumOf { it.height } + (rows.size - 1) * GAP_PX
        val composite = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(composite)
        canvas.drawColor(AndroidColor.parseColor("#14141A"))
        var y = 0f
        for (row in rows) {
            canvas.drawBitmap(row, ((width - row.width) / 2).toFloat(), y, null)
            y += row.height + GAP_PX
        }
        return composite
    }

    companion object {
        private const val GAP_PX = 24
        private val GROUP_ORDER = listOf("agents", "primary", "dock")
    }
}

@Composable
fun rememberSnipCaptureController(): SnipCaptureController = remember { SnipCaptureController() }

/** Marks one region (an agent chip, the primary panel, the quick dock) as part of [SnipCaptureController.captureComposite]. */
@Composable
fun SnipRegion(
    controller: SnipCaptureController,
    group: String,
    itemId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    DisposableEffect(controller, group, itemId) {
        controller.register(group, itemId, layer)
        onDispose { controller.unregister(group, itemId) }
    }
    Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        },
    ) {
        // Panels, the dock and the spatial agent chips are all SnipRegions, so this gives them
        // the theme's font (monospace in Terminal).
        ProvideLensFont(content)
    }
}
