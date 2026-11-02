package app.referencecanvas.domain

import app.referencecanvas.domain.model.DateRange
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.domain.model.FolderTree
import app.referencecanvas.domain.model.LinkParser
import app.referencecanvas.domain.model.SearchCategory
import app.referencecanvas.domain.model.SearchFilters
import app.referencecanvas.domain.model.TagNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeLogicTest {
    // ---- tags
    @Test fun `tag names are trimmed collapsed and capped but never blank`() {
        assertEquals("film noir", TagNames.normalize("  film \t noir "))
        assertNull(TagNames.normalize("   "))
        assertEquals(TagNames.MAX_LENGTH, TagNames.normalize("x".repeat(500))!!.length)
    }

    // ---- folders
    private fun f(id: String, name: String, parent: String? = null) = Folder(id, name, parent)

    @Test fun `flatten orders depth-first alphabetically with depths`() {
        val nodes = FolderTree.flatten(listOf(f("b", "Beta"), f("a", "alpha"), f("a2", "Zed", "a"), f("a1", "Ant", "a")))
        assertEquals(listOf("a", "a1", "a2", "b"), nodes.map { it.folder.id })
        assertEquals(listOf(0, 1, 1, 0), nodes.map { it.depth })
    }

    @Test fun `flatten treats orphans as roots and never drops cycle members`() {
        val nodes = FolderTree.flatten(listOf(f("o", "Orphan", "missing"), f("x", "X", "y"), f("y", "Y", "x")))
        assertEquals(setOf("o", "x", "y"), nodes.map { it.folder.id }.toSet())
        assertEquals(3, nodes.size)
    }

    @Test fun `moving a folder under its own descendant is a cycle`() {
        val parents = mapOf("a" to null, "b" to "a", "c" to "b")
        assertTrue(FolderTree.wouldCreateCycle("a", "c") { parents[it] })
        assertTrue(FolderTree.wouldCreateCycle("a", "a") { parents[it] })
        assertFalse(FolderTree.wouldCreateCycle("c", "a") { parents[it] })
        assertFalse(FolderTree.wouldCreateCycle("c", null) { parents[it] })
    }

    @Test fun `an existing loop in the data is reported instead of hanging`() {
        val parents = mapOf("x" to "y", "y" to "x")
        assertTrue(FolderTree.wouldCreateCycle("z", "x") { parents[it] })
    }

    @Test fun `path runs root to leaf and survives loops`() {
        val byId = listOf(f("a", "A"), f("b", "B", "a"), f("c", "C", "b")).associateBy { it.id }
        assertEquals(listOf("a", "b", "c"), FolderTree.path("c", byId).map { it.id })
        assertTrue(FolderTree.path(null, byId).isEmpty())
        val loop = listOf(f("x", "X", "y"), f("y", "Y", "x")).associateBy { it.id }
        assertEquals(2, FolderTree.path("x", loop).size)
    }

    // ---- links
    @Test fun `parse accepts web links and bare domains`() {
        assertEquals("https://example.com/a?b=1", LinkParser.parse("  https://example.com/a?b=1 "))
        assertEquals("https://example.com", LinkParser.parse("example.com"))
        assertEquals("http://localhost:8080/x", LinkParser.parse("http://localhost:8080/x"))
    }

    @Test fun `parse rejects other schemes spaces and non-hosts`() {
        assertNull(LinkParser.parse("javascript:alert(1)"))
        assertNull(LinkParser.parse("file:///sdcard/a.png"))
        assertNull(LinkParser.parse("content://media/1"))
        assertNull(LinkParser.parse("two words.com"))
        assertNull(LinkParser.parse("notadomain"))
        assertNull(LinkParser.parse("https://"))
        assertNull(LinkParser.parse(""))
    }

    @Test fun `extractUrl finds the first explicit link in shared text`() {
        assertEquals("https://a.example/x", LinkParser.extractUrl("Look at this: https://a.example/x, wow"))
        assertNull(LinkParser.extractUrl("example.com has no scheme"))
        assertNull(LinkParser.extractUrl("javascript:alert(1)"))
    }

    @Test fun `hostOf strips credentials port and path`() {
        assertEquals("example.com", LinkParser.hostOf("https://user:pw@Example.com:8443/p?q#f"))
    }

    // ---- search criteria
    @Test fun `no criteria until something narrows the search`() {
        assertFalse(SearchFilters().hasCriteria)
        assertFalse(SearchFilters(text = "   ").hasCriteria)
        assertTrue(SearchFilters(text = "cat").hasCriteria)
        assertTrue(SearchFilters(category = SearchCategory.Pdf).hasCriteria)
        assertTrue(SearchFilters(favoritesOnly = true).hasCriteria)
        assertTrue(SearchFilters(dateRange = DateRange.Week).hasCriteria)
        assertTrue(SearchFilters(tagId = "t").hasCriteria)
    }
}
