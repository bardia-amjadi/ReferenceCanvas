package app.referencecanvas.data.repository

import app.referencecanvas.core.database.AnnotationDao
import app.referencecanvas.core.database.AnnotationEntity
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import app.referencecanvas.domain.annotation.Annotation
import app.referencecanvas.domain.annotation.AnnotationCodec
import app.referencecanvas.domain.annotation.AnnotationData
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Annotations for one reference, in paint order. [unreadable] counts stored rows that could not be decoded. */
data class AnnotationSet(val items: List<Annotation>, val unreadable: Int)

interface AnnotationRepository {
    fun observe(referenceId: String): Flow<AnnotationSet>
    fun observeCount(referenceId: String): Flow<Int>

    /** Stores [data] on top of the existing annotations and returns it with its new id. */
    suspend fun add(referenceId: String, data: AnnotationData): Annotation

    /** Re-inserts previously removed annotations with their original ids and order (used by undo). */
    suspend fun restore(items: List<Annotation>)
    suspend fun remove(ids: Collection<String>)

    /** Removes every annotation of [referenceId] and returns what was removed, so it can be undone. */
    suspend fun clear(referenceId: String): List<Annotation>
}

class AnnotationRepositoryImpl @Inject constructor(
    private val dao: AnnotationDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
) : AnnotationRepository {

    override fun observe(referenceId: String): Flow<AnnotationSet> =
        dao.observeFor(referenceId).map { rows -> decode(rows) }

    override fun observeCount(referenceId: String): Flow<Int> = dao.observeCount(referenceId)

    override suspend fun add(referenceId: String, data: AnnotationData): Annotation = transactions.run {
        val nextSeq = (decode(dao.getFor(referenceId)).items.maxOfOrNull { it.data.seq } ?: 0L) + 1
        val stored = data.copy(seq = nextSeq)
        val now = clock.now()
        val annotation = Annotation(ids.newId(), referenceId, stored)
        dao.insertAll(listOf(annotation.toEntity(now)))
        annotation
    }

    override suspend fun restore(items: List<Annotation>) {
        if (items.isEmpty()) return
        val now = clock.now()
        dao.insertAll(items.map { it.toEntity(now) })
    }

    override suspend fun remove(ids: Collection<String>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids.toList())
    }

    override suspend fun clear(referenceId: String): List<Annotation> = transactions.run {
        val existing = decode(dao.getFor(referenceId)).items
        dao.deleteFor(referenceId)
        existing
    }

    private fun decode(rows: List<AnnotationEntity>): AnnotationSet {
        var unreadable = 0
        val items = rows.mapNotNull { row ->
            val data = AnnotationCodec.decode(row.serializedData)
            if (data == null) { unreadable++; null } else Annotation(row.id, row.referenceId, data)
        }.sortedWith(compareBy({ it.data.seq }, { it.id }))
        return AnnotationSet(items, unreadable)
    }

    private fun Annotation.toEntity(now: Long) = AnnotationEntity(
        id = id,
        referenceId = referenceId,
        type = data.kind.name,
        serializedData = AnnotationCodec.encode(data),
        createdAt = now,
        updatedAt = now,
    )
}
