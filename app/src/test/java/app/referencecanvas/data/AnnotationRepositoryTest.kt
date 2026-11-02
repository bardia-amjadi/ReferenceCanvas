package app.referencecanvas.data

import app.referencecanvas.core.database.AnnotationEntity
import app.referencecanvas.data.repository.AnnotationRepositoryImpl
import app.referencecanvas.domain.annotation.AnnotationCodec
import app.referencecanvas.domain.annotation.AnnotationData
import app.referencecanvas.domain.annotation.AnnotationKind
import app.referencecanvas.domain.annotation.Pt
import app.referencecanvas.testing.DirectTransactionRunner
import app.referencecanvas.testing.FakeAnnotationDao
import app.referencecanvas.testing.FixedClock
import app.referencecanvas.testing.SequentialIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationRepositoryTest {
    private val dao = FakeAnnotationDao()
    private val repo = AnnotationRepositoryImpl(dao, DirectTransactionRunner, FixedClock(10L), SequentialIds())

    private fun dot(x: Float) = AnnotationData(AnnotationKind.Pen, -1, 0.01f, listOf(Pt(x, 0.5f)))

    @Test fun `added annotations keep paint order via increasing sequence numbers`() = runTest {
        val a = repo.add("r", dot(0.1f))
        val b = repo.add("r", dot(0.2f))

        assertEquals(listOf(a.id, b.id), repo.observe("r").first().items.map { it.id })
        assertTrue(b.data.seq > a.data.seq)
    }

    @Test fun `annotations are stored per reference and independent of each other`() = runTest {
        repo.add("r1", dot(0.1f))
        repo.add("r2", dot(0.2f))

        assertEquals(1, repo.observe("r1").first().items.size)
        assertEquals(1, repo.observe("r2").first().items.size)
    }

    @Test fun `remove then restore brings back the same ids and order`() = runTest {
        val a = repo.add("r", dot(0.1f))
        val b = repo.add("r", dot(0.2f))

        repo.remove(listOf(a.id))
        assertEquals(listOf(b.id), repo.observe("r").first().items.map { it.id })
        repo.restore(listOf(a))

        assertEquals(listOf(a.id, b.id), repo.observe("r").first().items.map { it.id })
    }

    @Test fun `clear returns what it removed so it can be undone`() = runTest {
        repo.add("r", dot(0.1f)); repo.add("r", dot(0.2f))

        val removed = repo.clear("r")

        assertEquals(2, removed.size)
        assertTrue(repo.observe("r").first().items.isEmpty())
        repo.restore(removed)
        assertEquals(2, repo.observe("r").first().items.size)
    }

    @Test fun `unreadable rows are counted and skipped instead of failing the image`() = runTest {
        repo.add("r", dot(0.1f))
        dao.rows["bad"] = AnnotationEntity("bad", "r", "Pen", "{ this is not json", 1, 1)
        dao.rows["future"] = AnnotationEntity("future", "r", "Hologram", """{"kind":"Hologram","color":0,"width":0.1,"points":[]}""", 1, 1)

        val set = repo.observe("r").first()

        assertEquals(1, set.items.size)
        assertEquals(2, set.unreadable)
    }

    @Test fun `stored rows hold the codec's JSON and the kind name`() = runTest {
        val a = repo.add("r", dot(0.3f))
        val row = dao.rows.getValue(a.id)
        assertEquals("Pen", row.type)
        assertEquals(a.data, AnnotationCodec.decode(row.serializedData))
    }
}
