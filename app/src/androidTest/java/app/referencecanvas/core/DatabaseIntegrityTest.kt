package app.referencecanvas.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.referencecanvas.core.database.AppDatabase
import app.referencecanvas.core.database.BoardEntity
import app.referencecanvas.core.database.BoardReferenceEntity
import app.referencecanvas.core.database.AnnotationEntity
import app.referencecanvas.core.database.FolderEntity
import app.referencecanvas.core.database.RecentSearchEntity
import app.referencecanvas.core.database.ReferenceEntity
import app.referencecanvas.core.database.ReferenceTagEntity
import app.referencecanvas.core.database.TagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseIntegrityTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    private fun board(id: String) = BoardEntity(id = id, name = id, createdAt = 1, updatedAt = 1)
    private fun reference(id: String) = ReferenceEntity(
        id = id, title = id, originalFileName = "$id.png", mimeType = "image/png", createdAt = 1, updatedAt = 1,
    )

    @Test fun deletingABoardKeepsSharedReferences() = runBlocking {
        db.boardDao().insert(board("a"))
        db.boardDao().insert(board("b"))
        db.referenceDao().insert(reference("r"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r"))
        db.boardReferenceDao().insert(BoardReferenceEntity("b", "r"))

        db.boardDao().deleteById("a")

        assertNotNull(db.referenceDao().getById("r"))
        assertEquals(0, db.boardReferenceDao().countForBoard("a"))
        assertEquals(1, db.boardReferenceDao().countForBoard("b"))
    }

    @Test fun deletingAReferenceRemovesItsMemberships() = runBlocking {
        db.boardDao().insert(board("a"))
        db.referenceDao().insert(reference("r"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r"))

        db.referenceDao().deleteById("r")

        assertNull(db.referenceDao().getById("r"))
        assertEquals(0, db.boardReferenceDao().countForBoard("a"))
    }

    @Test fun duplicateMembershipIsIgnored() = runBlocking {
        db.boardDao().insert(board("a"))
        db.referenceDao().insert(reference("r"))

        val first = db.boardReferenceDao().insert(BoardReferenceEntity("a", "r"))
        val second = db.boardReferenceDao().insert(BoardReferenceEntity("a", "r", positionX = 99.0))

        assertEquals(true, first != -1L)
        assertEquals(-1L, second)
        assertEquals(1, db.boardReferenceDao().countForBoard("a"))
    }

    @Test fun copyMembershipPreservesLayout() = runBlocking {
        db.boardDao().insert(board("src"))
        db.boardDao().insert(board("dst"))
        db.referenceDao().insert(reference("r"))
        db.boardReferenceDao().insert(BoardReferenceEntity("src", "r", positionX = 1e7, positionY = -42.5, zIndex = 3))

        db.boardReferenceDao().copyMembership("src", "dst")

        assertEquals(1, db.boardReferenceDao().countForBoard("dst"))
    }

    @Test fun summaryCountExcludesTrashedReferences() = runBlocking {
        db.boardDao().insert(board("a"))
        db.referenceDao().insert(reference("live"))
        db.referenceDao().insert(reference("trashed"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "live"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "trashed"))
        db.referenceDao().setDeletedAt("trashed", 123L)

        val row = db.boardDao().observeSummaries("").first().single()

        assertEquals(1, row.referenceCount)
    }

    @Test fun searchTreatsPercentLiterally() = runBlocking {
        db.boardDao().insert(board("100%"))
        db.boardDao().insert(board("other"))

        val hits = db.boardDao().observeSummaries("100\\%").first()

        assertEquals(listOf("100%"), hits.map { it.board.id })
    }

    @Test fun orderedIdsFollowTheRequestedSortMode() = runBlocking {
        db.boardDao().insert(board("a"))
        // title / createdAt: b-title is newest, a-title is oldest, c-title in the middle
        db.referenceDao().insert(reference("r1").copy(title = "banana", createdAt = 30))
        db.referenceDao().insert(reference("r2").copy(title = "Apple", createdAt = 10))
        db.referenceDao().insert(reference("r3").copy(title = "cherry", createdAt = 20))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r1", sortOrder = 2))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r2", sortOrder = 0))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r3", sortOrder = 1))

        val dao = db.boardReferenceDao()
        assertEquals(listOf("r2", "r3", "r1"), dao.orderedIds("a", "Manual"))
        assertEquals(listOf("r2", "r1", "r3"), dao.orderedIds("a", "Name")) // case-insensitive
        assertEquals(listOf("r1", "r3", "r2"), dao.orderedIds("a", "DateAdded")) // newest first
    }

    @Test fun orderedIdsExcludeTrashedReferences() = runBlocking {
        db.boardDao().insert(board("a"))
        db.referenceDao().insert(reference("live"))
        db.referenceDao().insert(reference("gone"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "live"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "gone"))
        db.referenceDao().setDeletedAt("gone", 5L)

        assertEquals(listOf("live"), db.boardReferenceDao().orderedIds("a", "Manual"))
    }

    @Test fun bulkFavoriteAndDeleteAffectOnlyTheGivenIds() = runBlocking {
        listOf("x", "y", "z").forEach { db.referenceDao().insert(reference(it)) }

        db.referenceDao().setFavorite(listOf("x", "y"), true)
        db.referenceDao().deleteByIds(listOf("y"))

        assertEquals(true, db.referenceDao().getById("x")!!.isFavorite)
        assertNull(db.referenceDao().getById("y"))
        assertEquals(false, db.referenceDao().getById("z")!!.isFavorite)
    }

    @Test fun canvasCardsCarryLayoutAndExcludeTrashed() = runBlocking {
        db.boardDao().insert(board("a"))
        db.referenceDao().insert(reference("live").copy(widthPx = 400, heightPx = 200))
        db.referenceDao().insert(reference("gone"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "live", positionX = 1e7, positionY = -250.5, zIndex = 4))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "gone"))
        db.referenceDao().setDeletedAt("gone", 9L)

        val rows = db.boardReferenceDao().observeCanvasCards("a").first()

        assertEquals(listOf("live"), rows.map { it.referenceId })
        assertEquals(1e7, rows.single().positionX, 0.0)
        assertEquals(-250.5, rows.single().positionY, 0.0)
        assertEquals(4, rows.single().zIndex)
        assertEquals(400, rows.single().widthPx)
    }

    @Test fun setLayoutUpdatesOnlyTheTargetedCardOnTheTargetedBoard() = runBlocking {
        db.boardDao().insert(board("a"))
        db.boardDao().insert(board("b"))
        db.referenceDao().insert(reference("r"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r"))
        db.boardReferenceDao().insert(BoardReferenceEntity("b", "r"))

        db.boardReferenceDao().setLayout("a", "r", 123.5, -9.0, 7)

        assertEquals(123.5, db.boardReferenceDao().getMembership("a", "r")!!.positionX, 0.0)
        assertEquals(7, db.boardReferenceDao().getMembership("a", "r")!!.zIndex)
        assertEquals(0.0, db.boardReferenceDao().getMembership("b", "r")!!.positionX, 0.0)
        assertEquals(7, db.boardReferenceDao().maxZ("a"))
    }

    @Test fun viewportIsPersistedWithFullPrecision() = runBlocking {
        db.boardDao().insert(board("a"))

        db.boardDao().setViewport("a", -9_876_543.21, 12_345.678, 0.25f)

        val saved = db.boardDao().getById("a")!!
        assertEquals(-9_876_543.21, saved.canvasOffsetX, 0.0)
        assertEquals(12_345.678, saved.canvasOffsetY, 0.0)
        assertEquals(0.25f, saved.canvasZoom, 0f)
    }

    // ---- Stage 4: tags, folders, search

    private suspend fun searchIds(
        text: String = "", category: String = "All", favoritesOnly: Boolean = false, tagId: String? = null,
        folderId: String? = null, boardId: String? = null, since: Long = 0, sort: String = "Name",
    ): List<String> {
        val source = db.searchDao().search(text, category, favoritesOnly, tagId, folderId, boardId, since, sort)
        val page = source.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 500, false))
        return (page as androidx.paging.PagingSource.LoadResult.Page).data.map { it.id }
    }

    @Test fun searchMatchesTitleFileNameTagAndBoardName() = runBlocking {
        db.boardDao().insert(board("a").copy(name = "Poster ideas"))
        db.referenceDao().insert(reference("r1").copy(title = "Sunset", originalFileName = "IMG_0042.png"))
        db.referenceDao().insert(reference("r2").copy(title = "Forest"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r2"))
        db.tagDao().insert(TagEntity("t1", "golden hour"))
        db.tagDao().attach(listOf(ReferenceTagEntity("r1", "t1")))

        assertEquals(listOf("r1"), searchIds(text = "sunse"))
        assertEquals(listOf("r1"), searchIds(text = "img_0042".replace("_", "\\_")))
        assertEquals(listOf("r1"), searchIds(text = "golden"))
        assertEquals(listOf("r2"), searchIds(text = "poster"))
        assertEquals(emptyList<String>(), searchIds(text = "nothing-like-this"))
    }

    @Test fun searchTreatsPercentAndUnderscoreLiterally() = runBlocking {
        db.referenceDao().insert(reference("r1").copy(title = "100% done"))
        db.referenceDao().insert(reference("r2").copy(title = "1000 done"))

        assertEquals(listOf("r1"), searchIds(text = "100\\%"))
    }

    @Test fun searchFiltersByCategoryFavoriteTagFolderBoardAndDate() = runBlocking {
        db.folderDao().insert(FolderEntity("f", "Folder", 1, 1))
        db.boardDao().insert(board("a"))
        db.referenceDao().insert(reference("img").copy(isFavorite = true, folderId = "f", createdAt = 1_000))
        db.referenceDao().insert(reference("pdf").copy(mimeType = "application/pdf", createdAt = 5_000))
        db.referenceDao().insert(reference("link").copy(mimeType = "text/x-uri", storageMode = "LINK", sourceUri = "https://x.example"))
        db.referenceDao().insert(reference("txt").copy(mimeType = "text/plain"))
        db.referenceDao().insert(reference("zip").copy(mimeType = "application/zip"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "pdf"))
        db.tagDao().insert(TagEntity("t", "keep"))
        db.tagDao().attach(listOf(ReferenceTagEntity("zip", "t")))

        assertEquals(listOf("img"), searchIds(category = "Image"))
        assertEquals(listOf("pdf"), searchIds(category = "Pdf"))
        assertEquals(listOf("link"), searchIds(category = "Link"))
        assertEquals(listOf("txt"), searchIds(category = "Text")) // links are not "text"
        assertEquals(listOf("zip"), searchIds(category = "Other"))
        assertEquals(listOf("img"), searchIds(favoritesOnly = true))
        assertEquals(listOf("zip"), searchIds(tagId = "t"))
        assertEquals(listOf("img"), searchIds(folderId = "f"))
        assertEquals(listOf("pdf"), searchIds(boardId = "a"))
        assertEquals(listOf("pdf"), searchIds(since = 2_000, category = "Pdf"))
    }

    @Test fun searchExcludesTrashedAndHonoursSortModes() = runBlocking {
        db.referenceDao().insert(reference("b").copy(title = "banana", createdAt = 10, fileSizeBytes = 5))
        db.referenceDao().insert(reference("a").copy(title = "Apple", createdAt = 30, fileSizeBytes = 50))
        db.referenceDao().insert(reference("c").copy(title = "cherry", createdAt = 20, fileSizeBytes = 500))
        db.referenceDao().insert(reference("gone").copy(title = "ghost"))
        db.referenceDao().setDeletedAt("gone", 1L)

        assertEquals(listOf("a", "b", "c"), searchIds(sort = "Name"))
        assertEquals(listOf("a", "c", "b"), searchIds(sort = "Newest"))
        assertEquals(listOf("b", "c", "a"), searchIds(sort = "Oldest"))
        assertEquals(listOf("c", "a", "b"), searchIds(sort = "Size"))
    }

    @Test fun tagUsageAttachDetachAndCascadeOnDelete() = runBlocking {
        db.referenceDao().insert(reference("r1"))
        db.referenceDao().insert(reference("r2"))
        db.tagDao().insert(TagEntity("t", "x"))
        db.tagDao().attach(listOf(ReferenceTagEntity("r1", "t"), ReferenceTagEntity("r2", "t")))
        db.tagDao().attach(listOf(ReferenceTagEntity("r1", "t"))) // duplicate ignored

        assertEquals(2, db.tagDao().usageFor(listOf("r1", "r2")).single().uses)
        db.tagDao().detach("t", listOf("r1"))
        assertEquals(1, db.tagDao().usageFor(listOf("r1", "r2")).single().uses)
        assertEquals("x", db.tagDao().findByName("X")!!.name) // case-insensitive lookup

        db.tagDao().delete("t")
        assertEquals(emptyList<Any>(), db.tagDao().usageFor(listOf("r1", "r2")))
        assertNotNull(db.referenceDao().getById("r1"))
    }

    @Test fun deletingAFolderKeepsItsBoardsAndReferences() = runBlocking {
        db.folderDao().insert(FolderEntity("top", "Top", 1, 1))
        db.folderDao().insert(FolderEntity("mid", "Mid", 1, 1, parentFolderId = "top"))
        db.boardDao().insert(board("a").copy(folderId = "mid"))
        db.referenceDao().insert(reference("r").copy(folderId = "mid"))

        db.folderDao().reparentChildren("mid", "top")
        db.folderDao().reassignBoards("mid", "top")
        db.folderDao().reassignReferences("mid", "top")
        db.folderDao().delete("mid")

        assertEquals("top", db.boardDao().getById("a")!!.folderId)
        assertEquals("top", db.referenceDao().getById("r")!!.folderId)
        assertNull(db.folderDao().getById("mid"))
    }

    @Test fun boardsInFolderQueryDistinguishesRootFromFolder() = runBlocking {
        db.folderDao().insert(FolderEntity("f", "F", 1, 1))
        db.boardDao().insert(board("root"))
        db.boardDao().insert(board("inside").copy(folderId = "f"))

        assertEquals(listOf("root"), db.boardDao().observeSummariesInFolder(null).first().map { it.board.id })
        assertEquals(listOf("inside"), db.boardDao().observeSummariesInFolder("f").first().map { it.board.id })
    }

    @Test fun recentSearchesAreOrderedTrimmedAndClearable() = runBlocking {
        (1..5).forEach { db.searchDao().upsertRecent(RecentSearchEntity("term$it", it.toLong())) }
        db.searchDao().upsertRecent(RecentSearchEntity("term1", 99)) // re-used: moves to the top, no duplicate

        assertEquals("term1", db.searchDao().observeRecent(10).first().first().term)
        assertEquals(5, db.searchDao().observeRecent(10).first().size)

        db.searchDao().trimRecent(2)
        assertEquals(listOf("term1", "term5"), db.searchDao().observeRecent(10).first().map { it.term })

        db.searchDao().clearRecent()
        assertEquals(0, db.searchDao().observeRecent(10).first().size)
    }

    // ---- Stage 5: annotations

    @Test fun annotationsAreStoredOrderedAndCascadeWithTheirReference() = runBlocking {
        db.referenceDao().insert(reference("r"))
        db.annotationDao().insertAll(
            listOf(
                AnnotationEntity("b", "r", "Pen", "{}", createdAt = 2, updatedAt = 2),
                AnnotationEntity("a", "r", "Rect", "{}", createdAt = 1, updatedAt = 1),
            ),
        )

        assertEquals(listOf("a", "b"), db.annotationDao().getFor("r").map { it.id })
        assertEquals(2, db.annotationDao().observeCount("r").first())

        db.referenceDao().deleteById("r")
        assertEquals(0, db.annotationDao().getFor("r").size)
    }

    @Test fun deletingSomeAnnotationsLeavesTheOthersAndTheOriginalFileRecordIntact() = runBlocking {
        db.referenceDao().insert(reference("r"))
        db.annotationDao().insertAll((1..3).map { AnnotationEntity("a$it", "r", "Pen", "{}", it.toLong(), it.toLong()) })

        db.annotationDao().deleteByIds(listOf("a1", "a3"))

        assertEquals(listOf("a2"), db.annotationDao().getFor("r").map { it.id })
        assertNotNull(db.referenceDao().getById("r"))

        db.annotationDao().deleteFor("r")
        assertEquals(0, db.annotationDao().observeCount("r").first())
    }

    // ---- Stage 6: trash

    @Test fun trashedReferencesDisappearEverywhereAndComeBackOnRestore() = runBlocking {
        db.boardDao().insert(board("a"))
        db.referenceDao().insert(reference("r1"))
        db.referenceDao().insert(reference("r2"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r1"))
        db.boardReferenceDao().insert(BoardReferenceEntity("a", "r2"))

        db.referenceDao().setDeletedAtMany(listOf("r1"), 100L)

        assertEquals(listOf("r2"), db.boardReferenceDao().orderedIds("a", "Manual"))
        assertEquals(listOf("r2"), db.boardReferenceDao().observeCanvasCards("a").first().map { it.referenceId })
        assertEquals(1, db.boardDao().observeSummaries("").first().single().referenceCount)
        assertEquals(listOf("r1"), db.referenceDao().allTrashedIds())
        assertEquals(1, db.referenceDao().observeTrashCount().first())
        // Membership is kept, so a restore puts it back on its board.
        assertEquals(2, db.boardReferenceDao().countForBoard("a"))

        db.referenceDao().setDeletedAtMany(listOf("r1"), null)
        assertEquals(listOf("r1", "r2"), db.boardReferenceDao().orderedIds("a", "Name"))
        assertEquals(0, db.referenceDao().observeTrashCount().first())
    }

    @Test fun onlyItemsOlderThanTheRetentionCutoffAreExpired() = runBlocking {
        db.referenceDao().insert(reference("old"))
        db.referenceDao().insert(reference("recent"))
        db.referenceDao().insert(reference("live"))
        db.referenceDao().setDeletedAtMany(listOf("old"), 1_000L)
        db.referenceDao().setDeletedAtMany(listOf("recent"), 9_000L)

        assertEquals(listOf("old"), db.referenceDao().expiredTrashedIds(5_000L))
    }
}
