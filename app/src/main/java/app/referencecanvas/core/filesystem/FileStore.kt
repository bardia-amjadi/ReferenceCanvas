package app.referencecanvas.core.filesystem

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class StagedFile(val file: File, val sizeBytes: Long, val sha256: String)

/**
 * App-private storage for managed reference files.
 *
 * Imports are streamed into `staging/`, then atomically moved into `references/`.
 * Anything left in staging after a crash is discarded on next launch.
 */
@Singleton
class FileStore(private val baseDir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.filesDir)

    private val stagingDir get() = File(baseDir, "staging")

    /** Storage name is derived from the reference id, never from the user's file name. */
    fun relativePathFor(id: String, extension: String) = "references/$id.$extension"

    /** Resolves a stored relative path, refusing anything that escapes the app directory. */
    fun resolve(relative: String): File? {
        val root = baseDir.canonicalFile
        val file = File(baseDir, relative).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    fun usableBytes(): Long = baseDir.usableSpace

    /** The directory holding managed reference files. */
    fun referencesDir(): File = File(baseDir, "references")

    /** Streams [input] to a staging file in 64 KiB chunks (never whole-file in memory) and hashes it. */
    suspend fun stage(input: InputStream): StagedFile = withContext(Dispatchers.IO) {
        stageBlocking(input) { ensureActive() }
    }

    /**
     * Blocking form of [stage] for callers already on a background thread (backup restore).
     * [checkCancelled] runs between chunks and may throw to abort; the partial file is always removed.
     */
    fun stageBlocking(input: InputStream, checkCancelled: () -> Unit = {}): StagedFile {
        stagingDir.mkdirs()
        val target = File.createTempFile("import-", ".part", stagingDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            java.io.FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    checkCancelled()
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    total += n
                }
                out.flush()
                out.fd.sync()
            }
            return StagedFile(target, total, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (t: Throwable) {
            target.delete() // never leave a partial file behind, including on cancellation
            throw t
        }
    }

    /** Moves a staged file into managed storage. */
    fun commit(staged: File, relative: String) {
        val dest = resolve(relative) ?: throw IOException("Invalid storage path")
        dest.parentFile?.mkdirs()
        try {
            Files.move(staged.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(relative: String): Boolean = resolve(relative)?.delete() ?: false

    fun clearStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }
}
