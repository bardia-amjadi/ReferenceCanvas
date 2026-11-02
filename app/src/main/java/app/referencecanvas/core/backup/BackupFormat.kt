package app.referencecanvas.core.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Backup file format (version 1): one ZIP archive, written and read strictly sequentially so large libraries stream
 * through bounded memory.
 *
 *   data/<table>.jsonl      one JSON object per line, one file per database table
 *   data/prefs.json         app preferences
 *   files/references/<id>.<ext>   the managed reference files, byte for byte
 *   entries.jsonl           path, size and SHA-256 of every entry above
 *   manifest.json           LAST: format/app/schema versions, row and file counts, SHA-256 of entries.jsonl
 *
 * The manifest is written last, so an archive without one is, by definition, an incomplete backup.
 */
object BackupFormat {
    const val FORMAT_NAME = "reference-canvas-backup"
    const val FORMAT_VERSION = 1
    const val MANIFEST = "manifest.json"
    const val ENTRIES = "entries.jsonl"
    const val PREFS = "data/prefs.json"
    const val DATA_DIR = "data/"
    const val FILE_DIR = "files/"
    const val FILE_SUBDIR = "references/"

    /** Parents before children; also the order rows are written. */
    val TABLES = listOf(
        "folders", "boards", "reference_items", "board_references",
        "tags", "reference_tags", "annotations", "recent_searches",
    )

    fun tablePath(table: String) = "$DATA_DIR$table.jsonl"

    private val SAFE_FILE_NAME = Regex("^[A-Za-z0-9._-]{1,200}$")

    /** Only entry names this format can produce are accepted: no absolute paths, no `..`, no surprises. */
    fun isSafeEntryName(name: String): Boolean {
        if (name == MANIFEST || name == ENTRIES || name == PREFS) return true
        if (name.startsWith(DATA_DIR)) {
            return TABLES.any { tablePath(it) == name }
        }
        if (name.startsWith("$FILE_DIR$FILE_SUBDIR")) {
            val leaf = name.removePrefix("$FILE_DIR$FILE_SUBDIR")
            return SAFE_FILE_NAME.matches(leaf) && leaf != "." && leaf != ".."
        }
        return false
    }

    fun isFileEntry(name: String) = name.startsWith("$FILE_DIR$FILE_SUBDIR")
    fun isTableEntry(name: String) = TABLES.any { tablePath(it) == name }
    fun tableOf(name: String): String? = TABLES.firstOrNull { tablePath(it) == name }
}

@Serializable
data class EntryDigest(val path: String, val size: Long, val sha256: String)

@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val tableCounts: Map<String, Int>,
    val fileCount: Int,
    val fileBytes: Long,
    /** Referenced files that were not on disk when the backup ran (recorded, never silently ignored). */
    val missingFiles: Int,
    val entriesSha256: String,
    val entriesCount: Int,
)

enum class InvalidReason { NotABackup, Incomplete, Corrupt, UnsafeEntry, NewerFormat, NewerSchema, Mismatch, Unreadable }

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private const val BUFFER = 64 * 1024

