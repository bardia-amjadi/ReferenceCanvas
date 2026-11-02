package app.referencecanvas.domain

import app.referencecanvas.domain.annotation.AnnotationCodec
import app.referencecanvas.domain.annotation.AnnotationData
import app.referencecanvas.domain.annotation.AnnotationGeometry
import app.referencecanvas.domain.annotation.AnnotationKind
import app.referencecanvas.domain.annotation.AnnotationStyle
import app.referencecanvas.domain.annotation.Primitive
import app.referencecanvas.domain.annotation.Pt
import app.referencecanvas.domain.annotation.ShapeRender
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationTest {
    private fun pen(vararg pts: Pt) = AnnotationData(AnnotationKind.Pen, 0xFFEF4444.toInt(), 0.01f, pts.toList(), seq = 3)
    private fun shape(kind: AnnotationKind, a: Pt, b: Pt, width: Float = 0.01f) = AnnotationData(kind, 0xFF3B82F6.toInt(), width, listOf(a, b))

    // ---- serialization
    @Test fun `every kind survives an encode-decode round trip`() {
        val samples = listOf(
            pen(Pt(0.1f, 0.2f), Pt(0.3f, 0.4f)),
            AnnotationData(AnnotationKind.Highlighter, 0x80FFFF00.toInt(), 0.02f, listOf(Pt(0f, 0f), Pt(1f, 1f))),
            shape(AnnotationKind.Line, Pt(0f, 0f), Pt(1f, 1f)),
            shape(AnnotationKind.Arrow, Pt(0.2f, 0.2f), Pt(0.8f, 0.5f)),
            shape(AnnotationKind.Rect, Pt(0.1f, 0.1f), Pt(0.9f, 0.9f)),
            shape(AnnotationKind.Ellipse, Pt(0.1f, 0.1f), Pt(0.9f, 0.9f)),
            AnnotationData(AnnotationKind.Text, -1, 0.01f, listOf(Pt(0.5f, 0.5f)), text = "Héllo\nworld ✓", textSize = 0.05f),
        )
        samples.forEach { assertEquals(it, AnnotationCodec.decode(AnnotationCodec.encode(it))) }
    }

    @Test fun `decoding tolerates unknown fields and rejects garbage and invalid data`() {
        val future = """{"kind":"Pen","color":-1,"width":0.01,"points":[{"x":0.1,"y":0.2}],"seq":1,"futureField":42}"""
        assertNotNull(AnnotationCodec.decode(future))
        assertNull(AnnotationCodec.decode("not json"))
        assertNull(AnnotationCodec.decode("""{"kind":"Hologram","color":0,"width":0.01,"points":[]}"""))
        assertNull(AnnotationCodec.decode("""{"kind":"Rect","color":0,"width":0.01,"points":[{"x":0,"y":0}]}""")) // needs two points
        assertNull(AnnotationCodec.decode("""{"kind":"Text","color":0,"width":0.01,"points":[{"x":0,"y":0}],"text":"  ","textSize":0.05}"""))
        assertNull(AnnotationCodec.decode("""{"kind":"Pen","color":0,"width":-1,"points":[{"x":0,"y":0}]}"""))
    }

    @Test fun `width and text size sliders map into their ranges`() {
        assertEquals(AnnotationStyle.MIN_WIDTH, AnnotationStyle.widthFor(-5f), 0f)
        assertEquals(AnnotationStyle.MAX_WIDTH, AnnotationStyle.widthFor(5f), 0f)
        assertTrue(AnnotationStyle.textSizeFor(0.5f) in 0.025f..0.09f)
    }

    // ---- geometry
    @Test fun `fit centres the image and normalizes taps relative to it`() {
        val fit = AnnotationGeometry.fit(1000f, 500f, 1f) // square image in a wide view
        assertEquals(250f, fit.left, 1e-3f)
        assertEquals(500f, fit.width, 1e-3f)
        val centre = fit.toNormalized(500f, 250f)
        assertEquals(0.5f, centre.x, 1e-5f)
        assertEquals(0.5f, centre.y, 1e-5f)
        assertEquals(0f, fit.toNormalized(-50f, 9999f).x, 0f) // clamped to the image
    }

    @Test fun `fit handles tall images and degenerate input`() {
        val fit = AnnotationGeometry.fit(400f, 1000f, 2f) // wide image in a tall view
        assertEquals(400f, fit.width, 1e-3f)
        assertEquals(200f, fit.height, 1e-3f)
        assertEquals(400f, AnnotationGeometry.fit(400f, 1000f, 0f).width, 1e-3f)
    }

    @Test fun `normalized points map to the same image spot at different sizes`() {
        val p = Pt(0.25f, 0.75f)
        val small = ShapeRender.toPrimitives(pen(p, p), 200f, 100f).filterIsInstance<Primitive.Poly>().single().points.first()
        val large = ShapeRender.toPrimitives(pen(p, p), 2000f, 1000f).filterIsInstance<Primitive.Poly>().single().points.first()
        assertEquals(small.first * 10f, large.first, 1e-3f)
        assertEquals(small.second * 10f, large.second, 1e-3f)
    }

    @Test fun `simplify keeps endpoints and corners but drops collinear points`() {
        val line = (0..50).map { Pt(it / 50f, 0.5f) }
        assertEquals(listOf(line.first(), line.last()), AnnotationGeometry.simplify(line))
        val corner = (0..10).map { Pt(it / 10f, 0f) } + (1..10).map { Pt(1f, it / 10f) }
        val simplified = AnnotationGeometry.simplify(corner)
        assertEquals(3, simplified.size)
        assertEquals(Pt(1f, 0f), simplified[1])
    }

    @Test fun `hit testing finds strokes within reach and ignores far points`() {
        val line = shape(AnnotationKind.Line, Pt(0.1f, 0.5f), Pt(0.9f, 0.5f))
        assertTrue(AnnotationGeometry.hit(line, 500f, 505f, 1000f, 1000f, tolerance = 10f))
        assertFalse(AnnotationGeometry.hit(line, 500f, 600f, 1000f, 1000f, tolerance = 10f))
        assertFalse(AnnotationGeometry.hit(line, 50f, 500f, 1000f, 1000f, tolerance = 10f)) // beyond the end
    }

    @Test fun `rectangles hit on their outline not their interior`() {
        val rect = shape(AnnotationKind.Rect, Pt(0.2f, 0.2f), Pt(0.8f, 0.8f))
        assertTrue(AnnotationGeometry.hit(rect, 200f, 500f, 1000f, 1000f, 8f))
        assertFalse(AnnotationGeometry.hit(rect, 500f, 500f, 1000f, 1000f, 8f))
    }

    @Test fun `text hits anywhere inside its box`() {
        val text = AnnotationData(AnnotationKind.Text, -1, 0.01f, listOf(Pt(0.1f, 0.1f)), text = "Hello", textSize = 0.1f)
        assertTrue(AnnotationGeometry.hit(text, 150f, 130f, 1000f, 1000f, 4f))
        assertFalse(AnnotationGeometry.hit(text, 900f, 900f, 1000f, 1000f, 4f))
    }

    @Test fun `bounds contain every point`() {
        val b = AnnotationGeometry.bounds(pen(Pt(0.2f, 0.3f), Pt(0.6f, 0.9f)), 1000f, 1000f)
        assertTrue(b[0] <= 0.2f && b[1] <= 0.3f && b[2] >= 0.6f && b[3] >= 0.9f)
    }

    // ---- rendering primitives
    @Test fun `a single-point pen stroke becomes a dot`() {
        val poly = ShapeRender.toPrimitives(pen(Pt(0.5f, 0.5f)), 100f, 100f).single() as Primitive.Poly
        assertEquals(2, poly.points.size)
        assertEquals(poly.points[0], poly.points[1])
    }

    @Test fun `highlighter is translucent and wider than a pen of the same width`() {
        val data = AnnotationData(AnnotationKind.Highlighter, 0xFFFFFF00.toInt(), 0.01f, listOf(Pt(0f, 0f), Pt(1f, 0f)))
        val poly = ShapeRender.toPrimitives(data, 1000f, 1000f).single() as Primitive.Poly
        assertEquals(0x59, (poly.color ushr 24) and 0xFF)
        assertEquals(0x00FFFF00, poly.color and 0x00FFFFFF)
        assertTrue(poly.widthPx > 0.01f * 1000f * 2f)
    }

    @Test fun `arrow head is symmetric about the shaft and ends at the tip`() {
        val prims = ShapeRender.toPrimitives(shape(AnnotationKind.Arrow, Pt(0.1f, 0.5f), Pt(0.9f, 0.5f)), 1000f, 1000f)
        assertEquals(2, prims.size)
        val head = (prims[1] as Primitive.Poly).points
        val tip = head[1]
        assertEquals(900f, tip.first, 1e-2f)
        assertEquals(500f, tip.second, 1e-2f)
        assertEquals(hypot(head[0].first - tip.first, head[0].second - tip.second), hypot(head[2].first - tip.first, head[2].second - tip.second), 1e-2f)
        assertEquals(head[0].second - 500f, 500f - head[2].second, 1e-2f) // mirrored above/below the shaft
        assertTrue(head[0].first < tip.first && head[2].first < tip.first) // wings sweep back from the tip
    }

    @Test fun `ellipse and text keep their anchors`() {
        val oval = ShapeRender.toPrimitives(shape(AnnotationKind.Ellipse, Pt(0.8f, 0.9f), Pt(0.2f, 0.1f)), 100f, 200f).single() as Primitive.Oval
        assertEquals(20f, oval.left, 1e-3f); assertEquals(20f, oval.top, 1e-3f)
        assertEquals(80f, oval.right, 1e-3f); assertEquals(180f, oval.bottom, 1e-3f)
        val text = AnnotationData(AnnotationKind.Text, -1, 0.01f, listOf(Pt(0.5f, 0.25f)), text = "Hi", textSize = 0.1f)
        val run = ShapeRender.toPrimitives(text, 400f, 200f).single() as Primitive.TextRun
        assertEquals(200f, run.x, 1e-3f); assertEquals(50f, run.y, 1e-3f); assertEquals(20f, run.sizePx, 1e-3f)
    }

    @Test fun `stroke width scales with the image so annotations look the same at any zoom`() {
        val a = ShapeRender.toPrimitives(shape(AnnotationKind.Line, Pt(0f, 0f), Pt(1f, 1f), width = 0.02f), 500f, 500f).single() as Primitive.Poly
        val b = ShapeRender.toPrimitives(shape(AnnotationKind.Line, Pt(0f, 0f), Pt(1f, 1f), width = 0.02f), 1000f, 1000f).single() as Primitive.Poly
        assertTrue(abs(b.widthPx - 2 * a.widthPx) < 1e-3f)
    }
}
