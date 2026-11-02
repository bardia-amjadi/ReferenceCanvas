package app.referencecanvas.core

import app.referencecanvas.core.backup.BackupFormat
import app.referencecanvas.core.backup.BackupReader
import app.referencecanvas.core.backup.BackupScan
import app.referencecanvas.core.backup.BackupWriter
import app.referencecanvas.core.backup.InvalidReason
import app.referencecanvas.core.backup.RestoreHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val photo = ByteArray(5000) { (it * 31).toByte() }

    private fun build(
        schemaVersion: Int = 1,
        formatVersion: Int = BackupFormat.FORMAT_VERSION,
        boardLines: List<String> = listOf("""{"id":"b1","name":"Board \u2713","zoom":1.5,"fav":0,"cover":null}"""),
        finish: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BackupWriter(out, "0.6.0", schemaVersion, 1234L, formatVersion)
        BackupFormat.TABLES.forEach { table ->
            writer.writeTable(table, if (table == "boards") boardLines.asSequence() else emptySequence())
        }
        writer.writePrefs(JsonObject(mapOf("accent" to JsonPrimitive("Cyan"))))
        writer.writeFile("references/a.png", ByteArrayInputStream(photo))
        if (finish) writer.finish(missingFiles = 2)
        writer.close()
        return out.toByteArray()
    }

    private fun scan(bytes: ByteArray, max: Int = 1, handler: RestoreHandler? = null) =
        BackupReader.scan(ByteArrayInputStream(bytes), max, handler)

    private fun reason(scan: BackupScan) = (scan as BackupScan.Bad).reason

    @Test fun `a written backup validates and reports its manifest`() {
        val result = scan(build()) as BackupScan.Ok
        assertEquals(BackupFormat.FORMAT_NAME, result.manifest.format)
        assertEquals(1, result.manifest.tableCounts["boards"])
        assertEquals(1, result.manifest.fileCount)
        assertEquals(photo.size.toLong(), result.manifest.fileBytes)
        assertEquals(2, result.manifest.missingFiles)
        assertEquals(1234L, result.manifest.createdAt)
        assertTrue("files/references/a.png" in result.digests)
    }

    @Test fun `restore delivers rows prefs and file bytes exactly`() {
        val rows = mutableListOf<Pair<String, JsonObject>>()
        var fileName: String? = null
        var fileBytes: ByteArray? = null
        val handler = object : RestoreHandler {
            override fun row(table: String, row: JsonObject) { rows += table to row }
            override fun file(relativePath: String, content: InputStream) { fileName = relativePath; fileBytes = content.readBytes() }
            var prefsHolder: JsonObject? = null
            override fun prefs(prefs: JsonObject) { prefsHolder = prefs }
        }
        assertTrue(scan(build(), handler = handler) is BackupScan.Ok)

        assertEquals(1, rows.size)
        assertEquals("boards", rows[0].first)
        assertEquals("Board ✓", (rows[0].second["name"] as JsonPrimitive).content)
        assertEquals("references/a.png", fileName)
        assertArrayEquals(photo, fileBytes)
        assertEquals("Cyan", (handler.prefsHolder!!["accent"] as JsonPrimitive).content)
    }

    @Test fun `a flipped byte in a stored file is detected`() {
        val bytes = build()
        val at = indexOf(bytes, photo.copyOfRange(100, 140))
        assertTrue(at >= 0)
        bytes[at + 5] = (bytes[at + 5].toInt() xor 0x01).toByte()
        assertEquals(InvalidReason.Corrupt, reason(scan(bytes)))
    }

    @Test fun `an archive cut short is reported incomplete`() {
        val bytes = build()
        assertEquals(InvalidReason.Incomplete, reason(scan(bytes.copyOf(bytes.size / 2))))
    }

    @Test fun `an archive missing its manifest is incomplete`() {
        assertEquals(InvalidReason.Incomplete, reason(scan(build(finish = false))))
    }

    @Test fun `arbitrary files and other zips are not backups`() {
        assertEquals(InvalidReason.NotABackup, reason(scan("just some text".toByteArray())))
        assertEquals(InvalidReason.NotABackup, reason(scan(ByteArray(0))))
        val other = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("holiday.jpg")); z.write(1); z.closeEntry() } }
        assertEquals(InvalidReason.NotABackup, reason(scan(other.toByteArray())))
    }

    @Test fun `path traversal entries are refused`() {
        val evil = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z ->
                z.putNextEntry(ZipEntry("data/boards.jsonl")); z.closeEntry()
                z.putNextEntry(ZipEntry("files/references/../../evil.sh")); z.write(1); z.closeEntry()
            }
        }
        assertEquals(InvalidReason.UnsafeEntry, reason(scan(evil.toByteArray())))
    }

    @Test fun `entry name rules`() {
        assertTrue(BackupFormat.isSafeEntryName("files/references/3f2a-b.PNG"))
        assertTrue(BackupFormat.isSafeEntryName("data/board_references.jsonl"))
        assertFalse(BackupFormat.isSafeEntryName("/etc/passwd"))
        assertFalse(BackupFormat.isSafeEntryName("files/references/../x"))
        assertFalse(BackupFormat.isSafeEntryName("files/references/sub/x.png"))
        assertFalse(BackupFormat.isSafeEntryName("files/other/x.png"))
        assertFalse(BackupFormat.isSafeEntryName("data/unknown_table.jsonl"))
        assertFalse(BackupFormat.isSafeEntryName("files\\references\\x"))
        assertFalse(BackupFormat.isSafeEntryName(""))
    }

    @Test fun `newer formats and newer schemas are refused rather than guessed at`() {
        assertEquals(InvalidReason.NewerFormat, reason(scan(build(formatVersion = 99))))
        assertEquals(InvalidReason.NewerSchema, reason(scan(build(schemaVersion = 5), max = 1)))
        assertTrue(scan(build(schemaVersion = 1), max = 3) is BackupScan.Ok)
    }

    @Test fun `malformed rows are corrupt not ignored`() {
        assertEquals(InvalidReason.Corrupt, reason(scan(build(boardLines = listOf("this is not json")))))
        assertEquals(InvalidReason.Corrupt, reason(scan(build(boardLines = listOf("""{"id":{"nested":1}}""")))))
        assertEquals(InvalidReason.Corrupt, reason(scan(build(boardLines = listOf("""{"bad column":1}""")))))
    }

    @Test fun `a single enormous line cannot exhaust memory`() {
        val huge = "{\"id\":\"" + "x".repeat(2_000_000) + "\"}"
        assertEquals(InvalidReason.Corrupt, reason(scan(build(boardLines = listOf(huge)))))
    }

    @Test fun `numbers nulls and unicode survive the round trip`() {
        var seen: JsonObject? = null
        val handler = object : RestoreHandler {
            override fun row(table: String, row: JsonObject) { if (table == "boards") seen = row }
            override fun file(relativePath: String, content: InputStream) { content.readBytes() }
            override fun prefs(prefs: JsonObject) = Unit
        }
        scan(build(), handler = handler)
        assertEquals("1.5", (seen!!["zoom"] as JsonPrimitive).content)
        assertTrue(seen!!["cover"] is kotlinx.serialization.json.JsonNull)
    }

    @Test fun `an empty library still produces a valid backup`() {
        val out = ByteArrayOutputStream()
        val w = BackupWriter(out, "0.6.0", 1, 1L)
        BackupFormat.TABLES.forEach { w.writeTable(it, emptySequence()) }
        w.writePrefs(JsonObject(emptyMap()))
        w.finish(0); w.close()
        val ok = scan(out.toByteArray()) as BackupScan.Ok
        assertEquals(0, ok.manifest.fileCount)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
