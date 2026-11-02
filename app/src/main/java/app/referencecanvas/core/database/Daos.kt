package app.referencecanvas.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class BoardSummaryRow(
    @Embedded val board: BoardEntity,
    val referenceCount: Int,
)

/** Everything the canvas needs per card, in one lightweight row (no file bytes, no description text). */
data class CanvasCardRow(
    val referenceId: String,
    val positionX: Double,
    val positionY: Double,
    val width: Float,
    val height: Float,
    val zIndex: Int,
    val title: String,
    val mimeType: String,
    val localRelativePath: String?,
    val widthPx: Int?,
    val heightPx: Int?,
    val isFavorite: Boolean,
)

private const val SUMMARY_SELECT = """
SELECT b.*, (
    SELECT COUNT(*) FROM board_references br
    JOIN reference_items r ON r.id = br.referenceId
    WHERE br.boardId = b.id AND r.deletedAt IS NULL
) AS referenceCount
FROM boards b
"""

private const val BOARD_ITEMS = """
FROM reference_items r
JOIN board_references br ON br.referenceId = r.id
WHERE br.boardId = :boardId AND r.deletedAt IS NULL
"""

// One query serves every sort mode; non-matching CASE branches are NULL and fall through to the next key.
private const val BOARD_ITEMS_ORDER = """
ORDER BY
    CASE WHEN :sort = 'Manual' THEN br.sortOrder END ASC,
    CASE WHEN :sort = 'Name' THEN r.title END COLLATE NOCASE ASC,
    CASE WHEN :sort = 'DateAdded' THEN r.createdAt END DESC,
    CASE WHEN :sort = 'DateModified' THEN r.updatedAt END DESC,
    CASE WHEN :sort = 'FileType' THEN r.mimeType END ASC,
    r.title COLLATE NOCASE ASC, r.id ASC
"""

@Dao
interface BoardDao {
    @Query(
        """$SUMMARY_SELECT
        WHERE (:query = ''
            OR b.name LIKE '%' || :query || '%' ESCAPE '\'
            OR b.description LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY b.isPinned DESC, b.updatedAt DESC""",
    )
    fun observeSummaries(query: String): Flow<List<BoardSummaryRow>>

    @Query(
        """$SUMMARY_SELECT
        WHERE b.isFavorite = 1
        ORDER BY b.isPinned DESC, b.updatedAt DESC""",
    )
    fun observeFavoriteSummaries(): Flow<List<BoardSummaryRow>>

    @Query("SELECT * FROM boards WHERE id = :id")
    fun observeById(id: String): Flow<BoardEntity?>

    @Query("SELECT * FROM boards WHERE id = :id")
    suspend fun getById(id: String): BoardEntity?

    @Insert
    suspend fun insert(board: BoardEntity)

    @Query("UPDATE boards SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE boards SET isFavorite = :value WHERE id = :id")
    suspend fun setFavorite(id: String, value: Boolean)

    @Query("UPDATE boards SET isPinned = :value WHERE id = :id")
    suspend fun setPinned(id: String, value: Boolean)

    @Query("UPDATE boards SET viewMode = :viewMode WHERE id = :id")
    suspend fun setViewMode(id: String, viewMode: String)

    @Query(
        """$SUMMARY_SELECT
        WHERE ((:folderId IS NULL AND b.folderId IS NULL) OR b.folderId = :folderId)
        ORDER BY b.isPinned DESC, b.updatedAt DESC""",
    )
    fun observeSummariesInFolder(folderId: String?): Flow<List<BoardSummaryRow>>

