package app.referencecanvas.data.repository

import app.referencecanvas.core.database.BoardDao
import app.referencecanvas.core.database.BoardEntity
import app.referencecanvas.core.database.BoardReferenceDao
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import app.referencecanvas.core.util.escapeLike
import app.referencecanvas.domain.model.Board
import app.referencecanvas.domain.model.BoardNames
import app.referencecanvas.domain.model.BoardSortMode
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.domain.model.BoardViewMode
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface BoardRepository {
    fun observeBoards(query: String = ""): Flow<List<BoardSummary>>
    fun observeFavorites(): Flow<List<BoardSummary>>

    /** Boards directly inside [folderId] (null = top level). */
    fun observeInFolder(folderId: String?): Flow<List<BoardSummary>>
    fun observeBoard(id: String): Flow<Board?>

    /** Returns the new board's id, or null if [name] is blank. */
    suspend fun create(name: String, viewMode: BoardViewMode): String?

    /** Returns false if [name] is blank or the board does not exist. */
    suspend fun rename(id: String, name: String): Boolean
    suspend fun setFavorite(id: String, value: Boolean)
    suspend fun setPinned(id: String, value: Boolean)
    suspend fun setViewMode(id: String, mode: BoardViewMode)
    suspend fun setSortMode(id: String, mode: BoardSortMode)
    suspend fun moveToFolder(id: String, folderId: String?)

    /** Copies the board and its layout. References are shared, not duplicated. */
    suspend fun duplicate(id: String): String?

    /** Deletes the board only. References stay in the library. */
    suspend fun delete(id: String)
}

class BoardRepositoryImpl @Inject constructor(
    private val boardDao: BoardDao,
    private val boardReferenceDao: BoardReferenceDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
) : BoardRepository {

    override fun observeBoards(query: String): Flow<List<BoardSummary>> =
        boardDao.observeSummaries(escapeLike(query.trim())).map { rows -> rows.map { it.toDomain() } }

    override fun observeFavorites(): Flow<List<BoardSummary>> =
        boardDao.observeFavoriteSummaries().map { rows -> rows.map { it.toDomain() } }

    override fun observeInFolder(folderId: String?): Flow<List<BoardSummary>> =
        boardDao.observeSummariesInFolder(folderId).map { rows -> rows.map { it.toDomain() } }

    override fun observeBoard(id: String): Flow<Board?> =
        boardDao.observeById(id).map { it?.toDomain() }

    override suspend fun create(name: String, viewMode: BoardViewMode): String? {
        val clean = BoardNames.normalize(name) ?: return null
        val now = clock.now()
        val id = ids.newId()
        boardDao.insert(
            BoardEntity(id = id, name = clean, createdAt = now, updatedAt = now, viewMode = viewMode.name),
        )
        return id
    }

    override suspend fun rename(id: String, name: String): Boolean {
        val clean = BoardNames.normalize(name) ?: return false
        if (boardDao.getById(id) == null) return false
        boardDao.rename(id, clean, clock.now())
        return true
    }

    override suspend fun setFavorite(id: String, value: Boolean) = boardDao.setFavorite(id, value)

    override suspend fun setPinned(id: String, value: Boolean) = boardDao.setPinned(id, value)

    override suspend fun setViewMode(id: String, mode: BoardViewMode) =
        boardDao.setViewMode(id, mode.name)

    override suspend fun setSortMode(id: String, mode: BoardSortMode) =
        boardDao.setSortMode(id, mode.name)

    override suspend fun moveToFolder(id: String, folderId: String?) = boardDao.setFolder(id, folderId)

    override suspend fun duplicate(id: String): String? = transactions.run {
        val source = boardDao.getById(id) ?: return@run null
        val now = clock.now()
        val newId = ids.newId()
        boardDao.insert(
            source.copy(
                id = newId,
                name = BoardNames.copyName(source.name),
                createdAt = now,
                updatedAt = now,
                isFavorite = false,
                isPinned = false,
            ),
        )
        boardReferenceDao.copyMembership(sourceBoardId = id, targetBoardId = newId)
        newId
    }

    override suspend fun delete(id: String) = boardDao.deleteById(id)
}
