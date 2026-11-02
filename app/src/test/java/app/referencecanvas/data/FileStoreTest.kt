package app.referencecanvas.data

import app.referencecanvas.core.filesystem.FileStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = FileStore(tmp.root)
    private fun stagingFiles() = File(tmp.root, "staging").listFiles()?.toList().orEmpty()

    @Test fun `stage streams content and reports size and sha256`() = runTest {
        val staged = store().stage(ByteArrayInputStream("hello".toByteArray()))

        assertEquals(5L, staged.sizeBytes)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", staged.sha256)
        assertEquals("hello", staged.file.readText())
    }

    @Test fun `commit moves the staged file into managed storage`() = runTest {
        val store = store()
        val staged = store.stage(ByteArrayInputStream("data".toByteArray()))
        val relative = store.relativePathFor("abc", "png")

        store.commit(staged.file, relative)

        assertFalse(staged.file.exists())
        assertEquals("data", store.resolve(relative)!!.readText())
        assertTrue(store.delete(relative))
        assertFalse(File(tmp.root, relative).exists())
    }

    @Test fun `failed stream leaves no partial file behind`() = runTest {
        val broken = object : InputStream() {
            private var served = false
            override fun read(): Int = throw IOException("disk fell off")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!served) { served = true; b[off] = 1; return 1 }
                throw IOException("disk fell off")
            }
        }

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { store().stage(broken) } }

        assertTrue(stagingFiles().isEmpty())
    }

    @Test fun `resolve refuses paths that escape the app directory`() {
        val store = store()
        assertNull(store.resolve("../outside.txt"))
        assertNull(store.resolve("references/../../outside.txt"))
        assertNotNull(store.resolve("references/ok.png"))
    }

    @Test fun `clearStaging removes leftovers from interrupted imports`() = runTest {
        val store = store()
        store.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertEquals(1, stagingFiles().size)

        store.clearStaging()

        assertTrue(stagingFiles().isEmpty())
    }
}
