package app.referencecanvas.data.importer

import android.content.Context
import android.database.sqlite.SQLiteException
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.referencecanvas.core.database.BoardReferenceDao
import app.referencecanvas.core.database.BoardReferenceEntity
import app.referencecanvas.core.database.ReferenceDao
import app.referencecanvas.core.database.ReferenceEntity
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.filesystem.FileStore
import app.referencecanvas.core.filesystem.StagedFile
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import app.referencecanvas.core.util.sanitizeExtension
import app.referencecanvas.core.util.titleFromFileName
import app.referencecanvas.domain.model.defaultCardPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class ImportFailureReason { NoSpace, PermissionDenied, NotFound, Unreadable, Io, Database, Unknown }

data class ImportFailure(val uri: Uri, val name: String, val reason: ImportFailureReason)

data class ImportReport(val imported: Int, val failures: List<ImportFailure>)

sealed interface ImportEvent {
    data class Progress(val done: Int, val total: Int) : ImportEvent
    data class Finished(val report: ImportReport) : ImportEvent
}

interface ReferenceImporter {
    /** Copies each [uris] entry into managed storage and adds it to [boardId]. Cancelling the collector stops cleanly. */
    fun import(boardId: String, uris: List<Uri>): Flow<ImportEvent>
}

class ReferenceImporterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileStore: FileStore,
    private val referenceDao: ReferenceDao,
    private val boardReferenceDao: BoardReferenceDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
) : ReferenceImporter {

    private sealed interface Outcome {
        data object Ok : Outcome
        data class Failed(val failure: ImportFailure) : Outcome
    }

    private data class Meta(val name: String, val size: Long?, val mime: String)

    override fun import(boardId: String, uris: List<Uri>): Flow<ImportEvent> = flow {
        var imported = 0
        val failures = mutableListOf<ImportFailure>()
        uris.forEachIndexed { index, uri ->
            emit(ImportEvent.Progress(index, uris.size))
            when (val outcome = importOne(boardId, uri)) {
                Outcome.Ok -> imported++
                is Outcome.Failed -> failures += outcome.failure
            }
        }
        emit(ImportEvent.Progress(uris.size, uris.size))
        emit(ImportEvent.Finished(ImportReport(imported, failures)))
    }.flowOn(Dispatchers.IO)

    private suspend fun importOne(boardId: String, uri: Uri): Outcome {
        val meta = readMeta(uri)
        fun failed(reason: ImportFailureReason) = Outcome.Failed(ImportFailure(uri, meta.name, reason))

        var staged: StagedFile? = null
        var committedPath: String? = null
        var recorded = false
        try {
            if (meta.size != null && meta.size + SPACE_MARGIN_BYTES > fileStore.usableBytes()) {
                return failed(ImportFailureReason.NoSpace)
            }
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (_: SecurityException) {
                return failed(ImportFailureReason.PermissionDenied)
            } catch (_: FileNotFoundException) {
                return failed(ImportFailureReason.NotFound)
            } ?: return failed(ImportFailureReason.Unreadable)

            val stagedFile = input.use { fileStore.stage(it) }
            staged = stagedFile
            val dims = if (meta.mime.startsWith("image/")) imageBounds(stagedFile.file) else null

            val id = ids.newId()
            val relative = fileStore.relativePathFor(id, sanitizeExtension(meta.name))
            fileStore.commit(stagedFile.file, relative)
            committedPath = relative
            staged = null

            val now = clock.now()
            transactions.run {
                val index = boardReferenceDao.countForBoard(boardId)
                val (x, y) = defaultCardPosition(index)
                referenceDao.insert(
                    ReferenceEntity(
                        id = id,
                        title = titleFromFileName(meta.name),
                        originalFileName = meta.name,
                        mimeType = meta.mime,
                        createdAt = now,
                        updatedAt = now,
                        storageMode = "MANAGED",
                        localRelativePath = relative,
                        fileSizeBytes = stagedFile.sizeBytes,
                        widthPx = dims?.first,
                        heightPx = dims?.second,
                        checksum = stagedFile.sha256,
                    ),
                )
                boardReferenceDao.insert(
                    BoardReferenceEntity(
                        boardId = boardId,
                        referenceId = id,
                        positionX = x,
                        positionY = y,
                        zIndex = index,
                        sortOrder = index.toLong(),
                    ),
                )
            }
            recorded = true
            return Outcome.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteException) {
            return failed(ImportFailureReason.Database)
        } catch (e: SecurityException) {
            return failed(ImportFailureReason.PermissionDenied)
        } catch (e: IOException) {
            val full = e.message?.contains("ENOSPC") == true
            return failed(if (full) ImportFailureReason.NoSpace else ImportFailureReason.Io)
        } catch (e: RuntimeException) {
            return failed(ImportFailureReason.Unknown)
        } finally {
            withContext(NonCancellable) {
                staged?.file?.delete()
                if (!recorded) committedPath?.let { fileStore.delete(it) } // no orphan files without a record
                deleteOwnCapture(uri)
            }
        }
    }

    private fun readMeta(uri: Uri): Meta {
        var name: String? = null
        var size: Long? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            } catch (_: SecurityException) {
                // Metadata is best-effort; opening the stream reports the real permission error.
            } catch (_: IllegalArgumentException) {
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
        val mime = context.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(sanitizeExtension(resolvedName))
            ?: "application/octet-stream"
        return Meta(resolvedName, size, mime)
    }

    /** Reads only the image header; never decodes pixels. */
    private fun imageBounds(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    /** Camera captures are temp files owned by this app; remove them once copied (or failed). */
    private fun deleteOwnCapture(uri: Uri) {
        if (uri.authority == "${context.packageName}.files") {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private companion object {
        const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}