/** Streams a backup archive to [output]. Call table/file writers, then [finish] exactly once. */
class BackupWriter(
    output: OutputStream,
    private val appVersion: String,
    private val schemaVersion: Int,
    private val createdAt: Long,
    private val formatVersion: Int = BackupFormat.FORMAT_VERSION,
) : Closeable {
    private val zip = ZipOutputStream(BufferedOutputStream(output, BUFFER))
    private val digests = ArrayList<EntryDigest>()
    private val tableCounts = LinkedHashMap<String, Int>()
    private var fileCount = 0
    private var fileBytes = 0L

    private fun entry(path: String, level: Int, body: (OutputStream) -> Unit): EntryDigest {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        zip.setLevel(level)
        zip.putNextEntry(ZipEntry(path))
        val sink = object : OutputStream() {
            override fun write(b: Int) { md.update(b.toByte()); size++; zip.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); size += len; zip.write(b, off, len) }
        }
        body(sink)
        zip.closeEntry()
        return EntryDigest(path, size, hex(md.digest()))
    }

    /** [lines] must already be single-line JSON objects. Returns the number of rows written. */
    fun writeTable(table: String, lines: Sequence<String>): Int {
        require(table in BackupFormat.TABLES) { "unknown table" }
        var count = 0
        digests += entry(BackupFormat.tablePath(table), Deflater.DEFAULT_COMPRESSION) { out ->
            for (line in lines) {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                count++
            }
        }
        tableCounts[table] = count
        return count
    }

    fun writePrefs(prefs: JsonObject) {
        digests += entry(BackupFormat.PREFS, Deflater.DEFAULT_COMPRESSION) { it.write(prefs.toString().toByteArray(Charsets.UTF_8)) }
    }

    /** [relativePath] is e.g. `references/<id>.png`. Files are stored uncompressed (images are already compressed). */
    fun writeFile(relativePath: String, input: InputStream): Long {
        val name = BackupFormat.FILE_DIR + relativePath
        require(BackupFormat.isSafeEntryName(name)) { "unsafe file name" }
        val digest = entry(name, Deflater.NO_COMPRESSION) { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        digests += digest
        fileCount++
        fileBytes += digest.size
        return digest.size
    }

    fun finish(missingFiles: Int): BackupManifest {
        val listing = digests.joinToString("") { json.encodeToString(EntryDigest.serializer(), it) + "\n" }
        val entries = entry(BackupFormat.ENTRIES, Deflater.DEFAULT_COMPRESSION) { it.write(listing.toByteArray(Charsets.UTF_8)) }
        val manifest = BackupManifest(
            format = BackupFormat.FORMAT_NAME,
            formatVersion = formatVersion,
            appVersion = appVersion,
            schemaVersion = schemaVersion,
            createdAt = createdAt,
            tableCounts = tableCounts.toMap(),
            fileCount = fileCount,
            fileBytes = fileBytes,
            missingFiles = missingFiles,
            entriesSha256 = entries.sha256,
            entriesCount = digests.size,
        )
        entry(BackupFormat.MANIFEST, Deflater.DEFAULT_COMPRESSION) {
            it.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        }
        zip.finish()
        zip.flush()
        return manifest
    }

    override fun close() { zip.close() }
}

/** Receives the contents of a verified restore, in archive order. */
interface RestoreHandler {
    fun row(table: String, row: JsonObject)
    fun file(relativePath: String, content: InputStream)
    fun prefs(prefs: JsonObject)
}

sealed interface BackupScan {
    class Ok(val manifest: BackupManifest, val digests: Map<String, EntryDigest>) : BackupScan
    class Bad(val reason: InvalidReason, val detail: String? = null) : BackupScan
}

object BackupReader {
    private const val MAX_LINE_CHARS = 1_000_000
    private const val MAX_SMALL_ENTRY = 1_000_000L
    private const val MAX_ENTRIES_LISTING = 256L * 1024 * 1024
    private val COLUMN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    private class Corrupt(message: String) : IOException(message)

