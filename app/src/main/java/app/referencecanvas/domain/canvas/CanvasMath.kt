package app.referencecanvas.domain.canvas

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** Practical zoom range: wide enough to see very large boards, narrow enough to stay numerically stable. */
const val MIN_ZOOM = 0.002f
const val MAX_ZOOM = 16f

/** Cards and the viewport centre are kept within +/- this many world units (Doubles stay exact far beyond it). */
const val MAX_WORLD = 10_000_000.0

/** World-unit spacing of the snap grid. */
const val GRID_STEP = 40.0

data class WorldRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun intersects(o: WorldRect): Boolean = left < o.right && right > o.left && top < o.bottom && bottom > o.top
    fun contains(x: Double, y: Double): Boolean = x >= left && x <= right && y >= top && y <= bottom
    fun union(o: WorldRect) = WorldRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

/**
 * Maps between logical world coordinates (independent of screen size and zoom) and screen pixels:
 * `screen = world * zoom + offset`. World coordinates and offsets are Doubles so precision holds far from the origin.
 */
data class CanvasViewport(
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val zoom: Float = 1f,
) {
    fun toScreenX(worldX: Double): Double = worldX * zoom + offsetX
    fun toScreenY(worldY: Double): Double = worldY * zoom + offsetY
    fun toWorldX(screenX: Double): Double = (screenX - offsetX) / zoom
    fun toWorldY(screenY: Double): Double = (screenY - offsetY) / zoom

    fun panBy(dx: Double, dy: Double) = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    fun zoomedAround(focalX: Double, focalY: Double, factor: Double): CanvasViewport =
        zoomedTo(zoom * factor, focalX, focalY)

    /** Sets the zoom (clamped) while keeping the world point under the focal screen point fixed. */
    fun zoomedTo(target: Double, focalX: Double, focalY: Double): CanvasViewport {
        if (!target.isFinite()) return this
        val z = target.toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)
        val wx = toWorldX(focalX)
        val wy = toWorldY(focalY)
        return CanvasViewport(focalX - wx * z, focalY - wy * z, z)
    }

    /** The world area visible on a [viewW] x [viewH] screen, grown by [margin] screen pixels on every side. */
    fun visibleRect(viewW: Double, viewH: Double, margin: Double = 0.0) = WorldRect(
        toWorldX(-margin), toWorldY(-margin), toWorldX(viewW + margin), toWorldY(viewH + margin),
    )

    /** Repairs invalid values and keeps the view centre within [MAX_WORLD]. */
    fun coerced(viewW: Double, viewH: Double): CanvasViewport {
        if (!offsetX.isFinite() || !offsetY.isFinite() || !zoom.isFinite() || zoom <= 0f) return CanvasViewport()
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val cx = ((viewW / 2 - offsetX) / z).coerceIn(-MAX_WORLD, MAX_WORLD)
        val cy = ((viewH / 2 - offsetY) / z).coerceIn(-MAX_WORLD, MAX_WORLD)
        return CanvasViewport(viewW / 2 - cx * z, viewH / 2 - cy * z, z)
    }
}

/** A viewport that shows all of [bounds], never zooming in past [maxZoom]. */
fun fitViewport(
    bounds: WorldRect,
    viewW: Double,
    viewH: Double,
    padding: Double = 48.0,
    maxZoom: Float = 1f,
): CanvasViewport {
    val availW = max(1.0, viewW - 2 * padding)
    val availH = max(1.0, viewH - 2 * padding)
    val z = min(availW / max(bounds.width, 1.0), availH / max(bounds.height, 1.0))
        .toFloat().coerceIn(MIN_ZOOM, maxZoom)
    val cx = (bounds.left + bounds.right) / 2
    val cy = (bounds.top + bounds.bottom) / 2
    return CanvasViewport(viewW / 2 - cx * z, viewH / 2 - cy * z, z)
}

