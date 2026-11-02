package app.referencecanvas.domain

import app.referencecanvas.core.util.sanitizeExtension
import app.referencecanvas.core.util.titleFromFileName
import app.referencecanvas.domain.model.defaultCardPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileNamingTest {
    @Test fun `extension is lowercased and stripped of unsafe characters`() {
        assertEquals("jpg", sanitizeExtension("Holiday.JPG"))
        assertEquals("tar", sanitizeExtension("a.b/../tar"))
        assertEquals("bin", sanitizeExtension("no_extension"))
        assertEquals("bin", sanitizeExtension("weird.@#$"))
        assertEquals("abcdefghij", sanitizeExtension("x.abcdefghijklmnop"))
    }

    @Test fun `title drops one extension and is never blank`() {
        assertEquals("Holiday photo", titleFromFileName("Holiday photo.png"))
        assertEquals("archive.tar", titleFromFileName("archive.tar.gz"))
        assertEquals(".hidden", titleFromFileName(".hidden"))
        assertEquals("Untitled", titleFromFileName("   "))
    }

    @Test fun `default card positions fill a grid row by row`() {
        assertEquals(0.0 to 0.0, defaultCardPosition(0))
        assertEquals(260.0 to 0.0, defaultCardPosition(1))
        assertEquals(0.0 to 260.0, defaultCardPosition(8))
        assertEquals(520.0 to 520.0, defaultCardPosition(18))
        assertThrows(IllegalArgumentException::class.java) { defaultCardPosition(-1) }
    }
}
