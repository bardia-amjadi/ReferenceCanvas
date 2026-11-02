package app.referencecanvas.core.filesystem

import android.content.Context
import android.database.sqlite.SQLiteException
import app.referencecanvas.core.database.ReferenceDao
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.DiagnosticsLog
import coil.imageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageUsage(
    val referenceCount: Int,
    val referenceBytes: Long,
    val cacheBytes: Long,
    val deviceFreeBytes: Long,
)

data class CleanupResult(val deletedFiles: Int, val freedBytes: Long, val keptRecent: Int)

@Singleton
class StorageMaintenance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileStore: FileStore,
    private val referenceDao: ReferenceDao,
    private val diagnostics: DiagnosticsLog,
    private val clock: Clock,
) {
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        StorageUsage(
            referenceCount = referenceDao.totalCount(),
            referenceBytes = referenceDao.totalBytes(),
            cacheBytes = sizeOf(context.cacheDir),
            deviceFreeBytes = fileStore.usableBytes(),
        )
    }

    /** Empties temporary files (camera captures, exports, decoded-image memory). Never touches library data. */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        context.imageLoader.memoryCache?.clear()
        val before = sizeOf(context.cacheDir)
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        val freed = (before - sizeOf(context.cacheDir)).coerceAtLeast(0)
        diagnostics.record("maintenance", "cache cleared bytes=$freed")
        freed
    }

    /**
     * Removes files in managed storage that no database record points to (left over from interrupted operations).
     * Safety rules: if the list of known files can't be read, nothing is deleted; and files newer than [graceMillis]
     * are kept, so an import that has copied its file but not yet saved its record is never touched.
     * Returns null when the cleanup was skipped for safety.
     */
    suspend fun cleanOrphans(graceMillis: Long = ONE_HOUR_MS): CleanupResult? = withContext(Dispatchers.IO) {
        val known: Set<String> = try {
            referenceDao.allRelativePaths().toHashSet()
        } catch (_: SQLiteException) {
            diagnostics.record("maintenance", "orphan cleanup skipped: database unavailable")
            return@withContext null
        }
        val cutoff = clock.now() - graceMillis
        var deleted = 0
        var freed = 0L
        var recent = 0
        fileStore.referencesDir().listFiles()?.forEach { file ->
            if (!file.isFile || "references/${file.name}" in known) return@forEach
            if (file.lastModified() > cutoff) { recent++; return@forEach }
            val size = file.length()
            if (file.delete()) { deleted++; freed += size }
        }
        diagnostics.record("maintenance", "orphan cleanup deleted=$deleted bytes=$freed keptRecent=$recent")
        CleanupResult(deleted, freed, recent)
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val ONE_HOUR_MS = 60L * 60 * 1000
    }
}
