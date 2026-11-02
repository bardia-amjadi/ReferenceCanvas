package app.referencecanvas.core

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.referencecanvas.core.backup.BackupDestination
import app.referencecanvas.core.backup.BackupEngine
import app.referencecanvas.core.backup.BackupReader
import app.referencecanvas.core.backup.BackupScan
import app.referencecanvas.core.backup.BackupSettingsStore
import app.referencecanvas.core.backup.RestoreFailure
import app.referencecanvas.core.backup.RestoreState
import app.referencecanvas.core.database.AnnotationEntity
import app.referencecanvas.core.database.AppDatabase
import app.referencecanvas.core.database.BoardEntity
import app.referencecanvas.core.database.BoardReferenceEntity
import app.referencecanvas.core.database.DATABASE_VERSION
import app.referencecanvas.core.database.FolderEntity
import app.referencecanvas.core.database.ReferenceEntity
import app.referencecanvas.core.database.ReferenceTagEntity
import app.referencecanvas.core.database.TagEntity
import app.referencecanvas.core.filesystem.FileStore
import app.referencecanvas.core.filesystem.StorageMaintenance
import app.referencecanvas.core.util.DiagnosticsLog
import app.referencecanvas.core.util.WallClock
import app.referencecanvas.data.repository.SettingsRepositoryImpl
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real database, file store and backup engine end to end, using a local file instead of a SAF folder. */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var fileStore: FileStore
    private lateinit var maintenance: StorageMaintenance
    private lateinit var engine: BackupEngine
    private val clock = WallClock()
    private val photo = ByteArray(10_000) { (it % 251).toByte() }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "round-trip-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fileStore = FileStore(File(root, "files"))
        val diagnostics = DiagnosticsLog(context, clock)
        maintenance = StorageMaintenance(context, fileStore, db.referenceDao(), diagnostics, clock)
        val settings = SettingsRepositoryImpl(PreferenceDataStoreFactory.create { File(root, "settings.preferences_pb") })
        engine = BackupEngine(context, db, fileStore, settings, BackupSettingsStore(context), BackupDestination(context), maintenance, diagnostics, clock)
    }

    @After fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun storeFile(relative: String, bytes: ByteArray) {
        val staged = fileStore.stageBlocking(ByteArrayInputStream(bytes))
        fileStore.commit(staged.file, relative)
    }

    private fun seed() = runBlocking {
        db.folderDao().insert(FolderEntity("f", "Folder", 1, 1))
        db.boardDao().insert(BoardEntity("b", "Board ✓", 1, 1, canvasOffsetX = -9_876_543.21, folderId = "f"))
        db.referenceDao().insert(
            ReferenceEntity("r", "Sunset", "sunset.png", "image/png", 1, 1, localRelativePath = "references/r.png", fileSizeBytes = photo.size.toLong(), folderId = "f"),
        )
        db.boardReferenceDao().insert(BoardReferenceEntity("b", "r", positionX = 1e7, positionY = -42.5, zIndex = 3))
        db.tagDao().insert(TagEntity("t", "golden"))
        db.tagDao().attach(listOf(ReferenceTagEntity("r", "t")))
        db.annotationDao().insertAll(listOf(AnnotationEntity("a", "r", "Pen", """{"kind":"Pen","color":-1,"width":0.01,"points":[{"x":0.1,"y":0.2}],"seq":1}""", 1, 1)))
        storeFile("references/r.png", photo)
    }

    private fun backupToFile(): Pair<File, Map<String, app.referencecanvas.core.backup.EntryDigest>> = runBlocking {
        val archive = File(root, "backup.zip")
        FileOutputStream(archive).use { engine.writeBackupTo(it, File(root, "work")) }
        val scan = FileInputStream(archive).use { BackupReader.scan(it, DATABASE_VERSION) }
        assertTrue("backup must validate", scan is BackupScan.Ok)
        archive to (scan as BackupScan.Ok).digests
    }

    @Test fun backupThenRestoreBringsEverythingBack() = runBlocking {
        seed()
        val (archive, digests) = backupToFile()

        // Lose it all, as if on a fresh install.
        db.boardDao().deleteById("b")
        db.referenceDao().deleteById("r")
        db.folderDao().delete("f")
        db.tagDao().delete("t")
        fileStore.delete("references/r.png")
        assertNull(db.referenceDao().getById("r"))

        val state = engine.restoreFrom({ FileInputStream(archive) }, digests)

        assertTrue("expected Done, got $state", state is RestoreState.Done)
        val board = db.boardDao().getById("b")!!
        assertEquals("Board ✓", board.name)
        assertEquals(-9_876_543.21, board.canvasOffsetX, 0.0)
        assertEquals("f", board.folderId)
        assertEquals(1e7, db.boardReferenceDao().getMembership("b", "r")!!.positionX, 0.0)
        assertEquals("golden", db.tagDao().findByName("golden")!!.name)
        assertEquals(1, db.tagDao().usageFor(listOf("r")).single().uses)
        assertEquals(1, db.annotationDao().getFor("r").size)
        assertArrayEquals(photo, fileStore.resolve("references/r.png")!!.readBytes())
    }

    @Test fun aRestoreThatFailsValidationLeavesTheLibraryUntouched() = runBlocking {
        seed()
        val (archive, _) = backupToFile()
        db.boardDao().insert(BoardEntity("only-in-live-data", "Keep me", 5, 5))

        // Digests the user never confirmed: the archive is treated as changed and the transaction is abandoned.
        val state = engine.restoreFrom({ FileInputStream(archive) }, emptyMap())

        assertEquals(RestoreState.Failed(RestoreFailure.Mismatch), state)
        assertNotNull(db.boardDao().getById("only-in-live-data"))
        assertNotNull(db.boardDao().getById("b"))
    }

    @Test fun aDamagedArchiveIsRefusedByValidation() = runBlocking {
        seed()
        val (archive, _) = backupToFile()
        val bytes = archive.readBytes()
        val damaged = File(root, "damaged.zip").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }

        val scan = FileInputStream(damaged).use { BackupReader.scan(it, DATABASE_VERSION) }

        assertTrue(scan is BackupScan.Bad)
    }

    @Test fun orphanCleanupRemovesOnlyOldUnreferencedFiles() = runBlocking {
        seed()
        storeFile("references/old-orphan.bin", byteArrayOf(1))
        storeFile("references/new-orphan.bin", byteArrayOf(2))
        val dir = fileStore.referencesDir()
        File(dir, "old-orphan.bin").setLastModified(System.currentTimeMillis() - 3 * 60 * 60 * 1000)
        File(dir, "r.png").setLastModified(System.currentTimeMillis() - 3 * 60 * 60 * 1000)

        val result = maintenance.cleanOrphans()!!

        assertEquals(1, result.deletedFiles)
        assertFalse(File(dir, "old-orphan.bin").exists())
        assertTrue("recent file may belong to an in-flight import", File(dir, "new-orphan.bin").exists())
        assertTrue("referenced files are never touched", File(dir, "r.png").exists())
    }
}
