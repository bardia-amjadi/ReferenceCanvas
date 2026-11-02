package app.referencecanvas.core.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.referencecanvas.BuildConfig
import app.referencecanvas.core.database.AppDatabase
import app.referencecanvas.core.database.DATABASE_VERSION
import app.referencecanvas.core.filesystem.FileStore
import app.referencecanvas.core.filesystem.StorageMaintenance
import app.referencecanvas.core.util.Clock
import app.referencecanvas.core.util.DiagnosticsLog
import app.referencecanvas.core.util.toEnumOr
import app.referencecanvas.data.repository.SettingsRepository
import app.referencecanvas.domain.model.AccentOption
import app.referencecanvas.domain.model.BoardViewMode
import app.referencecanvas.domain.model.GalleryDensity
import app.referencecanvas.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface BackupProgress {
    data object Idle : BackupProgress
    data class Running(val kind: Kind, val phase: Phase, val done: Long, val total: Long) : BackupProgress
    enum class Kind { Backup, Restore }
    enum class Phase { Preparing, Database, Files, Verifying, Pruning, Restoring }
}

sealed interface BackupResult {
    data class Success(val fileName: String, val bytes: Long, val files: Int, val missingFiles: Int) : BackupResult
    data class Failure(val outcome: BackupOutcome) : BackupResult {
        /** Worth an automatic retry later; user-fixable problems (no destination, no permission, no space) are not. */
        val retryable: Boolean get() = outcome == BackupOutcome.Io || outcome == BackupOutcome.Unknown
    }
}

enum class RestoreFailure { Unreadable, Io, NoSpace, Database, Mismatch }

sealed interface RestoreState {
    data object Idle : RestoreState
    data class Validating(val bytesRead: Long) : RestoreState
    data class Confirm(val source: Uri, val manifest: BackupManifest, val digests: Map<String, EntryDigest>) : RestoreState
    data class Invalid(val reason: InvalidReason) : RestoreState
    data object Restoring : RestoreState
    data class Done(val rows: Int, val files: Int, val missingFiles: Int) : RestoreState
    data class Failed(val reason: RestoreFailure) : RestoreState
}

/**
 * Creates, verifies and restores backups. A backup counts as successful only after the finished file has been read
 * back and passed full validation; a restore changes nothing until the whole archive has validated and the user has
 * confirmed, and the database swap is a single transaction that rolls back on any problem.
 */
