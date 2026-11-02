package app.referencecanvas.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.core.backup.BackupDestination
import app.referencecanvas.core.backup.BackupEngine
import app.referencecanvas.core.backup.BackupFileInfo
import app.referencecanvas.core.backup.BackupProgress
import app.referencecanvas.core.backup.BackupSchedule
import app.referencecanvas.core.backup.BackupScheduler
import app.referencecanvas.core.backup.BackupSettings
import app.referencecanvas.core.backup.BackupSettingsStore
import app.referencecanvas.core.backup.RestoreState
import app.referencecanvas.core.filesystem.CleanupResult
import app.referencecanvas.core.filesystem.StorageMaintenance
import app.referencecanvas.core.filesystem.StorageUsage
import app.referencecanvas.core.util.DiagnosticsLog
import app.referencecanvas.data.repository.ReferenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val engine: BackupEngine,
    private val store: BackupSettingsStore,
    private val destination: BackupDestination,
    private val scheduler: BackupScheduler,
    private val maintenance: StorageMaintenance,
    private val diagnostics: DiagnosticsLog,
    references: ReferenceRepository,
) : ViewModel() {

    val backup: StateFlow<BackupSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettings())
    val progress: StateFlow<BackupProgress> = engine.progress
    val restoreState: StateFlow<RestoreState> = engine.restoreState
    val trashCount: StateFlow<Int> = references.observeTrashCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _destinationOk = MutableStateFlow<Boolean?>(null)
    val destinationOk: StateFlow<Boolean?> = _destinationOk.asStateFlow()
    private val _backups = MutableStateFlow<List<BackupFileInfo>>(emptyList())
    val backups: StateFlow<List<BackupFileInfo>> = _backups.asStateFlow()
    private val _usage = MutableStateFlow<StorageUsage?>(null)
    val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

    init {
        // Re-check the folder and the storage figures whenever a backup/restore finishes.
        viewModelScope.launch { engine.progress.collect { if (it is BackupProgress.Idle) refresh() } }
    }

    /** Re-reads destination health, the list of backups in it, and storage usage. */
    fun refresh() {
        viewModelScope.launch {
            _usage.value = maintenance.usage()
            val uriText = backup.value.destinationUri
            if (uriText == null) {
                _destinationOk.value = null
                _backups.value = emptyList()
            } else {
                val tree = Uri.parse(uriText)
                val ok = withContext(Dispatchers.IO) { destination.isAccessible(tree) }
                _destinationOk.value = ok
                _backups.value = if (ok) withContext(Dispatchers.IO) { destination.listBackups(tree) } else emptyList()
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                destination.persistPermission(uri)
            } catch (_: SecurityException) {
                _destinationOk.value = false
                return@launch
            }
            val name = withContext(Dispatchers.IO) { destination.displayName(uri) } ?: "Folder"
            store.setDestination(uri.toString(), name)
            refresh()
        }
    }

    fun clearDestination() {
        viewModelScope.launch {
            backup.value.destinationUri?.let { destination.releasePermission(Uri.parse(it)) }
            store.clearDestination()
            scheduler.apply(BackupSchedule.Off)
            refresh()
        }
    }

    fun setSchedule(schedule: BackupSchedule) {
        viewModelScope.launch {
            store.setSchedule(schedule)
            scheduler.apply(schedule)
        }
    }

    fun backupNow() = engine.startBackup()
    fun cancel() = engine.cancel()
    fun beginRestore(uri: Uri) = engine.beginRestore(uri)
    fun confirmRestore() = engine.confirmRestore()
    fun dismissRestore() = engine.dismissRestore()

    fun clearCache(onDone: (Long) -> Unit) {
        viewModelScope.launch { onDone(maintenance.clearCache()); refresh() }
    }

    fun cleanUnusedFiles(onDone: (CleanupResult?) -> Unit) {
        viewModelScope.launch { onDone(maintenance.cleanOrphans()); refresh() }
    }

    suspend fun exportDiagnostics(): File? = diagnostics.export()
}
