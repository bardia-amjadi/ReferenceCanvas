package app.referencecanvas.data

import app.referencecanvas.core.database.FolderEntity
import app.referencecanvas.data.repository.FolderRepositoryImpl
import app.referencecanvas.data.repository.TagRepositoryImpl
import app.referencecanvas.testing.DirectTransactionRunner
import app.referencecanvas.testing.FakeFolderDao
import app.referencecanvas.testing.FakeTagDao
import app.referencecanvas.testing.FixedClock
import app.referencecanvas.testing.SequentialIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagRepositoryTest {
    private val dao = FakeTagDao()
    private val repo = TagRepositoryImpl(dao, DirectTransactionRunner, SequentialIds())

    @Test fun `create and attach reuses an existing tag regardless of case`() = runTest {
        assertTrue(repo.createAndAttach("Noir", listOf("r1")))
        assertTrue(repo.createAndAttach("  noir ", listOf("r2", "r3")))

        assertEquals(1, dao.tags.size)
        assertEquals(setOf("r1", "r2", "r3"), dao.links.map { it.referenceId }.toSet())
    }

    @Test fun `blank names create nothing`() = runTest {
        assertFalse(repo.createAndAttach("   ", listOf("r1")))
        assertTrue(dao.tags.isEmpty() && dao.links.isEmpty())
    }

    @Test fun `usage counts how many of the given references carry each tag`() = runTest {
        repo.createAndAttach("a", listOf("r1", "r2"))
        repo.createAndAttach("b", listOf("r1"))
        val usage = repo.usageFor(listOf("r1", "r2", "r3"))
        val a = dao.findByName("a")!!.id
        val b = dao.findByName("b")!!.id
        assertEquals(2, usage[a])
        assertEquals(1, usage[b])
    }

    @Test fun `detach removes only the requested references`() = runTest {
        repo.createAndAttach("a", listOf("r1", "r2"))
        val id = dao.findByName("a")!!.id

        repo.detach(id, listOf("r1"))

        assertEquals(listOf("r2"), dao.links.map { it.referenceId })
    }

    @Test fun `usage lookups handle more ids than one SQL chunk`() = runTest {
        val many = (1..1200).map { "r$it" }
        repo.createAndAttach("bulk", many)
        assertEquals(1200, repo.usageFor(many)[dao.findByName("bulk")!!.id])
    }
}

class FolderRepositoryTest {
    private val dao = FakeFolderDao()
    private val repo = FolderRepositoryImpl(dao, DirectTransactionRunner, FixedClock(7L), SequentialIds())

    private fun seed(id: String, parent: String? = null) { dao.folders[id] = FolderEntity(id, id, 1, 1, parent) }

    @Test fun `create trims the name and rejects blanks`() = runTest {
        assertEquals("id-1", repo.create("  Moods  ", null))
        assertEquals("Moods", dao.folders.getValue("id-1").name)
        assertNull(repo.create("   ", null))
    }

    @Test fun `moving a folder into its own subtree is refused and changes nothing`() = runTest {
        seed("a"); seed("b", "a"); seed("c", "b")

        assertFalse(repo.move("a", "c"))
        assertFalse(repo.move("a", "a"))
        assertNull(dao.folders.getValue("a").parentFolderId)
    }

    @Test fun `valid moves and moves to the top level succeed`() = runTest {
        seed("a"); seed("b"); seed("c", "b")

        assertTrue(repo.move("c", "a"))
        assertEquals("a", dao.folders.getValue("c").parentFolderId)
        assertTrue(repo.move("c", null))
        assertNull(dao.folders.getValue("c").parentFolderId)
    }

    @Test fun `moving to a missing parent or missing folder fails`() = runTest {
        seed("a")
        assertFalse(repo.move("a", "ghost"))
        assertFalse(repo.move("ghost", null))
    }

    @Test fun `deleting a folder lifts its contents to the parent`() = runTest {
        seed("top"); seed("mid", "top"); seed("leaf", "mid")

        repo.delete("mid")

        assertEquals("top", dao.folders.getValue("leaf").parentFolderId)
        assertFalse("mid" in dao.folders)
        assertEquals(listOf("mid" to "top"), dao.reassignedBoards)
        assertEquals(listOf("mid" to "top"), dao.reassignedReferences)
    }
}
