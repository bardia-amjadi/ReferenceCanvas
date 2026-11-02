package app.referencecanvas.testing

import app.referencecanvas.core.database.FolderDao
import app.referencecanvas.core.database.FolderEntity
import app.referencecanvas.core.database.ReferenceTagEntity
import app.referencecanvas.core.database.TagDao
import app.referencecanvas.core.database.TagEntity
import app.referencecanvas.core.database.TagUsageRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeTagDao : TagDao {
    val tags = linkedMapOf<String, TagEntity>()
    val links = linkedSetOf<ReferenceTagEntity>()

    override suspend fun insert(tag: TagEntity): Long {
        if (tags.values.any { it.name == tag.name }) return -1
        tags[tag.id] = tag
        return 1
    }
    override suspend fun findByName(name: String) = tags.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
    override fun observeAll(): Flow<List<TagEntity>> = flowOf(tags.values.toList())
    override fun observeForReference(referenceId: String): Flow<List<TagEntity>> =
        flowOf(links.filter { it.referenceId == referenceId }.mapNotNull { tags[it.tagId] })
    override suspend fun attach(links: List<ReferenceTagEntity>) { this.links += links }
    override suspend fun detach(tagId: String, referenceIds: List<String>) {
        links.removeAll { it.tagId == tagId && it.referenceId in referenceIds }
    }
    override suspend fun usageFor(referenceIds: List<String>): List<TagUsageRow> =
        links.filter { it.referenceId in referenceIds }.groupBy { it.tagId }.map { (id, l) -> TagUsageRow(id, l.size) }
    override suspend fun delete(id: String) { tags.remove(id); links.removeAll { it.tagId == id } }
    override suspend fun suggest(prefix: String, limit: Int): List<TagEntity> =
        tags.values.filter { it.name.startsWith(prefix.replace("\\", ""), ignoreCase = true) }.take(limit)
}

class FakeFolderDao : FolderDao {
    val folders = linkedMapOf<String, FolderEntity>()
    val reassignedBoards = mutableListOf<Pair<String, String?>>()
    val reassignedReferences = mutableListOf<Pair<String, String?>>()

    override suspend fun insert(folder: FolderEntity) { folders[folder.id] = folder }
    override suspend fun getById(id: String) = folders[id]
    override suspend fun getAll() = folders.values.toList()
    override fun observeAll(): Flow<List<FolderEntity>> = flowOf(folders.values.toList())
    override suspend fun rename(id: String, name: String, now: Long) {
        folders[id]?.let { folders[id] = it.copy(name = name, updatedAt = now) }
    }
    override suspend fun setParent(id: String, parentId: String?, now: Long) {
        folders[id]?.let { folders[id] = it.copy(parentFolderId = parentId, updatedAt = now) }
    }
    override suspend fun reparentChildren(oldParent: String, newParent: String?) {
        folders.replaceAll { _, f -> if (f.parentFolderId == oldParent) f.copy(parentFolderId = newParent) else f }
    }
    override suspend fun reassignBoards(oldFolder: String, newFolder: String?) { reassignedBoards += oldFolder to newFolder }
    override suspend fun reassignReferences(oldFolder: String, newFolder: String?) { reassignedReferences += oldFolder to newFolder }
    override suspend fun delete(id: String) { folders.remove(id) }
}

class FakeAnnotationDao : app.referencecanvas.core.database.AnnotationDao {
    val rows = linkedMapOf<String, app.referencecanvas.core.database.AnnotationEntity>()

    override suspend fun insertAll(items: List<app.referencecanvas.core.database.AnnotationEntity>) { items.forEach { rows[it.id] = it } }
    override fun observeFor(referenceId: String): Flow<List<app.referencecanvas.core.database.AnnotationEntity>> =
        flowOf(rows.values.filter { it.referenceId == referenceId })
    override suspend fun getFor(referenceId: String) = rows.values.filter { it.referenceId == referenceId }
    override fun observeCount(referenceId: String): Flow<Int> = flowOf(rows.values.count { it.referenceId == referenceId })
    override suspend fun deleteByIds(ids: List<String>) { ids.forEach { rows.remove(it) } }
    override suspend fun deleteFor(referenceId: String) { rows.values.removeAll { it.referenceId == referenceId } }
}
