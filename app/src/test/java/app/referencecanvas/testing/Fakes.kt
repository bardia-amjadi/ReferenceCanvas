package app.referencecanvas.testing

import app.referencecanvas.core.database.BoardDao
import androidx.paging.PagingSource
import app.referencecanvas.core.database.BoardEntity
import app.referencecanvas.core.database.BoardReferenceDao
import app.referencecanvas.core.database.BoardReferenceEntity
import app.referencecanvas.core.database.BoardSummaryRow
import app.referencecanvas.core.database.CanvasCardRow
import app.referencecanvas.core.database.ReferenceEntity
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeBoardDao : BoardDao {
    val boards = linkedMapOf<String, BoardEntity>()

    override fun observeSummaries(query: String): Flow<List<BoardSummaryRow>> = flowOf(emptyList())
    override fun observeFavoriteSummaries(): Flow<List<BoardSummaryRow>> = flowOf(emptyList())
    override fun observeSummariesInFolder(folderId: String?): Flow<List<BoardSummaryRow>> = flowOf(emptyList())
    override suspend fun setFolder(id: String, folderId: String?) {
        boards[id]?.let { boards[id] = it.copy(folderId = folderId) }
    }
    override fun observeById(id: String): Flow<BoardEntity?> = flowOf(boards[id])
    override suspend fun getById(id: String): BoardEntity? = boards[id]
    override suspend fun insert(board: BoardEntity) { boards[board.id] = board }
    override suspend fun rename(id: String, name: String, now: Long) {
        boards[id]?.let { boards[id] = it.copy(name = name, updatedAt = now) }
    }
    override suspend fun setFavorite(id: String, value: Boolean) {
        boards[id]?.let { boards[id] = it.copy(isFavorite = value) }
    }
    override suspend fun setPinned(id: String, value: Boolean) {
        boards[id]?.let { boards[id] = it.copy(isPinned = value) }
    }
    override suspend fun setViewport(id: String, x: Double, y: Double, zoom: Float) {
        boards[id]?.let { boards[id] = it.copy(canvasOffsetX = x, canvasOffsetY = y, canvasZoom = zoom) }
    }
    override suspend fun setSortMode(id: String, sortMode: String) {
        boards[id]?.let { boards[id] = it.copy(sortMode = sortMode) }
    }
    override suspend fun setViewMode(id: String, viewMode: String) {
        boards[id]?.let { boards[id] = it.copy(viewMode = viewMode) }
    }
    override suspend fun deleteById(id: String) { boards.remove(id) }
}

class FakeBoardReferenceDao : BoardReferenceDao {
    val copies = mutableListOf<Pair<String, String>>()
    override suspend fun insert(membership: BoardReferenceEntity): Long = 1L
    override suspend fun insertAll(items: List<BoardReferenceEntity>): List<Long> = items.map { 1L }
    override suspend fun removeMany(boardId: String, referenceIds: List<String>) = Unit
    override fun pagingSource(boardId: String, sort: String): PagingSource<Int, ReferenceEntity> =
        error("not used in unit tests")
    override suspend fun orderedIds(boardId: String, sort: String): List<String> = emptyList()
    override fun observeCanvasCards(boardId: String): Flow<List<CanvasCardRow>> = flowOf(emptyList())
    override suspend fun setLayout(boardId: String, referenceId: String, x: Double, y: Double, z: Int) = Unit
    override suspend fun getMembership(boardId: String, referenceId: String): BoardReferenceEntity? = null
    override suspend fun maxZ(boardId: String): Int = 0
    override suspend fun countForBoard(boardId: String): Int = 0
    override suspend fun remove(boardId: String, referenceId: String) = Unit
    override suspend fun copyMembership(sourceBoardId: String, targetBoardId: String) {
        copies += sourceBoardId to targetBoardId
    }
}

object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class FixedClock(var time: Long = 1_000L) : Clock {
    override fun now(): Long = time
}

class SequentialIds : IdGenerator {
    private var n = 0
    override fun newId(): String = "id-${++n}"
}
