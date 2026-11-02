package app.referencecanvas.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AnnotationEntity>)

    @Query("SELECT * FROM annotations WHERE referenceId = :referenceId ORDER BY createdAt ASC, id ASC")
    fun observeFor(referenceId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE referenceId = :referenceId ORDER BY createdAt ASC, id ASC")
    suspend fun getFor(referenceId: String): List<AnnotationEntity>

    @Query("SELECT COUNT(*) FROM annotations WHERE referenceId = :referenceId")
    fun observeCount(referenceId: String): Flow<Int>

    @Query("DELETE FROM annotations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM annotations WHERE referenceId = :referenceId")
    suspend fun deleteFor(referenceId: String)
}
