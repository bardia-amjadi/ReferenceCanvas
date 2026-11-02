package app.referencecanvas.data.repository

import android.database.sqlite.SQLiteException
import app.referencecanvas.core.database.AnnotationDao
import app.referencecanvas.core.database.AnnotationEntity
import app.referencecanvas.core.database.BoardDao
import app.referencecanvas.core.database.BoardReferenceDao
import app.referencecanvas.core.database.BoardReferenceEntity
import app.referencecanvas.core.database.CanvasCardRow
import app.referencecanvas.core.database.ReferenceDao
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.filesystem.FileStore
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import app.referencecanvas.core.util.sanitizeExtension
import app.referencecanvas.domain.canvas.CanvasCard
import app.referencecanvas.domain.canvas.CanvasViewport
import app.referencecanvas.domain.canvas.CardLayout
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface CanvasRepository {
    fun observeCards(boardId: String): Flow<List<CanvasCard>>

    /** Writes positions and z-order for many cards in one transaction. */
    suspend fun applyLayout(boardId: String, layouts: List<CardLayout>)
    suspend fun saveViewport(boardId: String, viewport: CanvasViewport)

    /** Copies each reference (file included) onto the same board. Returns the ids actually created. */
    suspend fun duplicate(boardId: String, ids: Collection<String>): List<String>
    fun fileFor(relativePath: String?): File?
}

class CanvasRepositoryImpl @Inject constructor(
    private val boardDao: BoardDao,
    private val referenceDao: ReferenceDao,
    private val boardReferenceDao: BoardReferenceDao,
    private val annotationDao: AnnotationDao,
    private val fileStore: FileStore,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) : CanvasRepository {

    override fun observeCards(boardId: String): Flow<List<CanvasCard>> =
        boardReferenceDao.observeCanvasCards(boardId).map { rows -> rows.map { it.toCard() } }

    override suspend fun applyLayout(boardId: String, layouts: List<CardLayout>) {
        transactions.run {
            layouts.forEach { boardReferenceDao.setLayout(boardId, it.id, it.x, it.y, it.z) }
        }
    }

    override suspend fun saveViewport(boardId: String, viewport: CanvasViewport) {
        boardDao.setViewport(boardId, viewport.offsetX, viewport.offsetY, viewport.zoom)
    }

    override suspend fun duplicate(boardId: String, ids: Collection<String>): List<String> {
        val created = mutableListOf<String>()
        var z = boardReferenceDao.maxZ(boardId)
        for (id in ids) {
            val source = referenceDao.getById(id) ?: continue
            val membership = boardReferenceDao.getMembership(boardId, id) ?: continue
            val sourceFile = source.localRelativePath?.let { fileStore.resolve(it) }
            if (sourceFile == null || !sourceFile.exists()) continue

            val newId = idGenerator.newId()
            val relative = fileStore.relativePathFor(newId, sanitizeExtension(source.originalFileName))
            var copied = false
            var recorded = false
            try {
                val staged = sourceFile.inputStream().use { fileStore.stage(it) }
                fileStore.commit(staged.file, relative)
                copied = true
                val now = clock.now()
                z += 1
                transactions.run {
                    referenceDao.insert(
                        source.copy(
                            id = newId,
                            title = source.title + " copy",
                            createdAt = now,
                            updatedAt = now,
                            localRelativePath = relative,
                            checksum = staged.sha256,
                            isFavorite = false,
                            lastOpenedAt = null,
                            deletedAt = null,
                        ),
                    )
                    // The copy keeps its own, independent set of annotations.
                    annotationDao.insertAll(
                        annotationDao.getFor(id).map { it.copy(id = idGenerator.newId(), referenceId = newId, createdAt = now, updatedAt = now) },
                    )
                    boardReferenceDao.insert(
                        BoardReferenceEntity(
                            boardId = boardId,
                            referenceId = newId,
                            positionX = membership.positionX + DUPLICATE_OFFSET,
                            positionY = membership.positionY + DUPLICATE_OFFSET,
                            zIndex = z,
                            width = membership.width,
                            height = membership.height,
                            sortOrder = boardReferenceDao.countForBoard(boardId).toLong(),
                        ),
                    )
                }
                recorded = true
                created += newId
            } catch (e: IOException) {
                // This item is skipped; the caller reports how many were created.
            } catch (e: SQLiteException) {
            } finally {
                if (copied && !recorded) fileStore.delete(relative) // never leave a file without a record
            }
        }
        return created
    }

    override fun fileFor(relativePath: String?): File? = relativePath?.let { fileStore.resolve(it) }

    private companion object {
        const val DUPLICATE_OFFSET = 40.0
    }
}

/** Cards keep their stored size; legacy square defaults are fitted to the image's aspect ratio for display. */
internal fun CanvasCardRow.toCard(): CanvasCard {
    var w = width.toDouble()
    var h = height.toDouble()
    val iw = widthPx
    val ih = heightPx
    if (w == DEFAULT_SIZE && h == DEFAULT_SIZE && iw != null && ih != null && iw > 0 && ih > 0) {
        val scale = DEFAULT_SIZE / max(iw, ih)
        w = iw * scale
        h = ih * scale
    }
    return CanvasCard(
        id = referenceId, title = title, mimeType = mimeType, relativePath = localRelativePath,
        x = positionX, y = positionY, w = w, h = h, z = zIndex, isFavorite = isFavorite,
    )
}

private const val DEFAULT_SIZE = 240.0
