package app.referencecanvas.feature.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import java.io.File

private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Pinch-to-zoom, pan while zoomed, double-tap zoom. At 1x a single finger is left unconsumed
 * so the surrounding pager can swipe between references.
 */
@Composable
fun ZoomableImage(
    file: File,
    contentDescription: String,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable (aspect: Float) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var aspect by remember { mutableFloatStateOf(0f) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(scale > 1f) { onZoomed(scale > 1f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if ((zoom != 1f || pan != Offset.Zero) && centroid.isSpecified) {
                                val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                val c = centroid - Offset(box.width / 2f, box.height / 2f)
                                // Keep the point under the fingers fixed while scaling, then apply the pan.
                                val moved = c - (c - offset) * (newScale / scale) + pan
                                offset = if (newScale <= 1f) Offset.Zero else clamp(moved, newScale)
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // Image and overlay share one transformed layer, so annotations stay glued to the picture while zooming.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            AsyncImage(
                model = file,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                onError = { onError() },
                onSuccess = { state ->
                    // The displayed (EXIF-corrected) aspect ratio is what annotations are normalized against.
                    val d = state.result.drawable
                    if (d.intrinsicHeight > 0) aspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (aspect > 0f) overlay(aspect)
        }
    }
}
