package app.referencecanvas.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.referencecanvas.core.database.FolderDao
import app.referencecanvas.core.database.FolderEntity
import app.referencecanvas.core.database.RecentSearchEntity
import app.referencecanvas.core.database.ReferenceTagEntity
import app.referencecanvas.core.database.SearchDao
import app.referencecanvas.core.database.TagDao
import app.referencecanvas.core.database.TagEntity
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import app.referencecanvas.core.util.escapeLike
import app.referencecanvas.domain.model.BoardRef
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.domain.model.FolderTree
import app.referencecanvas.domain.model.Reference
import app.referencecanvas.domain.model.SearchFilters
import app.referencecanvas.domain.model.Tag
import app.referencecanvas.domain.model.TagNames
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ---------------------------------------------------------------- tags

interface TagRepository {
    fun observeAll(): Flow<List<Tag>>
    fun observeFor(referenceId: String): Flow<List<Tag>>

    /** How many of [referenceIds] carry each tag (tags with zero uses are absent). */
    suspend fun usageFor(referenceIds: Collection<String>): Map<String, Int>

    /** Finds a tag by name (case-insensitive) or creates it, then attaches it. False if the name is unusable. */
    suspend fun createAndAttach(name: String, referenceIds: Collection<String>): Boolean
    suspend fun attach(tagId: String, referenceIds: Collection<String>)
    suspend fun detach(tagId: String, referenceIds: Collection<String>)
    suspend fun delete(tagId: String)
    suspend fun suggest(prefix: String, limit: Int = 8): List<Tag>
}

class TagRepositoryImpl @Inject constructor(
    private val dao: TagDao,
    private val transactions: TransactionRunner,
    private val ids: IdGenerator,
) : TagRepository {
    override fun observeAll() = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeFor(referenceId: String) = dao.observeForReference(referenceId).map { list -> list.map { it.toDomain() } }

    override suspend fun usageFor(referenceIds: Collection<String>): Map<String, Int> {
        val totals = HashMap<String, Int>()
        referenceIds.chunked(CHUNK).forEach { chunk ->
            dao.usageFor(chunk).forEach { totals.merge(it.tagId, it.uses, Int::plus) }
        }
        return totals
    }

    override suspend fun createAndAttach(name: String, referenceIds: Collection<String>): Boolean {
        val clean = TagNames.normalize(name) ?: return false
        transactions.run {
            val tag = dao.findByName(clean) ?: TagEntity(ids.newId(), clean).also { dao.insert(it) }
            attachInTransaction(tag.id, referenceIds)
        }
        return true
    }

    override suspend fun attach(tagId: String, referenceIds: Collection<String>) {
        transactions.run { attachInTransaction(tagId, referenceIds) }
    }

    private suspend fun attachInTransaction(tagId: String, referenceIds: Collection<String>) {
        referenceIds.chunked(CHUNK).forEach { chunk -> dao.attach(chunk.map { ReferenceTagEntity(it, tagId) }) }
    }

    override suspend fun detach(tagId: String, referenceIds: Collection<String>) {
        transactions.run { referenceIds.chunked(CHUNK).forEach { dao.detach(tagId, it) } }
    }

    override suspend fun delete(tagId: String) = dao.delete(tagId)

    override suspend fun suggest(prefix: String, limit: Int): List<Tag> {
        val clean = prefix.trim()
        if (clean.isEmpty()) return emptyList()
        return dao.suggest(escapeLike(clean), limit).map { it.toDomain() }
    }

    private fun TagEntity.toDomain() = Tag(id, name, color)

    private companion object { const val CHUNK = 500 }
}

// ---------------------------------------------------------------- folders

interface FolderRepository {
    fun observeAll(): Flow<List<Folder>>
    suspend fun create(name: String, parentId: String?): String?
    suspend fun rename(id: String, name: String): Boolean

    /** Returns false if the move would create a loop or the folder doesn't exist. */
    suspend fun move(id: String, newParentId: String?): Boolean

    /** Deletes the folder; everything inside moves up to its parent. Nothing inside is deleted. */
    suspend fun delete(id: String)
}

class FolderRepositoryImpl @Inject constructor(
    private val dao: FolderDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
) : FolderRepository {
    override fun observeAll() = dao.observeAll().map { list -> list.map { Folder(it.id, it.name, it.parentFolderId) } }

    override suspend fun create(name: String, parentId: String?): String? {
        val clean = TagNames.normalize(name) ?: return null
        val now = clock.now()
        val id = ids.newId()
        dao.insert(FolderEntity(id = id, name = clean, createdAt = now, updatedAt = now, parentFolderId = parentId))
        return id
    }

    override suspend fun rename(id: String, name: String): Boolean {
        val clean = TagNames.normalize(name) ?: return false
        if (dao.getById(id) == null) return false
        dao.rename(id, clean, clock.now())
        return true
    }

    override suspend fun move(id: String, newParentId: String?): Boolean = transactions.run {
        if (dao.getById(id) == null) return@run false
        if (newParentId != null && dao.getById(newParentId) == null) return@run false
        val parents = dao.getAll().associate { it.id to it.parentFolderId }
        if (FolderTree.wouldCreateCycle(id, newParentId) { parents[it] }) return@run false
        dao.setParent(id, newParentId, clock.now())
        true
    }

    override suspend fun delete(id: String) {
        transactions.run {
            val folder = dao.getById(id) ?: return@run
            dao.reparentChildren(id, folder.parentFolderId)
            dao.reassignBoards(id, folder.parentFolderId)
            dao.reassignReferences(id, folder.parentFolderId)
            dao.delete(id)
        }
    }
}

// ---------------------------------------------------------------- search

interface SearchRepository {
    fun search(filters: SearchFilters): Flow<PagingData<Reference>>
    suspend fun boardsFor(referenceId: String): List<BoardRef>
    fun observeRecent(): Flow<List<String>>
    suspend fun remember(term: String)
    suspend fun forget(term: String)
    suspend fun clearRecent()
}

class SearchRepositoryImpl @Inject constructor(
    private val dao: SearchDao,
    private val clock: Clock,
) : SearchRepository {

    override fun search(filters: SearchFilters): Flow<PagingData<Reference>> {
        val since = filters.dateRange.days?.let { clock.now() - it * DAY_MS } ?: 0L
        return Pager(PagingConfig(pageSize = 40, initialLoadSize = 80, enablePlaceholders = false)) {
            dao.search(
                text = escapeLike(filters.text.trim()),
                category = filters.category.name,
                favoritesOnly = filters.favoritesOnly,
                tagId = filters.tagId,
                folderId = filters.folderId,
                boardId = filters.boardId,
                since = since,
                sort = filters.sort.name,
            )
        }.flow.map { page -> page.map { it.toDomain() } }
    }

    override suspend fun boardsFor(referenceId: String) =
        dao.boardsFor(referenceId).map { BoardRef(it.boardId, it.boardName) }

    override fun observeRecent() = dao.observeRecent(RECENT_SHOWN).map { rows -> rows.map { it.term } }

    override suspend fun remember(term: String) {
        val clean = term.trim().replace(Regex("\\s+"), " ").take(120)
        if (clean.isEmpty()) return
        dao.upsertRecent(RecentSearchEntity(clean, clock.now()))
        dao.trimRecent(RECENT_KEPT)
    }

    override suspend fun forget(term: String) = dao.deleteRecent(term)
    override suspend fun clearRecent() = dao.clearRecent()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val RECENT_SHOWN = 10
        const val RECENT_KEPT = 30
    }
}
