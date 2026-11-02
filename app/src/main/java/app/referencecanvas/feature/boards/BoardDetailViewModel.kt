package app.referencecanvas.feature.boards

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.referencecanvas.data.importer.ImportEvent
import app.referencecanvas.data.importer.ImportReport
import app.referencecanvas.data.importer.ReferenceImporter
import app.referencecanvas.data.repository.BoardRepository
import app.referencecanvas.data.repository.FolderRepository
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.domain.model.LinkParser
import app.referencecanvas.data.repository.SettingsRepository
import app.referencecanvas.domain.model.Board
import app.referencecanvas.domain.model.BoardSortMode
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.domain.model.BoardViewMode
import app.referencecanvas.domain.model.GalleryDensity
import app.referencecanvas.domain.model.Reference
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BoardDetailState {
    data object Loading : BoardDetailState
    data object Missing : BoardDetailState
    data class Loaded(val board: Board) : BoardDetailState
}

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Running(val done: Int, val total: Int) : ImportUiState
    data class Done(val report: ImportReport) : ImportUiState
}

@HiltViewModel
class BoardDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boards: BoardRepository,
    private val references: ReferenceRepository,
    private val importer: ReferenceImporter,
    folders: FolderRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val boardId: String = checkNotNull(savedStateHandle["boardId"])

    val state: StateFlow<BoardDetailState> = boards.observeBoard(boardId)
        .map { if (it == null) BoardDetailState.Missing else BoardDetailState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardDetailState.Loading)

    val density: StateFlow<GalleryDensity> = settings.settings.map { it.galleryDensity }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryDensity.Comfortable)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<Reference>> = boards.observeBoard(boardId)
        .map { it?.sortMode ?: BoardSortMode.Manual }
        .distinctUntilChanged()
        .flatMapLatest { references.pagedForBoard(boardId, it) }
        .cachedIn(viewModelScope)

    val otherBoards: StateFlow<List<BoardSummary>> = boards.observeBoards()
        .map { list -> list.filter { it.board.id != boardId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allFolders: StateFlow<List<Folder>> = folders.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()
    private var importJob: Job? = null

    fun fileFor(reference: Reference): File? = references.fileFor(reference)

    fun setViewMode(mode: BoardViewMode) { viewModelScope.launch { boards.setViewMode(boardId, mode) } }
    fun setSortMode(mode: BoardSortMode) { viewModelScope.launch { boards.setSortMode(boardId, mode) } }

    // ---- selection ----
    fun toggleSelected(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }
    fun clearSelection() { _selection.value = emptySet() }

    fun favoriteSelected() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch { references.setFavorite(ids, true); clearSelection() }
    }

    fun removeSelectedFromBoard() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch { references.removeFromBoard(boardId, ids); clearSelection() }
    }

    fun addSelectedToBoard(targetBoardId: String) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch { references.addToBoard(targetBoardId, ids); clearSelection() }
    }

    fun trashSelected() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch { references.moveToTrash(ids); clearSelection() }
    }

    fun moveSelectedToFolder(folderId: String?) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch { references.setFolder(ids, folderId); clearSelection() }
    }

    /** Returns false (and stores nothing) if [raw] isn't a plain http(s) link. */
    fun addLink(raw: String): Boolean {
        if (LinkParser.parse(raw) == null) return false
        viewModelScope.launch { references.addLink(boardId, raw) }
        return true
    }

    // ---- import ----
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _importState.value = ImportUiState.Running(0, uris.size)
            var report: ImportReport? = null
            try {
                importer.import(boardId, uris).collect { event ->
                    when (event) {
                        is ImportEvent.Progress -> _importState.value = ImportUiState.Running(event.done, event.total)
                        is ImportEvent.Finished -> report = event.report
                    }
                }
            } catch (e: CancellationException) {
                _importState.value = ImportUiState.Idle
                throw e
            }
            _importState.value = report?.let { ImportUiState.Done(it) } ?: ImportUiState.Idle
        }
    }

    fun cancelImport() { importJob?.cancel() }

    fun dismissImportReport() { _importState.value = ImportUiState.Idle }

    fun retryFailed(report: ImportReport) {
        _importState.value = ImportUiState.Idle
        importUris(report.failures.map { it.uri })
    }
}
