package app.referencecanvas.domain

import app.referencecanvas.domain.canvas.AlignMode
import app.referencecanvas.domain.canvas.CanvasCard
import app.referencecanvas.domain.canvas.CanvasOps
import app.referencecanvas.domain.canvas.CanvasViewport
import app.referencecanvas.domain.canvas.MAX_WORLD
import app.referencecanvas.domain.canvas.MAX_ZOOM
import app.referencecanvas.domain.canvas.MIN_ZOOM
import app.referencecanvas.domain.canvas.WorldRect
import app.referencecanvas.domain.canvas.fitViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasMathTest {
    private fun card(id: String, x: Double, y: Double, w: Double = 100.0, h: Double = 100.0, z: Int = 0) =
        CanvasCard(id, id, "image/png", null, x, y, w, h, z, false)

    @Test fun `world and screen conversion round-trips far from the origin`() {
        val vp = CanvasViewport(-4_999_000.0, 12.5, 0.5f)
        assertEquals(1e7, vp.toWorldX(vp.toScreenX(1e7)), 1e-6)
        assertEquals(-1e7, vp.toWorldY(vp.toScreenY(-1e7)), 1e-6)
    }

    @Test fun `zooming around a point keeps the world point under it fixed`() {
        val vp = CanvasViewport(30.0, -20.0, 1.5f)
        val before = vp.toWorldX(200.0) to vp.toWorldY(300.0)
        val zoomed = vp.zoomedAround(200.0, 300.0, 2.0)
        assertEquals(3.0f, zoomed.zoom, 1e-6f)
        assertEquals(before.first, zoomed.toWorldX(200.0), 1e-9)
        assertEquals(before.second, zoomed.toWorldY(300.0), 1e-9)
    }

    @Test fun `zoom is clamped to the supported range`() {
        assertEquals(MAX_ZOOM, CanvasViewport().zoomedAround(0.0, 0.0, 1e9).zoom, 0f)
        assertEquals(MIN_ZOOM, CanvasViewport().zoomedAround(0.0, 0.0, 1e-9).zoom, 0f)
        assertEquals(1f, CanvasViewport().zoomedAround(0.0, 0.0, Double.NaN).zoom, 0f)
    }

    @Test fun `fit shows every corner of the bounds inside the screen`() {
        val bounds = WorldRect(-5000.0, 200.0, 5000.0, 3000.0)
        val vp = fitViewport(bounds, 1000.0, 800.0)
        for ((x, y) in listOf(bounds.left to bounds.top, bounds.right to bounds.bottom)) {
            assertTrue(vp.toScreenX(x) in 0.0..1000.0)
            assertTrue(vp.toScreenY(y) in 0.0..800.0)
        }
    }

    @Test fun `fit does not zoom in past the maximum for small content`() {
        val vp = fitViewport(WorldRect(0.0, 0.0, 50.0, 50.0), 1000.0, 800.0)
        assertEquals(1f, vp.zoom, 0f)
    }

    @Test fun `coerced pulls an out-of-range centre back and repairs invalid values`() {
        val far = CanvasViewport(-1e12, 0.0, 1f).coerced(1000.0, 800.0)
        assertEquals(MAX_WORLD, far.toWorldX(500.0), 1e-3)
        assertEquals(CanvasViewport(), CanvasViewport(Double.NaN, 0.0, 1f).coerced(1000.0, 800.0))
    }

    @Test fun `visible culling keeps only intersecting cards`() {
        val cards = listOf(card("in", 10.0, 10.0), card("edge", 950.0, 0.0), card("out", 5000.0, 5000.0))
        val area = CanvasViewport().visibleRect(1000.0, 800.0)
        assertEquals(listOf("in", "edge"), CanvasOps.visible(cards, area).map { it.id })
    }

    @Test fun `hit test returns the top-most card and null on empty space`() {
        val cards = listOf(card("low", 0.0, 0.0, z = 1), card("high", 50.0, 50.0, z = 5), card("mid", 0.0, 0.0, z = 3))
        assertEquals("high", CanvasOps.hitTest(cards, 60.0, 60.0)?.id)
        assertEquals("mid", CanvasOps.hitTest(cards, 10.0, 10.0)?.id)
        assertNull(CanvasOps.hitTest(cards, 900.0, 900.0))
    }

    @Test fun `align left and bottom use the selection bounds`() {
        val cards = listOf(card("a", 100.0, 0.0), card("b", 300.0, 150.0, h = 50.0))
        assertEquals(listOf(100.0, 100.0), CanvasOps.align(cards, AlignMode.Left).map { it.x })
        val bottoms = CanvasOps.align(cards, AlignMode.Bottom)
        assertEquals(100.0, bottoms[0].y, 1e-9) // bottom edge 200 -> y = 200 - 100
        assertEquals(150.0, bottoms[1].y, 1e-9) // bottom edge 200 -> y = 200 - 50
    }

    @Test fun `grid arrange never overlaps and keeps every card`() {
        val cards = (0 until 10).map { card("c$it", (it * 7).toDouble(), (it * 3).toDouble(), w = 80.0 + it, h = 60.0 + it) }
        val laid = CanvasOps.gridArrange(cards)
        assertEquals(cards.map { it.id }.toSet(), laid.map { it.id }.toSet())
        val rects = laid.map { l -> val c = cards.first { it.id == l.id }; WorldRect(l.x, l.y, l.x + c.w, l.y + c.h) }
        for (i in rects.indices) for (j in i + 1 until rects.size) assertFalse(rects[i].intersects(rects[j]))
    }

    @Test fun `bring to front and send to back preserve relative order`() {
        val cards = listOf(card("a", 0.0, 0.0, z = 1), card("b", 0.0, 0.0, z = 2), card("c", 0.0, 0.0, z = 3))
        val front = CanvasOps.bringToFront(cards, setOf("a", "b")).associate { it.id to it.z }
        assertTrue(front.getValue("a") > 3 && front.getValue("b") > front.getValue("a"))
        val back = CanvasOps.sendToBack(cards, setOf("b", "c")).associate { it.id to it.z }
        assertTrue(back.getValue("b") < 1 && back.getValue("c") > back.getValue("b"))
    }

    @Test fun `snap rounds to the nearest grid line`() {
        assertEquals(80.0, CanvasOps.snap(61.0), 0.0)
        assertEquals(40.0, CanvasOps.snap(59.0), 0.0)
        assertEquals(-40.0, CanvasOps.snap(-25.0), 0.0)
    }

    @Test fun `moves are clamped to the world limit`() {
        val moved = CanvasOps.move(listOf(card("a", 9_999_990.0, 0.0)), 1000.0, 0.0).single()
        assertEquals(MAX_WORLD, moved.x, 0.0)
    }
}
