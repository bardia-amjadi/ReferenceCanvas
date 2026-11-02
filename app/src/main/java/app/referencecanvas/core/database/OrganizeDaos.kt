package app.referencecanvas.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class TagUsageRow(val tagId: String, val uses: Int)

data class BoardRefRow(val boardId: String, val boardName: String)

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query(
        """SELECT t.* FROM tags t JOIN reference_tags rt ON rt.tagId = t.id
        WHERE rt.referenceId = :referenceId ORDER BY t.name COLLATE NOCASE ASC""",
    )
    fun observeForReference(referenceId: String): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(links: List<ReferenceTagEntity>)

    @Query("DELETE FROM reference_tags WHERE tagId = :tagId AND referenceId IN (:referenceIds)")
    suspend fun detach(tagId: String, referenceIds: List<String>)

    @Query(
        """SELECT tagId AS tagId, COUNT(*) AS uses FROM reference_tags
        WHERE referenceId IN (:referenceIds) GROUP BY tagId""",
    )
    suspend fun usageFor(referenceIds: List<String>): List<TagUsageRow>

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM tags WHERE name LIKE :prefix || '%' ESCAPE '\\' ORDER BY name COLLATE NOCASE ASC LIMIT :limit")
    suspend fun suggest(prefix: String, limit: Int): List<TagEntity>
}

@Dao
interface FolderDao {
    @Insert
    suspend fun insert(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("UPDATE folders SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE folders SET parentFolderId = :parentId, updatedAt = :now WHERE id = :id")
    suspend fun setParent(id: String, parentId: String?, now: Long)

    @Query("UPDATE folders SET parentFolderId = :newParent WHERE parentFolderId = :oldParent")
    suspend fun reparentChildren(oldParent: String, newParent: String?)

    @Query("UPDATE boards SET folderId = :newFolder WHERE folderId = :oldFolder")
    suspend fun reassignBoards(oldFolder: String, newFolder: String?)

    @Query("UPDATE reference_items SET folderId = :newFolder WHERE folderId = :oldFolder")
    suspend fun reassignReferences(oldFolder: String, newFolder: String?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

private const val CATEGORY_LINK = "r.storageMode = 'LINK'"
private const val CATEGORY_IMAGE = "(r.storageMode <> 'LINK' AND r.mimeType LIKE 'image/%')"
private const val CATEGORY_VIDEO = "r.mimeType LIKE 'video/%'"
private const val CATEGORY_PDF = "r.mimeType = 'application/pdf'"
private const val CATEGORY_TEXT = "(r.storageMode <> 'LINK' AND r.mimeType LIKE 'text/%')"

// :text arrives already LIKE-escaped (see escapeLike). Same parameter names are reused throughout.
private const val SEARCH_SQL = """
SELECT r.* FROM reference_items r
WHERE r.deletedAt IS NULL
AND (:text = ''
    OR r.title LIKE '%' || :text || '%' ESCAPE '\'
    OR r.originalFileName LIKE '%' || :text || '%' ESCAPE '\'
    OR r.description LIKE '%' || :text || '%' ESCAPE '\'
    OR r.sourceUri LIKE '%' || :text || '%' ESCAPE '\'
    OR EXISTS (SELECT 1 FROM reference_tags rt JOIN tags t ON t.id = rt.tagId
               WHERE rt.referenceId = r.id AND t.name LIKE '%' || :text || '%' ESCAPE '\')
    OR EXISTS (SELECT 1 FROM board_references br JOIN boards b ON b.id = br.boardId
               WHERE br.referenceId = r.id AND b.name LIKE '%' || :text || '%' ESCAPE '\'))
AND (:category = 'All'
    OR (:category = 'Image' AND $CATEGORY_IMAGE)
    OR (:category = 'Video' AND $CATEGORY_VIDEO)
    OR (:category = 'Pdf' AND $CATEGORY_PDF)
    OR (:category = 'Text' AND $CATEGORY_TEXT)
    OR (:category = 'Link' AND $CATEGORY_LINK)
    OR (:category = 'Other' AND NOT ($CATEGORY_LINK OR $CATEGORY_IMAGE OR $CATEGORY_VIDEO OR $CATEGORY_PDF OR $CATEGORY_TEXT)))
AND (:favoritesOnly = 0 OR r.isFavorite = 1)
AND (:tagId IS NULL OR EXISTS (SELECT 1 FROM reference_tags rt WHERE rt.referenceId = r.id AND rt.tagId = :tagId))
AND (:folderId IS NULL OR r.folderId = :folderId)
AND (:boardId IS NULL OR EXISTS (SELECT 1 FROM board_references br WHERE br.referenceId = r.id AND br.boardId = :boardId))
AND r.createdAt >= :since
ORDER BY
    CASE WHEN :sort = 'Newest' THEN r.createdAt END DESC,
    CASE WHEN :sort = 'Oldest' THEN r.createdAt END ASC,
    CASE WHEN :sort = 'Name' THEN r.title END COLLATE NOCASE ASC,
    CASE WHEN :sort = 'Size' THEN r.fileSizeBytes END DESC,
    r.title COLLATE NOCASE ASC, r.id ASC
"""

@Dao
interface SearchDao {
    @Query(SEARCH_SQL)
    fun search(
        text: String,
        category: String,
        favoritesOnly: Boolean,
        tagId: String?,
        folderId: String?,
        boardId: String?,
        since: Long,
        sort: String,
    ): PagingSource<Int, ReferenceEntity>

    @Query(
        """SELECT b.id AS boardId, b.name AS boardName FROM board_references br
        JOIN boards b ON b.id = br.boardId WHERE br.referenceId = :referenceId
        ORDER BY b.name COLLATE NOCASE ASC""",
    )
    suspend fun boardsFor(referenceId: String): List<BoardRefRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(recent: RecentSearchEntity)

    @Query("SELECT * FROM recent_searches ORDER BY usedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>>

    @Query("DELETE FROM recent_searches WHERE term = :term")
    suspend fun deleteRecent(term: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearRecent()

    @Query("DELETE FROM recent_searches WHERE term NOT IN (SELECT term FROM recent_searches ORDER BY usedAt DESC LIMIT :keep)")
    suspend fun trimRecent(keep: Int)
}
