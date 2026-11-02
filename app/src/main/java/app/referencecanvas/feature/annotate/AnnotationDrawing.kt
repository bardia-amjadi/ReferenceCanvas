package app.referencecanvas.feature.annotate

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.referencecanvas.domain.annotation.AnnotationData
import app.referencecanvas.domain.annotation.AnnotationGeometry
import app.referencecanvas.domain.annotation.Primitive
import app.referencecanvas.domain.annotation.ShapeRender

/** Draws backend-neutral [Primitive]s (pixel space) onto a Compose canvas, shifted by [origin]. */
internal fun DrawScope.drawPrimitives(prims: List<Primitive>, origin: Offset, measurer: TextMeasurer) {
    for (p in prims) {
        when (p) {
            is Primitive.Poly -> {
                val path = Path()
                p.points.forEachIndexed { i, (x, y) ->
                    if (i == 0) path.moveTo(origin.x + x, origin.y + y) else path.lineTo(origin.x + x, origin.y + y)
                }
                if (p.closed) path.close()
                drawPath(path, Color(p.color), style = Stroke(p.widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            is Primitive.Oval -> drawOval(
                Color(p.color),
                topLeft = Offset(origin.x + p.left, origin.y + p.top),
                size = Size(p.right - p.left, p.bottom - p.top),
                style = Stroke(p.widthPx),
            )
            is Primitive.TextRun -> drawText(
                measurer,
                p.text,
                Offset(origin.x + p.x, origin.y + p.y),
                TextStyle(color = Color(p.color), fontSize = p.sizePx.toSp(), lineHeight = (p.sizePx * AnnotationGeometry.TEXT_LINE_FACTOR).toSp()),
            )
        }
    }
}

/** Read-only annotations laid over an aspect-fitted image. Place it inside the same layer as the image. */
@Composable
fun AnnotationOverlay(annotations: List<AnnotationData>, aspect: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val fit = AnnotationGeometry.fit(size.width, size.height, aspect)
        val origin = Offset(fit.left, fit.top)
        for (a in annotations.sortedBy { it.seq }) {
            drawPrimitives(ShapeRender.toPrimitives(a, fit.width, fit.height), origin, measurer)
        }
    }
}