@Singleton
class BackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val fileStore: FileStore,
    private val settings: SettingsRepository,
    private val store: BackupSettingsStore,
    private val destination: BackupDestination,
    private val maintenance: StorageMaintenance,
    private val diagnostics: DiagnosticsLog,
    private val clock: Clock,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runLock = Mutex()
    private var job: Job? = null

    private val _progress = MutableStateFlow<BackupProgress>(BackupProgress.Idle)
    val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restore.asStateFlow()

    // ------------------------------------------------------------------ backup

    /** Starts a backup that keeps running if the user leaves the screen. */
    fun startBackup() {
        if (job?.isActive == true) return
        job = appScope.launch { backupNow() }
    }

    fun cancel() { job?.cancel() }

    suspend fun backupNow(): BackupResult = runLock.withLock {
        diagnostics.record("backup", "started")
        val result = try {
            runBackup()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                store.recordAttempt(BackupOutcome.Cancelled, clock.now())
                diagnostics.record("backup", "cancelled")
                _progress.value = BackupProgress.Idle
            }
            throw e
        }
        withContext(NonCancellable) {
            when (result) {
                is BackupResult.Success -> {
                    store.recordAttempt(BackupOutcome.Success, clock.now(), result.bytes, result.files)
                    diagnostics.record("backup", "success files=${result.files} missing=${result.missingFiles} bytes=${result.bytes}")
                }
                is BackupResult.Failure -> {
                    store.recordAttempt(result.outcome, clock.now())
                    diagnostics.record("backup", "failed outcome=${result.outcome}")
                }
            }
            _progress.value = BackupProgress.Idle
        }
        result
    }

    private class Snapshot(val tables: Map<String, File>, val paths: File)

    private suspend fun runBackup(): BackupResult {
        val current = store.settings.first()
        val treeText = current.destinationUri ?: return BackupResult.Failure(BackupOutcome.NoDestination)
        val tree = Uri.parse(treeText)
        if (!withContext(Dispatchers.IO) { destination.isAccessible(tree) }) {
            return BackupResult.Failure(BackupOutcome.DestinationUnavailable)
        }
        report(BackupProgress.Phase.Preparing, 0, 0)
        val work = File(context.cacheDir, "backup-tmp").apply { deleteRecursively(); mkdirs() }
        var created: Uri? = null
        var succeeded = false
        try {
            report(BackupProgress.Phase.Database, 0, 0)
            val snapshot = snapshotDatabase(work)
            val name = backupFileName()
            val doc = withContext(Dispatchers.IO) { destination.createBackupFile(tree, name) }
                ?: return BackupResult.Failure(BackupOutcome.DestinationUnavailable)
            created = doc

            val manifest = withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(doc, "w") ?: throw IOException("cannot open destination")
                writeArchive(out, snapshot)
            }

            report(BackupProgress.Phase.Verifying, 0, manifest.fileBytes)
            val scan = withContext(Dispatchers.IO) {
                val ctx = currentCoroutineContext()
                (context.contentResolver.openInputStream(doc) ?: throw IOException("cannot reopen backup")).use {
                    BackupReader.scan(it, DATABASE_VERSION, checkCancelled = { ctx.ensureActive() }, onBytes = { b -> report(BackupProgress.Phase.Verifying, b, manifest.fileBytes) })
                }
            }
            if (scan !is BackupScan.Ok || scan.manifest != manifest) {
                diagnostics.record("backup", "verification failed")
                return BackupResult.Failure(BackupOutcome.VerificationFailed)
            }
            succeeded = true

            report(BackupProgress.Phase.Pruning, 0, 0)
            withContext(Dispatchers.IO) {
                // Only now, with the new backup proven good, are the oldest ones retired.
                destination.listBackups(tree).drop(KEEP_BACKUPS).forEach { destination.delete(it.uri) }
            }
            val bytes = withContext(Dispatchers.IO) { destination.listBackups(tree).firstOrNull { it.name == name }?.sizeBytes ?: 0L }
            return BackupResult.Success(name, bytes, manifest.fileCount, manifest.missingFiles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return BackupResult.Failure(if (e.message?.contains("ENOSPC") == true) BackupOutcome.NoSpace else BackupOutcome.Io)
        } catch (e: SQLiteException) {
            return BackupResult.Failure(BackupOutcome.Database)
        } catch (e: SecurityException) {
            return BackupResult.Failure(BackupOutcome.DestinationUnavailable)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                work.deleteRecursively()
                // A half-written or unverified file must never be left looking like a backup.
                if (!succeeded) created?.let { destination.delete(it) }
            }
        }
    }

    /** Dumps every table to local temp files inside one short transaction, so the archive is a consistent snapshot. */
    private suspend fun snapshotDatabase(dir: File): Snapshot = db.withTransaction {
        val sdb = db.openHelper.writableDatabase
        val tables = LinkedHashMap<String, File>()
        val paths = File(dir, "paths.txt")
        paths.bufferedWriter().use { pathsOut ->
            for (table in BackupFormat.TABLES) {
                val file = File(dir, "$table.jsonl").also { tables[table] = it }
                file.bufferedWriter().use { out -> dumpTable(sdb, table, out, if (table == "reference_items") pathsOut else null) }
            }
        }
        Snapshot(tables, paths)
    }

    private fun dumpTable(sdb: SupportSQLiteDatabase, table: String, out: BufferedWriter, pathsOut: BufferedWriter?) {
        require(table in BackupFormat.TABLES)
        sdb.query("SELECT * FROM $table").use { c ->
            val names = c.columnNames
            val pathColumn = names.indexOf("localRelativePath")
            while (c.moveToNext()) {
                out.write(rowToJson(c, names).toString())
                out.newLine()
                if (pathsOut != null && pathColumn >= 0 && !c.isNull(pathColumn)) {
                    pathsOut.write(c.getString(pathColumn))
                    pathsOut.newLine()
                }
            }
        }
    }

    private fun rowToJson(c: Cursor, names: Array<String>): JsonObject = buildJsonObject {
        for (i in names.indices) {
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> put(names[i], JsonNull)
                Cursor.FIELD_TYPE_INTEGER -> put(names[i], c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> put(names[i], c.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> put(names[i], c.getString(i))
                else -> throw IOException("unsupported column type") // no BLOB columns exist in this schema
            }
        }
    }

    /** Snapshots the database and streams a complete archive to [output]. Used by [backupNow]; internal for tests. */
    internal suspend fun writeBackupTo(output: java.io.OutputStream, workDir: File): BackupManifest {
        workDir.mkdirs()
        return writeArchive(output, snapshotDatabase(workDir))
    }

    private suspend fun writeArchive(out: java.io.OutputStream, snapshot: Snapshot): BackupManifest {
        val ctx = currentCoroutineContext()
        BackupWriter(out, BuildConfig.VERSION_NAME, DATABASE_VERSION, clock.now()).use { writer ->
            for (table in BackupFormat.TABLES) {
                ctx.ensureActive()
                snapshot.tables.getValue(table).useLines { writer.writeTable(table, it) }
            }
            writer.writePrefs(prefsJson())

            val total = snapshot.paths.useLines { it.count() }.toLong()
            var done = 0L
            var missing = 0
            report(BackupProgress.Phase.Files, 0, total)
            snapshot.paths.useLines { lines ->
                for (relative in lines) {
                    ctx.ensureActive()
                    val file = fileStore.resolve(relative)
                    if (file == null || !file.isFile || !BackupFormat.isSafeEntryName(BackupFormat.FILE_DIR + relative)) {
                        missing++
                    } else {
                        try {
                            FileInputStream(file).use { writer.writeFile(relative, it) }
                        } catch (_: FileNotFoundException) {
                            missing++ // removed while the backup ran
                        }
                    }
                    done++
                    if (done % 20 == 0L || done == total) report(BackupProgress.Phase.Files, done, total)
                }
            }
            return writer.finish(missing)
        }
    }

    private suspend fun prefsJson(): JsonObject {
        val s = settings.settings.first()
        return buildJsonObject {
            put("accent", s.accent.name)
            put("themeMode", s.themeMode.name)
            put("defaultViewMode", s.defaultViewMode.name)
            put("galleryDensity", s.galleryDensity.name)
            put("snapToGrid", s.snapToGrid)
            put("showCanvasGrid", s.showCanvasGrid)
        }
    }

    private suspend fun applyPrefs(p: JsonObject) {
        fun text(key: String) = (p[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(key: String) = (p[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        settings.setAccent(text("accent").toEnumOr(AccentOption.Violet))
        settings.setThemeMode(text("themeMode").toEnumOr(ThemeMode.Dark))
        settings.setDefaultViewMode(text("defaultViewMode").toEnumOr(BoardViewMode.Canvas))
        settings.setGalleryDensity(text("galleryDensity").toEnumOr(GalleryDensity.Comfortable))
        flag("snapToGrid")?.let { settings.setSnapToGrid(it) }
        flag("showCanvasGrid")?.let { settings.setShowCanvasGrid(it) }
    }

    private fun backupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(clock.now()))
        return "${BackupDestination.NAME_PREFIX}$stamp${BackupDestination.NAME_SUFFIX}"
    }

    private fun report(phase: BackupProgress.Phase, done: Long, total: Long) {
        _progress.value = BackupProgress.Running(BackupProgress.Kind.Backup, phase, done, total)
    }

    // ----------------------------------------------------------------- restore

    /** Step 1: read the whole backup and check it. Nothing on the device is changed. */
    fun beginRestore(source: Uri) {
        if (job?.isActive == true) return
        job = appScope.launch {
            runLock.withLock {
                _restore.value = RestoreState.Validating(0)
                diagnostics.record("restore", "validation started")
                val outcome = try {
                    withContext(Dispatchers.IO) {
                        val ctx = currentCoroutineContext()
                        val stream = context.contentResolver.openInputStream(source) ?: throw IOException("cannot open backup")
                        stream.use {
                            BackupReader.scan(it, DATABASE_VERSION, checkCancelled = { ctx.ensureActive() }, onBytes = { b -> _restore.value = RestoreState.Validating(b) })
                        }
                    }
                } catch (e: CancellationException) {
                    _restore.value = RestoreState.Idle
                    throw e
                } catch (e: IOException) {
                    BackupScan.Bad(InvalidReason.Unreadable)
                } catch (e: SecurityException) {
                    BackupScan.Bad(InvalidReason.Unreadable)
                }
                _restore.value = when (outcome) {
                    is BackupScan.Ok -> RestoreState.Confirm(source, outcome.manifest, outcome.digests)
                    is BackupScan.Bad -> RestoreState.Invalid(outcome.reason).also { diagnostics.record("restore", "invalid reason=${outcome.reason}") }
                }
            }
        }
    }

    fun dismissRestore() {
        if (_restore.value !is RestoreState.Restoring) _restore.value = RestoreState.Idle
    }

    /** Step 2, after the user confirmed: replace the library with the backup's contents, all or nothing. */
    fun confirmRestore() {
        val pending = _restore.value as? RestoreState.Confirm ?: return
        if (job?.isActive == true) return
        job = appScope.launch {
            runLock.withLock {
                _restore.value = RestoreState.Restoring
                _progress.value = BackupProgress.Running(BackupProgress.Kind.Restore, BackupProgress.Phase.Restoring, 0, pending.manifest.fileBytes)
                diagnostics.record("restore", "started")
                try {
                    _restore.value = applyRestore(
                        open = { context.contentResolver.openInputStream(pending.source) ?: throw IOException("cannot open backup") },
                        expected = pending.digests,
                        totalBytes = pending.manifest.fileBytes,
                        missingFiles = pending.manifest.missingFiles,
                    )
                } catch (e: CancellationException) {
                    _restore.value = RestoreState.Idle
                    diagnostics.record("restore", "cancelled; library unchanged")
                    throw e
                } finally {
                    withContext(NonCancellable) { _progress.value = BackupProgress.Idle }
                }
            }
        }
    }

    private class RestoreDataException(message: String) : Exception(message)

    /** Replaces the library with the contents of an already-validated archive. Internal for tests. */
    internal suspend fun restoreFrom(open: () -> InputStream, expected: Map<String, EntryDigest>): RestoreState =
        applyRestore(open, expected, totalBytes = 0, missingFiles = 0)

    private suspend fun applyRestore(
        open: () -> InputStream,
        expected: Map<String, EntryDigest>,
        totalBytes: Long,
        missingFiles: Int,
    ): RestoreState {
        val ctx = currentCoroutineContext()
        var rows = 0
        var files = 0
        var prefs: JsonObject? = null
        try {
            db.withTransaction {
                val sdb = db.openHelper.writableDatabase
                sdb.execSQL("PRAGMA defer_foreign_keys = ON")
                val columns = BackupFormat.TABLES.associateWith { columnsOf(sdb, it) }
                BackupFormat.TABLES.asReversed().forEach { sdb.execSQL("DELETE FROM $it") }

                val handler = object : RestoreHandler {
                    override fun row(table: String, row: JsonObject) {
                        val allowed = columns.getValue(table)
                        val values = ContentValues()
                        for ((key, value) in row) {
                            if (key !in allowed) throw RestoreDataException("unknown column")
                            val primitive = value as? JsonPrimitive
                            when {
                                value is JsonNull -> values.putNull(key)
                                primitive == null -> throw RestoreDataException("bad value")
                                primitive.isString -> values.put(key, primitive.content)
                                else -> primitive.content.toLongOrNull()?.let { values.put(key, it) }
                                    ?: values.put(key, primitive.content.toDouble())
                            }
                        }
                        if (sdb.insert(table, SQLiteDatabase.CONFLICT_ABORT, values) == -1L) throw RestoreDataException("insert failed")
                        rows++
                    }

                    override fun file(relativePath: String, content: InputStream) {
                        val staged = fileStore.stageBlocking(content) { ctx.ensureActive() }
                        fileStore.commit(staged.file, relativePath)
                        files++
                    }

                    var capturedPrefs: JsonObject? = null
                    override fun prefs(prefs: JsonObject) { capturedPrefs = prefs }
                }
                val scan = open().use {
                    BackupReader.scan(
                        it, DATABASE_VERSION, handler,
                        checkCancelled = { ctx.ensureActive() },
                        onBytes = { b -> _progress.value = BackupProgress.Running(BackupProgress.Kind.Restore, BackupProgress.Phase.Restoring, b, totalBytes) },
                    )
                }
                // The archive must be byte-for-byte what the user confirmed; otherwise the transaction is abandoned.
                if (scan !is BackupScan.Ok || scan.digests != expected) throw RestoreDataException("archive changed")
                prefs = handler.capturedPrefs
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestoreDataException) {
            diagnostics.record("restore", "failed: data mismatch; library unchanged")
            return RestoreState.Failed(RestoreFailure.Mismatch)
        } catch (e: SQLiteException) {
            diagnostics.record("restore", "failed: database; library unchanged")
            return RestoreState.Failed(RestoreFailure.Database)
        } catch (e: IOException) {
            val full = e.message?.contains("ENOSPC") == true
            diagnostics.record("restore", "failed: io full=$full; library unchanged")
            return RestoreState.Failed(if (full) RestoreFailure.NoSpace else RestoreFailure.Io)
        } catch (e: SecurityException) {
            return RestoreState.Failed(RestoreFailure.Unreadable)
        }

        // Committed. Everything below is best-effort tidying, and cannot undo the restore.
        prefs?.let { applyPrefs(it) }
        db.invalidationTracker.refreshVersionsAsync()
        maintenance.cleanOrphans()
        diagnostics.record("restore", "success rows=$rows files=$files")
        return RestoreState.Done(rows, files, missingFiles)
    }

    private fun columnsOf(sdb: SupportSQLiteDatabase, table: String): Set<String> {
        require(table in BackupFormat.TABLES)
        return sdb.query("PRAGMA table_info($table)").use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            buildSet { while (c.moveToNext()) add(c.getString(nameIndex)) }
        }
    }

    private companion object {
        const val KEEP_BACKUPS = 3
    }
}