    /** Counts and hashes everything read through it, and never closes the underlying archive stream. */
    private class HashingStream(private val inner: InputStream) : InputStream() {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        var size = 0L
            private set
        override fun read(): Int = inner.read().also { if (it >= 0) { md.update(it.toByte()); size++ } }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) { md.update(b, off, it); size += it } }
        override fun close() = Unit
        fun drain() { val buffer = ByteArray(BUFFER); while (read(buffer) >= 0) Unit }
        fun digest(path: String) = EntryDigest(path, size, hex(md.digest()))
    }

    /**
     * Reads the whole archive once. With a null [handler] this only validates (hashes, structure, counts);
     * with a handler the contents are also delivered as they stream past. [onBytes] reports progress.
     */
    fun scan(
        input: InputStream,
        maxSchemaVersion: Int,
        handler: RestoreHandler? = null,
        checkCancelled: () -> Unit = {},
        onBytes: (Long) -> Unit = {},
    ): BackupScan {
        val actual = LinkedHashMap<String, EntryDigest>()
        val rowCounts = HashMap<String, Int>()
        var manifest: BackupManifest? = null
        var listing: List<EntryDigest>? = null
        var listingSha = ""
        var fileEntries = 0
        var entryCount = 0
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry: ZipEntry = zip.nextEntry ?: break
                    entryCount++
                    val name = entry.name
                    if (entry.isDirectory || !BackupFormat.isSafeEntryName(name)) {
                        return if (entryCount == 1) BackupScan.Bad(InvalidReason.NotABackup) else BackupScan.Bad(InvalidReason.UnsafeEntry, "unexpected entry")
                    }
                    val stream = HashingStream(zip)
                    when {
                        name == BackupFormat.MANIFEST -> manifest = try {
                            json.decodeFromString(BackupManifest.serializer(), readSmall(stream, MAX_SMALL_ENTRY))
                        } catch (_: SerializationException) { return BackupScan.Bad(InvalidReason.Corrupt, "manifest") }
                        name == BackupFormat.ENTRIES -> {
                            val text = readSmall(stream, MAX_ENTRIES_LISTING)
                            listingSha = hex(stream.md.digest())
                            listing = try {
                                text.lineSequence().filter { it.isNotBlank() }
                                    .map { json.decodeFromString(EntryDigest.serializer(), it) }.toList()
                            } catch (_: SerializationException) { return BackupScan.Bad(InvalidReason.Corrupt, "entries") }
                        }
                        name == BackupFormat.PREFS -> {
                            val obj = parseObject(readSmall(stream, MAX_SMALL_ENTRY))
                            handler?.prefs(obj)
                            actual[name] = stream.digest(name)
                        }
                        BackupFormat.isTableEntry(name) -> {
                            val table = BackupFormat.tableOf(name)!!
                            var rows = 0
                            val reader = stream.bufferedReader(Charsets.UTF_8)
                            while (true) {
                                checkCancelled()
                                val line = readLineLimited(reader) ?: break
                                if (line.isBlank()) continue
                                val row = parseObject(line)
                                if (row.keys.any { !COLUMN.matches(it) } || row.values.any { it is JsonObject || it is JsonArray }) {
                                    throw Corrupt("row shape")
                                }
                                handler?.row(table, row)
                                rows++
                            }
                            stream.drain()
                            rowCounts[table] = rows
                            actual[name] = stream.digest(name)
                        }
                        else -> { // a managed file
                            fileEntries++
                            handler?.file(name.removePrefix(BackupFormat.FILE_DIR), stream)
                            stream.drain()
                            actual[name] = stream.digest(name)
                        }
                    }
                    total += stream.size
                    onBytes(total)
                }
            }
        } catch (e: EOFException) {
            return BackupScan.Bad(InvalidReason.Incomplete, "archive ends early")
        } catch (e: ZipException) {
            return BackupScan.Bad(InvalidReason.Corrupt, "archive damaged")
        } catch (e: Corrupt) {
            return BackupScan.Bad(InvalidReason.Corrupt, e.message)
        } catch (e: IOException) {
            return BackupScan.Bad(InvalidReason.Unreadable, null)
        }

        if (entryCount == 0) return BackupScan.Bad(InvalidReason.NotABackup)
        val m = manifest ?: return BackupScan.Bad(InvalidReason.Incomplete, "no manifest")
        if (m.format != BackupFormat.FORMAT_NAME) return BackupScan.Bad(InvalidReason.NotABackup)
        if (m.formatVersion > BackupFormat.FORMAT_VERSION) return BackupScan.Bad(InvalidReason.NewerFormat)
        if (m.schemaVersion > maxSchemaVersion) return BackupScan.Bad(InvalidReason.NewerSchema)
        val declared = listing ?: return BackupScan.Bad(InvalidReason.Incomplete, "no entry listing")
        if (listingSha != m.entriesSha256 || declared.size != m.entriesCount) return BackupScan.Bad(InvalidReason.Mismatch, "entry listing")
        val declaredMap = declared.associateBy { it.path }
        if (declaredMap.size != declared.size || declaredMap != actual) return BackupScan.Bad(InvalidReason.Mismatch, "checksums")
        for (table in BackupFormat.TABLES) {
            if ((m.tableCounts[table] ?: 0) != (rowCounts[table] ?: 0)) return BackupScan.Bad(InvalidReason.Mismatch, "row count")
        }
        if (m.tableCounts.keys.any { it !in BackupFormat.TABLES } || m.fileCount != fileEntries) return BackupScan.Bad(InvalidReason.Mismatch, "counts")
        return BackupScan.Ok(m, actual)
    }

    private fun parseObject(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: throw Corrupt("not an object")
    } catch (e: SerializationException) {
        throw Corrupt("bad json")
    } catch (e: IllegalArgumentException) {
        throw Corrupt("bad json")
    }

    private fun readSmall(stream: InputStream, max: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw Corrupt("entry too large")
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** `readLine` with a cap, so a hostile file can't exhaust memory with one enormous line. */
    private fun readLineLimited(reader: java.io.Reader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
            if (sb.length > MAX_LINE_CHARS) throw Corrupt("line too long")
        }
    }
}
