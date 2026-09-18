package dev.aura.xr.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Snip-tool crop UI over a captured workspace screenshot (spec follow-up:
 * XR snip tool). Two independent drag handles — top-left and bottom-right —
 * each with their own [pointerInput] gesture scope, so Android XR's
 * per-hand pinch-and-drag can move both corners at once (one hand per
 * handle), the same mechanism that already lets this app's panel
 * `movable`/`resizable` modifiers respond to a pinch. A single hand
 * dragging either handle alone works identically, just one corner at a time.
 *
 * [onConfirm] receives the crop rect in the source [bitmap]'s own pixel
 * space, already converted back out of the on-screen display scale.
 */
@Composable
fun SnipCropOverlay(
    bitmap: Bitmap,
    uploading: Boolean,
    onConfirm: (Rect) -> Unit,
    onCancel: () -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxWpx = with(density) { (maxWidth * 0.9f).toPx() }
            val maxHpx = with(density) { (maxHeight * 0.78f).toPx() }
            val scale = minOf(maxWpx / bmpW, maxHpx / bmpH)
            val dispW = bmpW * scale
            val dispH = bmpH * scale
            val dispWDp = with(density) { dispW.toDp() }
            val dispHDp = with(density) { dispH.toDp() }

            // Crop corners in displayed (already-scaled) px, top-left origin,
            // reset whenever a new capture comes in.
            var topLeft by remember(bitmap) { mutableStateOf(Offset.Zero) }
            var bottomRight by remember(bitmap) { mutableStateOf(Offset(dispW, dispH)) }

            Box(Modifier.align(Alignment.Center)) {
                Box(Modifier.size(dispWDp, dispHDp)) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Captured workspace",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val rect = Rect(topLeft, bottomRight)
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, rect.top))
                        drawRect(dim, topLeft = Offset(0f, rect.bottom), size = Size(size.width, size.height - rect.bottom))
                        drawRect(dim, topLeft = Offset(0f, rect.top), size = Size(rect.left, rect.height))
                        drawRect(dim, topLeft = Offset(rect.right, rect.top), size = Size(size.width - rect.right, rect.height))
                        drawRect(Color.White, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 3f))
                    }
                    SnipHandle(
                        offset = topLeft,
                        onDrag = { delta ->
                            val next = topLeft + delta
                            topLeft = Offset(
                                next.x.coerceIn(0f, bottomRight.x - MIN_CROP_PX),
                                next.y.coerceIn(0f, bottomRight.y - MIN_CROP_PX),
                            )
                        },
                    )
                    SnipHandle(
                        offset = bottomRight,
                        onDrag = { delta ->
                            val next = bottomRight + delta
                            bottomRight = Offset(
                                next.x.coerceIn(topLeft.x + MIN_CROP_PX, dispW),
                                next.y.coerceIn(topLeft.y + MIN_CROP_PX, dispH),
                            )
                        },
                    )
                }
            }

            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(onClick = onCancel, enabled = !uploading) { Text("Cancel") }
                Button(
                    onClick = {
                        onConfirm(
                            Rect(
                                topLeft.x / scale,
                                topLeft.y / scale,
                                bottomRight.x / scale,
                                bottomRight.y / scale,
                            ),
                        )
                    },
                    enabled = !uploading,
                ) { Text(if (uploading) "Sending…" else "Send to agent") }
            }
        }
    }
}

private const val MIN_CROP_PX = 60f

@Composable
private fun SnipHandle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSizeDp = 28.dp
    val handlePx = with(density) { handleSizeDp.toPx() }
    val offsetXDp = with(density) { (offset.x - handlePx / 2).toDp() }
    val offsetYDp = with(density) { (offset.y - handlePx / 2).toDp() }
    Box(
        Modifier
            .offset(offsetXDp, offsetYDp)
            .size(handleSizeDp)
            .clip(CircleShape)
            .background(Color(0xFF4FC3F7))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
    )
}