/** Lightweight card data the canvas needs: no file bytes, no unbounded text. */
data class CanvasCard(
    val id: String,
    val title: String,
    val mimeType: String,
    val relativePath: String?,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val z: Int,
    val isFavorite: Boolean,
) {
    val rect: WorldRect get() = WorldRect(x, y, x + w, y + h)
    val isPreviewableImage: Boolean get() = mimeType.startsWith("image/") && !mimeType.contains("svg")
}

data class CardLayout(val id: String, val x: Double, val y: Double, val z: Int)

enum class AlignMode { Left, CenterX, Right, Top, CenterY, Bottom }

object CanvasOps {
    fun clampWorld(v: Double): Double = v.coerceIn(-MAX_WORLD, MAX_WORLD)

    fun snap(v: Double, step: Double = GRID_STEP): Double = round(v / step) * step

    fun bounds(cards: Collection<CanvasCard>): WorldRect? {
        var result: WorldRect? = null
        for (c in cards) result = result?.union(c.rect) ?: c.rect
        return result
    }

    fun visible(cards: List<CanvasCard>, area: WorldRect): List<CanvasCard> = cards.filter { it.rect.intersects(area) }

    /** The top-most card (highest z, then latest in list) containing the world point, or null. */
    fun hitTest(cards: List<CanvasCard>, wx: Double, wy: Double): CanvasCard? {
        var best: CanvasCard? = null
        for (c in cards) {
            if (c.rect.contains(wx, wy) && (best == null || c.z >= best.z)) best = c
        }
        return best
    }

    fun align(cards: List<CanvasCard>, mode: AlignMode): List<CardLayout> {
        val b = bounds(cards) ?: return emptyList()
        return cards.map { c ->
            val x = when (mode) {
                AlignMode.Left -> b.left
                AlignMode.CenterX -> (b.left + b.right) / 2 - c.w / 2
                AlignMode.Right -> b.right - c.w
                else -> c.x
            }
            val y = when (mode) {
                AlignMode.Top -> b.top
                AlignMode.CenterY -> (b.top + b.bottom) / 2 - c.h / 2
                AlignMode.Bottom -> b.bottom - c.h
                else -> c.y
            }
            CardLayout(c.id, clampWorld(x), clampWorld(y), c.z)
        }
    }

    /** Re-flows cards into a near-square grid anchored at their current top-left, in reading order. Never overlaps. */
    fun gridArrange(cards: List<CanvasCard>, gap: Double = 24.0): List<CardLayout> {
        if (cards.isEmpty()) return emptyList()
        val b = bounds(cards)!!
        val ordered = cards.sortedWith(compareBy({ it.y }, { it.x }))
        val columns = max(1, ceil(sqrt(ordered.size.toDouble())).toInt())
        val cellW = ordered.maxOf { it.w } + gap
        val cellH = ordered.maxOf { it.h } + gap
        return ordered.mapIndexed { i, c ->
            CardLayout(c.id, clampWorld(b.left + (i % columns) * cellW), clampWorld(b.top + (i / columns) * cellH), c.z)
        }
    }

    fun move(cards: List<CanvasCard>, dx: Double, dy: Double): List<CardLayout> =
        cards.map { CardLayout(it.id, clampWorld(it.x + dx), clampWorld(it.y + dy), it.z) }

    /** Puts [ids] above everything else, keeping their relative order. */
    fun bringToFront(all: List<CanvasCard>, ids: Set<String>): List<CardLayout> {
        val top = all.maxOfOrNull { it.z } ?: return emptyList()
        return all.filter { it.id in ids }.sortedBy { it.z }
            .mapIndexed { i, c -> CardLayout(c.id, c.x, c.y, top + 1 + i) }
    }

    /** Puts [ids] below everything else, keeping their relative order. */
    fun sendToBack(all: List<CanvasCard>, ids: Set<String>): List<CardLayout> {
        val bottom = all.minOfOrNull { it.z } ?: return emptyList()
        val chosen = all.filter { it.id in ids }.sortedBy { it.z }
        return chosen.mapIndexed { i, c -> CardLayout(c.id, c.x, c.y, bottom - chosen.size + i) }
    }
}
