package app.referencecanvas.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/** Bump with every schema change, together with a Migration and a migration test. */
const val DATABASE_VERSION = 1

@Database(
    entities = [
        FolderEntity::class,
        BoardEntity::class,
        ReferenceEntity::class,
        BoardReferenceEntity::class,
        TagEntity::class,
        ReferenceTagEntity::class,
        AnnotationEntity::class,
        RecentSearchEntity::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun boardDao(): BoardDao
    abstract fun referenceDao(): ReferenceDao
    abstract fun boardReferenceDao(): BoardReferenceDao
    abstract fun tagDao(): TagDao
    abstract fun folderDao(): FolderDao
    abstract fun searchDao(): SearchDao
    abstract fun annotationDao(): AnnotationDao
}

/**
 * Every schema bump needs an explicit entry here plus a migration test.
 * The app never uses destructive migration: user data must survive updates.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
