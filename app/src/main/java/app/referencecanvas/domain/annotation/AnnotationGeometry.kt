package app.referencecanvas.domain.annotation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The image area inside a view: where the (aspect-fitted) picture actually sits. All values in view pixels. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun toNormalized(px: Float, py: Float) =
        Pt(((px - left) / width).coerceIn(0f, 1f), ((py - top) / height).coerceIn(0f, 1f))
}

object AnnotationGeometry {
    /** Largest centred rectangle of aspect ratio [aspect] (width / height) that fits a [viewW] x [viewH] view. */
    fun fit(viewW: Float, viewH: Float, aspect: Float): FitRect {
        if (viewW <= 0f || viewH <= 0f || aspect <= 0f || !aspect.isFinite()) return FitRect(0f, 0f, max(viewW, 1f), max(viewH, 1f))
        val w: Float
        val h: Float
        if (viewW / viewH > aspect) {
            h = viewH; w = h * aspect
        } else {
            w = viewW; h = w / aspect
        }
        return FitRect((viewW - w) / 2f, (viewH - h) / 2f, w, h)
    }

    /** Ramer–Douglas–Peucker: drops pen points that barely change the shape. [epsilon] is in normalized units. */
    fun simplify(points: List<Pt>, epsilon: Float = 0.0008f): List<Pt> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.lastIndex))
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var worst = -1f
            var index = -1
            for (i in start + 1 until end) {
                val d = distanceToSegment(points[i].x, points[i].y, points[start].x, points[start].y, points[end].x, points[end].y)
                if (d > worst) { worst = d; index = i }
            }
            if (worst > epsilon && index != -1) {
                keep[index] = true
                stack.addLast(intArrayOf(start, index))
                stack.addLast(intArrayOf(index, end))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Outline polylines in pixel space for a [w] x [h] image: used for hit-testing and bounds. */
    fun outlines(a: AnnotationData, w: Float, h: Float): List<List<Pair<Float, Float>>> {
        fun px(p: Pt) = p.x * w to p.y * h
        return when (a.kind) {
            AnnotationKind.Pen, AnnotationKind.Highlighter ->
                listOf(if (a.points.size == 1) listOf(px(a.points[0]), px(a.points[0])) else a.points.map(::px))
            AnnotationKind.Line, AnnotationKind.Arrow -> listOf(a.points.map(::px))
            AnnotationKind.Rect -> {
                val (x1, y1) = px(a.points[0]); val (x2, y2) = px(a.points[1])
                listOf(listOf(x1 to y1, x2 to y1, x2 to y2, x1 to y2, x1 to y1))
            }
            AnnotationKind.Ellipse -> {
                val (x1, y1) = px(a.points[0]); val (x2, y2) = px(a.points[1])
                val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
                val rx = kotlin.math.abs(x2 - x1) / 2f; val ry = kotlin.math.abs(y2 - y1) / 2f
                listOf((0..ELLIPSE_SEGMENTS).map { i ->
                    val t = 2.0 * PI * i / ELLIPSE_SEGMENTS
                    (cx + rx * cos(t)).toFloat() to (cy + ry * sin(t)).toFloat()
                })
            }
            AnnotationKind.Text -> {
                val box = textBox(a, w, h)
                listOf(listOf(box[0] to box[1], box[2] to box[1], box[2] to box[3], box[0] to box[3], box[0] to box[1]))
            }
        }
    }

    /** Approximate text bounds [left, top, right, bottom] in pixels (no font metrics needed). */
    fun textBox(a: AnnotationData, w: Float, h: Float): FloatArray {
        val size = a.textSize * h
        val lines = (a.text ?: "").split('\n')
        val widest = lines.maxOfOrNull { it.length } ?: 0
        val left = a.points[0].x * w
        val top = a.points[0].y * h
        return floatArrayOf(left, top, left + widest * size * TEXT_WIDTH_FACTOR, top + lines.size * size * TEXT_LINE_FACTOR)
    }

    /** True if the pixel point ([px], [py]) is on (or within [tolerance] px of) the annotation. */
    fun hit(a: AnnotationData, px: Float, py: Float, w: Float, h: Float, tolerance: Float): Boolean {
        val reach = tolerance + a.width * w / 2f
        if (a.kind == AnnotationKind.Text) {
            val b = textBox(a, w, h)
            return px >= b[0] - tolerance && px <= b[2] + tolerance && py >= b[1] - tolerance && py <= b[3] + tolerance
        }
        return outlines(a, w, h).any { line ->
            line.zipWithNext().any { (p, q) -> distanceToSegment(px, py, p.first, p.second, q.first, q.second) <= reach }
        }
    }

    /** Bounding box [left, top, right, bottom] in normalized units. */
    fun bounds(a: AnnotationData, w: Float, h: Float): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (line in outlines(a, w, h)) for ((x, y) in line) {
            l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y)
        }
        val pad = a.width * w / 2f
        return floatArrayOf((l - pad) / w, (t - pad) / h, (r + pad) / w, (b + pad) / h)
    }

    fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / lengthSq).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    /** Angle (radians) of the vector from [from] to [to]. */
    fun angle(from: Pair<Float, Float>, to: Pair<Float, Float>): Double =
        atan2((to.second - from.second).toDouble(), (to.first - from.first).toDouble())

    private const val ELLIPSE_SEGMENTS = 48
    const val TEXT_WIDTH_FACTOR = 0.56f
    const val TEXT_LINE_FACTOR = 1.25f
}