    @Query("UPDATE boards SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    @Query("UPDATE boards SET sortMode = :sortMode WHERE id = :id")
    suspend fun setSortMode(id: String, sortMode: String)

    @Query("UPDATE boards SET canvasOffsetX = :x, canvasOffsetY = :y, canvasZoom = :zoom WHERE id = :id")
    suspend fun setViewport(id: String, x: Double, y: Double, zoom: Float)

    @Query("DELETE FROM boards WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ReferenceDao {
    @Insert
    suspend fun insert(reference: ReferenceEntity)

    @Query("SELECT * FROM reference_items WHERE id = :id")
    suspend fun getById(id: String): ReferenceEntity?

    @Query("SELECT * FROM reference_items WHERE id = :id")
    fun observeById(id: String): Flow<ReferenceEntity?>

    @Query("UPDATE reference_items SET isFavorite = :value WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, value: Boolean)

    @Query("UPDATE reference_items SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun setFolder(ids: List<String>, folderId: String?)

    @Query("UPDATE reference_items SET lastOpenedAt = :now WHERE id = :id")
    suspend fun setLastOpened(id: String, now: Long)

    @Query("SELECT localRelativePath FROM reference_items WHERE id IN (:ids) AND localRelativePath IS NOT NULL")
    suspend fun relativePathsFor(ids: List<String>): List<String>

    @Query("UPDATE reference_items SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("DELETE FROM reference_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM reference_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    // ---- trash and storage accounting
    @Query("UPDATE reference_items SET deletedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun setDeletedAtMany(ids: List<String>, deletedAt: Long?)

    @Query("SELECT * FROM reference_items WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id ASC")
    fun trashedPaging(): PagingSource<Int, ReferenceEntity>

    @Query("SELECT COUNT(*) FROM reference_items WHERE deletedAt IS NOT NULL")
    fun observeTrashCount(): Flow<Int>

    @Query("SELECT id FROM reference_items WHERE deletedAt IS NOT NULL")
    suspend fun allTrashedIds(): List<String>

    @Query("SELECT id FROM reference_items WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun expiredTrashedIds(before: Long): List<String>

    @Query("SELECT localRelativePath FROM reference_items WHERE localRelativePath IS NOT NULL")
    suspend fun allRelativePaths(): List<String>

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM reference_items")
    suspend fun totalBytes(): Long

    @Query("SELECT COUNT(*) FROM reference_items")
    suspend fun totalCount(): Int
}

@Dao
interface BoardReferenceDao {
    /** Returns -1 when the reference is already on the board. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(membership: BoardReferenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<BoardReferenceEntity>): List<Long>

    @Query("SELECT r.* $BOARD_ITEMS $BOARD_ITEMS_ORDER")
    fun pagingSource(boardId: String, sort: String): PagingSource<Int, ReferenceEntity>

    /** Ids only (a few dozen bytes each); the viewer uses them to page left/right. */
    @Query("SELECT r.id $BOARD_ITEMS $BOARD_ITEMS_ORDER")
    suspend fun orderedIds(boardId: String, sort: String): List<String>

    @Query(
        """SELECT br.referenceId AS referenceId, br.positionX AS positionX, br.positionY AS positionY,
            br.width AS width, br.height AS height, br.zIndex AS zIndex,
            r.title AS title, r.mimeType AS mimeType, r.localRelativePath AS localRelativePath,
            r.widthPx AS widthPx, r.heightPx AS heightPx, r.isFavorite AS isFavorite
        FROM board_references br
        JOIN reference_items r ON r.id = br.referenceId
        WHERE br.boardId = :boardId AND r.deletedAt IS NULL""",
    )
    fun observeCanvasCards(boardId: String): Flow<List<CanvasCardRow>>

    @Query(
        """UPDATE board_references SET positionX = :x, positionY = :y, zIndex = :z
        WHERE boardId = :boardId AND referenceId = :referenceId""",
    )
    suspend fun setLayout(boardId: String, referenceId: String, x: Double, y: Double, z: Int)

    @Query("SELECT * FROM board_references WHERE boardId = :boardId AND referenceId = :referenceId")
    suspend fun getMembership(boardId: String, referenceId: String): BoardReferenceEntity?

    @Query("SELECT COALESCE(MAX(zIndex), 0) FROM board_references WHERE boardId = :boardId")
    suspend fun maxZ(boardId: String): Int

    @Query("SELECT COUNT(*) FROM board_references WHERE boardId = :boardId")
    suspend fun countForBoard(boardId: String): Int

    @Query("DELETE FROM board_references WHERE boardId = :boardId AND referenceId = :referenceId")
    suspend fun remove(boardId: String, referenceId: String)

    @Query("DELETE FROM board_references WHERE boardId = :boardId AND referenceId IN (:referenceIds)")
    suspend fun removeMany(boardId: String, referenceIds: List<String>)

    /** Copies membership and layout in SQL so large boards are never loaded into memory. */
    @Query(
        """INSERT INTO board_references
            (boardId, referenceId, positionX, positionY, zIndex, width, height, sortOrder, label)
        SELECT :targetBoardId, referenceId, positionX, positionY, zIndex, width, height, sortOrder, label
        FROM board_references WHERE boardId = :sourceBoardId""",
    )
    suspend fun copyMembership(sourceBoardId: String, targetBoardId: String)
}
