package app.referencecanvas.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.referencecanvas.core.database.BoardReferenceDao
import app.referencecanvas.core.database.BoardReferenceEntity
import app.referencecanvas.core.database.ReferenceDao
import app.referencecanvas.core.database.ReferenceEntity
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.filesystem.FileStore
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import app.referencecanvas.domain.model.LinkParser
import app.referencecanvas.core.util.toEnumOr
import app.referencecanvas.domain.model.BoardSortMode
import app.referencecanvas.domain.model.Reference
import app.referencecanvas.domain.model.StorageMode
import app.referencecanvas.domain.model.defaultCardPosition
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ReferenceRepository {
    /** Bounded, paged view of a board; never loads the whole board into memory. */
    fun pagedForBoard(boardId: String, sort: BoardSortMode): Flow<PagingData<Reference>>
    suspend fun orderedIds(boardId: String, sort: BoardSortMode): List<String>
    fun observe(id: String): Flow<Reference?>
    suspend fun setFavorite(ids: Collection<String>, value: Boolean)
    suspend fun markOpened(id: String)
    suspend fun setFolder(ids: Collection<String>, folderId: String?)

    /** Adds a web link as a reference. Stores text only; nothing is fetched. False if [rawUrl] isn't an http(s) link. */
    suspend fun addLink(boardId: String, rawUrl: String): Boolean
    suspend fun addToBoard(boardId: String, ids: Collection<String>)
    suspend fun removeFromBoard(boardId: String, ids: Collection<String>)

    // ---- recoverable deletion
    fun observeTrash(): Flow<PagingData<Reference>>
    fun observeTrashCount(): Flow<Int>
    suspend fun moveToTrash(ids: Collection<String>)
    suspend fun restoreFromTrash(ids: Collection<String>)
    suspend fun trashedIds(): List<String>

    /** Permanently deletes everything in the trash; returns how many items. */
    suspend fun emptyTrash(): Int

    /** Permanently deletes items that have been in the trash longer than [retentionDays]. */
    suspend fun purgeExpired(retentionDays: Int): Int

    /** Permanently deletes references (every board) and their managed files. */
    suspend fun deletePermanently(ids: Collection<String>)
    fun fileFor(reference: Reference): File?
}

class ReferenceRepositoryImpl @Inject constructor(
    private val referenceDao: ReferenceDao,
    private val boardReferenceDao: BoardReferenceDao,
    private val fileStore: FileStore,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) : ReferenceRepository {

    override fun pagedForBoard(boardId: String, sort: BoardSortMode): Flow<PagingData<Reference>> =
        Pager(PagingConfig(pageSize = 60, initialLoadSize = 120, enablePlaceholders = false)) {
            boardReferenceDao.pagingSource(boardId, sort.name)
        }.flow.map { page -> page.map { it.toDomain() } }

    override suspend fun orderedIds(boardId: String, sort: BoardSortMode) =
        boardReferenceDao.orderedIds(boardId, sort.name)

    override fun observe(id: String): Flow<Reference?> = referenceDao.observeById(id).map { it?.toDomain() }

    override suspend fun setFavorite(ids: Collection<String>, value: Boolean) {
        transactions.run { ids.chunked(CHUNK).forEach { referenceDao.setFavorite(it, value) } }
    }

    override suspend fun markOpened(id: String) = referenceDao.setLastOpened(id, clock.now())

    override suspend fun setFolder(ids: Collection<String>, folderId: String?) {
        transactions.run { ids.chunked(CHUNK).forEach { referenceDao.setFolder(it, folderId) } }
    }

    override suspend fun addLink(boardId: String, rawUrl: String): Boolean {
        val url = LinkParser.parse(rawUrl) ?: return false
        val id = idGenerator.newId()
        val now = clock.now()
        transactions.run {
            val index = boardReferenceDao.countForBoard(boardId)
            val (x, y) = defaultCardPosition(index)
            referenceDao.insert(
                ReferenceEntity(
                    id = id,
                    title = LinkParser.hostOf(url).ifEmpty { url },
                    originalFileName = url,
                    mimeType = "text/x-uri",
                    createdAt = now,
                    updatedAt = now,
                    storageMode = "LINK",
                    sourceUri = url,
                ),
            )
            boardReferenceDao.insert(
                BoardReferenceEntity(
                    boardId = boardId, referenceId = id, positionX = x, positionY = y,
                    zIndex = index, sortOrder = index.toLong(),
                ),
            )
        }
        return true
    }

    override suspend fun addToBoard(boardId: String, ids: Collection<String>) {
        transactions.run {
            val start = boardReferenceDao.countForBoard(boardId)
            boardReferenceDao.insertAll(
                ids.mapIndexed { i, id ->
                    val (x, y) = defaultCardPosition(start + i)
                    BoardReferenceEntity(
                        boardId = boardId, referenceId = id, positionX = x, positionY = y,
                        zIndex = start + i, sortOrder = (start + i).toLong(),
                    )
                },
            )
        }
    }

    override suspend fun removeFromBoard(boardId: String, ids: Collection<String>) {
        transactions.run { ids.chunked(CHUNK).forEach { boardReferenceDao.removeMany(boardId, it) } }
    }

    override fun observeTrash(): Flow<PagingData<Reference>> =
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) { referenceDao.trashedPaging() }
            .flow.map { page -> page.map { it.toDomain() } }

    override fun observeTrashCount(): Flow<Int> = referenceDao.observeTrashCount()

    override suspend fun moveToTrash(ids: Collection<String>) {
        val now = clock.now()
        transactions.run { ids.chunked(CHUNK).forEach { referenceDao.setDeletedAtMany(it, now) } }
    }

    override suspend fun restoreFromTrash(ids: Collection<String>) {
        transactions.run { ids.chunked(CHUNK).forEach { referenceDao.setDeletedAtMany(it, null) } }
    }

    override suspend fun trashedIds(): List<String> = referenceDao.allTrashedIds()

    override suspend fun emptyTrash(): Int {
        val ids = referenceDao.allTrashedIds()
        if (ids.isNotEmpty()) deletePermanently(ids)
        return ids.size
    }

    override suspend fun purgeExpired(retentionDays: Int): Int {
        val ids = referenceDao.expiredTrashedIds(clock.now() - retentionDays * DAY_MS)
        if (ids.isNotEmpty()) deletePermanently(ids)
        return ids.size
    }

    override suspend fun deletePermanently(ids: Collection<String>) {
        // Database first (atomic); files only after the commit succeeded, so a failure never loses both.
        val paths = transactions.run {
            val found = ids.chunked(CHUNK).flatMap { referenceDao.relativePathsFor(it) }
            ids.chunked(CHUNK).forEach { referenceDao.deleteByIds(it) }
            found
        }
        paths.forEach { fileStore.delete(it) }
    }

    override fun fileFor(reference: Reference): File? =
        reference.localRelativePath?.let { fileStore.resolve(it) }

    private companion object {
        const val CHUNK = 500 // stays under SQLite's bound-variable limit
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

internal fun ReferenceEntity.toDomain() = Reference(
    id = id,
    title = title,
    description = description,
    originalFileName = originalFileName,
    mimeType = mimeType,
    storageMode = storageMode.toEnumOr(StorageMode.MANAGED),
    localRelativePath = localRelativePath,
    sourceUri = sourceUri,
    fileSizeBytes = fileSizeBytes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isFavorite = isFavorite,
    widthPx = widthPx,
    heightPx = heightPx,
)
