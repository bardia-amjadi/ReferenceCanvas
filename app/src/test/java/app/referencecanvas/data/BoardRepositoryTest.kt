package app.referencecanvas.data

import app.referencecanvas.core.database.BoardEntity
import app.referencecanvas.data.repository.BoardRepositoryImpl
import app.referencecanvas.domain.model.BoardViewMode
import app.referencecanvas.testing.DirectTransactionRunner
import app.referencecanvas.testing.FakeBoardDao
import app.referencecanvas.testing.FakeBoardReferenceDao
import app.referencecanvas.testing.FixedClock
import app.referencecanvas.testing.SequentialIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRepositoryTest {
    private val dao = FakeBoardDao()
    private val membership = FakeBoardReferenceDao()
    private val clock = FixedClock(5_000L)
    private val repo = BoardRepositoryImpl(dao, membership, DirectTransactionRunner, clock, SequentialIds())

    @Test fun `create stores a normalized board with the chosen view mode`() = runTest {
        val id = repo.create("  Poster   studies ", BoardViewMode.Gallery)

        assertEquals("id-1", id)
        val board = dao.boards.getValue("id-1")
        assertEquals("Poster studies", board.name)
        assertEquals("Gallery", board.viewMode)
        assertEquals(5_000L, board.createdAt)
        assertEquals(board.createdAt, board.updatedAt)
    }

    @Test fun `create with blank name stores nothing`() = runTest {
        assertNull(repo.create("   ", BoardViewMode.Canvas))
        assertTrue(dao.boards.isEmpty())
    }

    @Test fun `rename updates name and timestamp`() = runTest {
        val id = repo.create("Old", BoardViewMode.Canvas)!!
        clock.time = 9_000L

        assertTrue(repo.rename(id, " New "))

        val board = dao.boards.getValue(id)
        assertEquals("New", board.name)
        assertEquals(9_000L, board.updatedAt)
    }

    @Test fun `rename rejects blank names and unknown boards`() = runTest {
        val id = repo.create("Keep", BoardViewMode.Canvas)!!

        assertFalse(repo.rename(id, "  "))
        assertFalse(repo.rename("missing", "Name"))
        assertEquals("Keep", dao.boards.getValue(id).name)
    }

    @Test fun `duplicate copies layout state but not favorite or pin and shares membership`() = runTest {
        dao.insert(
            BoardEntity(
                id = "src", name = "Moodboard", createdAt = 1L, updatedAt = 1L,
                canvasOffsetX = 1234.5, canvasZoom = 2f, isFavorite = true, isPinned = true,
            ),
        )

        val newId = repo.duplicate("src")

        assertEquals("id-1", newId)
        val copy = dao.boards.getValue("id-1")
        assertEquals("Moodboard copy", copy.name)
        assertEquals(1234.5, copy.canvasOffsetX, 0.0)
        assertEquals(2f, copy.canvasZoom, 0f)
        assertFalse(copy.isFavorite)
        assertFalse(copy.isPinned)
        assertEquals(listOf("src" to "id-1"), membership.copies)
    }

    @Test fun `duplicate of a missing board returns null and copies nothing`() = runTest {
        assertNull(repo.duplicate("nope"))
        assertTrue(membership.copies.isEmpty())
    }

    @Test fun `favorite pin and view mode persist`() = runTest {
        val id = repo.create("Board", BoardViewMode.Canvas)!!

        repo.setFavorite(id, true)
        repo.setPinned(id, true)
        repo.setViewMode(id, BoardViewMode.Gallery)

        val board = dao.boards.getValue(id)
        assertTrue(board.isFavorite)
        assertTrue(board.isPinned)
        assertEquals("Gallery", board.viewMode)
    }

    @Test fun `delete removes only the board`() = runTest {
        val id = repo.create("Gone", BoardViewMode.Canvas)!!
        repo.delete(id)
        assertTrue(dao.boards.isEmpty())
    }
}
