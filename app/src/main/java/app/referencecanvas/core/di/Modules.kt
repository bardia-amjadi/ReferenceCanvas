package app.referencecanvas.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import app.referencecanvas.core.database.AnnotationDao
import app.referencecanvas.core.database.AppDatabase
import app.referencecanvas.core.database.BoardDao
import app.referencecanvas.core.database.BoardReferenceDao
import app.referencecanvas.core.database.Migrations
import app.referencecanvas.core.database.FolderDao
import app.referencecanvas.core.database.ReferenceDao
import app.referencecanvas.core.database.SearchDao
import app.referencecanvas.core.database.TagDao
import app.referencecanvas.core.database.RoomTransactionRunner
import app.referencecanvas.core.database.TransactionRunner
import app.referencecanvas.core.datastore.settingsDataStore
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.IdGenerator
import app.referencecanvas.core.util.UuidGenerator
import app.referencecanvas.core.util.WallClock
import app.referencecanvas.core.filesystem.FileStore
import app.referencecanvas.data.importer.ReferenceImporter
import app.referencecanvas.data.importer.ReferenceImporterImpl
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.data.repository.ReferenceRepositoryImpl
import app.referencecanvas.data.repository.AnnotationRepository
import app.referencecanvas.data.repository.AnnotationRepositoryImpl
import app.referencecanvas.data.repository.BoardRepository
import app.referencecanvas.data.repository.CanvasRepository
import app.referencecanvas.data.repository.FolderRepository
import app.referencecanvas.data.repository.FolderRepositoryImpl
import app.referencecanvas.data.repository.SearchRepository
import app.referencecanvas.data.repository.SearchRepositoryImpl
import app.referencecanvas.data.repository.TagRepository
import app.referencecanvas.data.repository.TagRepositoryImpl
import app.referencecanvas.data.repository.CanvasRepositoryImpl
import app.referencecanvas.data.repository.BoardRepositoryImpl
import app.referencecanvas.data.repository.SettingsRepository
import app.referencecanvas.data.repository.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "reference_canvas.db")
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides fun boardDao(db: AppDatabase): BoardDao = db.boardDao()
    @Provides fun referenceDao(db: AppDatabase): ReferenceDao = db.referenceDao()
    @Provides fun boardReferenceDao(db: AppDatabase): BoardReferenceDao = db.boardReferenceDao()
    @Provides fun tagDao(db: AppDatabase): TagDao = db.tagDao()
    @Provides fun folderDao(db: AppDatabase): FolderDao = db.folderDao()
    @Provides fun searchDao(db: AppDatabase): SearchDao = db.searchDao()
    @Provides fun annotationDao(db: AppDatabase): AnnotationDao = db.annotationDao()

    @Provides @Singleton
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun boardRepository(impl: BoardRepositoryImpl): BoardRepository
    @Binds abstract fun annotationRepository(impl: AnnotationRepositoryImpl): AnnotationRepository
    @Binds abstract fun tagRepository(impl: TagRepositoryImpl): TagRepository
    @Binds abstract fun folderRepository(impl: FolderRepositoryImpl): FolderRepository
    @Binds abstract fun searchRepository(impl: SearchRepositoryImpl): SearchRepository
    @Binds abstract fun canvasRepository(impl: CanvasRepositoryImpl): CanvasRepository
    @Binds abstract fun referenceRepository(impl: ReferenceRepositoryImpl): ReferenceRepository
    @Binds abstract fun referenceImporter(impl: ReferenceImporterImpl): ReferenceImporter
    @Binds abstract fun settingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun transactions(impl: RoomTransactionRunner): TransactionRunner
    @Binds abstract fun clock(impl: WallClock): Clock
    @Binds abstract fun ids(impl: UuidGenerator): IdGenerator
}
