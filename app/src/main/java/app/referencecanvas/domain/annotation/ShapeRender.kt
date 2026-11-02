package app.referencecanvas.domain.annotation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Backend-independent drawing instructions, in pixels. Both the on-screen overlay and the exporter draw these. */
sealed interface Primitive {
    /** Open or closed polyline, round caps and joins. A two-identical-point line draws a dot. */
    data class Poly(val points: List<Pair<Float, Float>>, val widthPx: Float, val color: Int, val closed: Boolean = false) : Primitive
    data class Oval(val left: Float, val top: Float, val right: Float, val bottom: Float, val widthPx: Float, val color: Int) : Primitive
    data class TextRun(val text: String, val x: Float, val y: Float, val sizePx: Float, val color: Int) : Primitive
}

object ShapeRender {
    private const val HIGHLIGHTER_ALPHA = 0x59 // ~35%
    private const val HIGHLIGHTER_WIDTH_FACTOR = 2.6f
    private val ARROW_HEAD_ANGLE = 28.0 * PI / 180.0

    /** Converts [a] into pixel-space primitives for a [w] x [h] image. */
    fun toPrimitives(a: AnnotationData, w: Float, h: Float): List<Primitive> {
        val widthPx = max(a.width * w, 1f)
        fun px(p: Pt) = p.x * w to p.y * h
        return when (a.kind) {
            AnnotationKind.Pen -> {
                val pts = a.points.map(::px)
                listOf(Primitive.Poly(if (pts.size == 1) listOf(pts[0], pts[0]) else pts, widthPx, a.color))
            }
            AnnotationKind.Highlighter -> {
                val pts = a.points.map(::px)
                val color = (a.color and 0x00FFFFFF) or (HIGHLIGHTER_ALPHA shl 24)
                listOf(Primitive.Poly(if (pts.size == 1) listOf(pts[0], pts[0]) else pts, widthPx * HIGHLIGHTER_WIDTH_FACTOR, color))
            }
            AnnotationKind.Line -> listOf(Primitive.Poly(a.points.map(::px), widthPx, a.color))
            AnnotationKind.Arrow -> arrow(px(a.points[0]), px(a.points[1]), widthPx, a.color, min(w, h))
            AnnotationKind.Rect -> {
                val (x1, y1) = px(a.points[0]); val (x2, y2) = px(a.points[1])
                listOf(Primitive.Poly(listOf(x1 to y1, x2 to y1, x2 to y2, x1 to y2), widthPx, a.color, closed = true))
            }
            AnnotationKind.Ellipse -> {
                val (x1, y1) = px(a.points[0]); val (x2, y2) = px(a.points[1])
                listOf(Primitive.Oval(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), widthPx, a.color))
            }
            AnnotationKind.Text -> {
                val (x, y) = px(a.points[0])
                listOf(Primitive.TextRun(a.text.orEmpty(), x, y, a.textSize * h, a.color))
            }
        }
    }

    private fun arrow(from: Pair<Float, Float>, to: Pair<Float, Float>, widthPx: Float, color: Int, minSide: Float): List<Primitive> {
        val length = max(widthPx * 4f, minSide * 0.03f)
        val back = AnnotationGeometry.angle(from, to) + PI
        fun head(delta: Double) = (to.first + length * cos(back + delta)).toFloat() to (to.second + length * sin(back + delta)).toFloat()
        return listOf(
            Primitive.Poly(listOf(from, to), widthPx, color),
            Primitive.Poly(listOf(head(ARROW_HEAD_ANGLE), to, head(-ARROW_HEAD_ANGLE)), widthPx, color),
        )
    }
}
