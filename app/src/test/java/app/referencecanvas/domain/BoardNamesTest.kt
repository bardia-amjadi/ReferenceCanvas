package app.referencecanvas.domain

import app.referencecanvas.core.util.escapeLike
import app.referencecanvas.domain.model.BoardNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardNamesTest {
    @Test fun `trims and collapses whitespace`() {
        assertEquals("Moodboard ideas", BoardNames.normalize("  Moodboard \n  ideas  "))
    }

    @Test fun `blank names are rejected`() {
        assertNull(BoardNames.normalize(""))
        assertNull(BoardNames.normalize("   \t\n"))
    }

    @Test fun `copy name appends suffix`() {
        assertEquals("Travel copy", BoardNames.copyName("Travel"))
    }

    @Test fun `like wildcards are escaped so search is literal`() {
        assertEquals("100\\%\\_done", escapeLike("100%_done"))
        assertEquals("a\\\\b", escapeLike("a\\b"))
    }
}
