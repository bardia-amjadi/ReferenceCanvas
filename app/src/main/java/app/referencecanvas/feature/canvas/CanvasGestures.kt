package app.referencecanvas.feature.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import app.referencecanvas.domain.canvas.CanvasCard
import app.referencecanvas.domain.canvas.CanvasOps
import app.referencecanvas.domain.canvas.CanvasViewport

/** Everything the gesture detector needs from the screen; all callbacks run on the main thread. */
internal class GestureHost(
    val viewport: MutableState<CanvasViewport>,
    val dragIds: MutableState<Set<String>>,
    /** Drag offset in world units (not screen pixels), applied to [dragIds] while a drag is active. */
    val dragDelta: MutableState<Offset>,
    val viewSize: () -> IntSize,
    val cards: () -> List<CanvasCard>,
    val idsToDrag: (CanvasCard) -> Set<String>,
    val onTap: (CanvasCard?) -> Unit,
    val onLongPress: (CanvasCard) -> Unit,
    val onMoveCommitted: (Set<String>, Double, Double) -> Unit,
) {
    var active: Boolean = false
}

private enum class Phase { Tap, LongPress, Move, Multi }

/**
 * Gesture priority, decided once per touch:
 * 1. Finger lifts before moving past touch slop -> tap (open, or toggle selection).
 * 2. Held still -> long press (select the card under the finger; it can then be dragged).
 * 3. Moves past slop -> drags the card under the finger, or pans the canvas on empty space.
 * 4. A second finger at any point -> pinch-zoom and two-finger pan, cancelling any card drag.
 * Single-finger events are consumed only once they become a drag or pan, never for a tap.
 */
internal suspend fun PointerInputScope.canvasGestures(host: GestureHost) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        host.active = true
        try {
            val slop = viewConfiguration.touchSlop
            val start = host.viewport.value
            val hit = CanvasOps.hitTest(
                host.cards(),
                start.toWorldX(down.position.x.toDouble()),
                start.toWorldY(down.position.y.toDouble()),
            )

            var phase: Phase? = null
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (phase == null) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    val tracked = event.changes.firstOrNull { it.id == down.id }
                    phase = when {
                        pressed == 0 -> Phase.Tap
                        pressed > 1 -> Phase.Multi
                        tracked != null && (tracked.position - down.position).getDistance() > slop -> Phase.Move
                        else -> null
                    }
                }
            }
            val resolved = phase ?: Phase.LongPress

            if (resolved == Phase.Tap) {
                host.onTap(hit)
                return@awaitEachGesture
            }
            if (resolved == Phase.LongPress && hit != null) host.onLongPress(hit)

            var dragging: Set<String>? =
                if (hit != null && (resolved == Phase.Move || resolved == Phase.LongPress)) host.idsToDrag(hit) else null
            if (dragging != null) host.dragIds.value = dragging

            var worldDelta = Offset.Zero
            var last = down.position
            var trackedId: PointerId = down.id
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size > 1) {
                    if (dragging != null) {
                        dragging = null
                        worldDelta = Offset.Zero
                        host.dragIds.value = emptySet()
                        host.dragDelta.value = Offset.Zero
                    }
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid.isSpecified) {
                        val pan = event.calculatePan()
                        val size = host.viewSize()
                        host.viewport.value = host.viewport.value
                            .zoomedAround(centroid.x.toDouble(), centroid.y.toDouble(), event.calculateZoom().toDouble())
                            .panBy(pan.x.toDouble(), pan.y.toDouble())
                            .coerced(size.width.toDouble(), size.height.toDouble())
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    trackedId = pressed.first().id
                    last = pressed.first().position
                } else if (pressed.size == 1) {
                    val change = pressed.first()
                    if (change.id != trackedId) {
                        // The remaining finger after a pinch: restart tracking so the view doesn't jump.
                        trackedId = change.id
                        last = change.position
                    }
                    val delta = change.position - last
                    last = change.position
                    if (delta != Offset.Zero) {
                        val vp = host.viewport.value
                        if (dragging != null) {
                            worldDelta += delta / vp.zoom
                            host.dragDelta.value = worldDelta
                        } else {
                            val size = host.viewSize()
                            host.viewport.value = vp
                                .panBy(delta.x.toDouble(), delta.y.toDouble())
                                .coerced(size.width.toDouble(), size.height.toDouble())
                        }
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })

            val ids = dragging
            if (ids != null && worldDelta != Offset.Zero) {
                host.onMoveCommitted(ids, worldDelta.x.toDouble(), worldDelta.y.toDouble())
            }
        } finally {
            host.active = false
        }
    }
}
