package app.referencecanvas.feature.boards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.data.repository.BoardRepository
import app.referencecanvas.data.repository.FolderRepository
import app.referencecanvas.data.repository.SettingsRepository
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.domain.model.FolderTree
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shared by Home, Boards, Favorites and Search. A null list means "still loading". */
@HiltViewModel
class BoardsViewModel @Inject constructor(
    private val boards: BoardRepository,
    private val folders: FolderRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<BoardSummary>?> = _query
        .debounce { if (it.isEmpty()) 0L else 200L }
        .distinctUntilChanged()
        .flatMapLatest { boards.observeBoards(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val favorites: StateFlow<List<BoardSummary>?> = boards.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- folder browsing (Boards tab) ----
    private val _currentFolderId = MutableStateFlow<String?>(null)
    val currentFolderId: StateFlow<String?> = _currentFolderId.asStateFlow()

    val allFolders: StateFlow<List<Folder>> = folders.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subfolders: StateFlow<List<Folder>> = combine(allFolders, _currentFolderId) { all, current ->
        all.filter { it.parentId == current }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val breadcrumb: StateFlow<List<Folder>> = combine(allFolders, _currentFolderId) { all, current ->
        FolderTree.path(current, all.associateBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val folderBoards: StateFlow<List<BoardSummary>?> = _currentFolderId
        .flatMapLatest { boards.observeInFolder(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(value: String) { _query.value = value }
    fun openFolder(id: String?) { _currentFolderId.value = id }

    fun createBoard(name: String) {
        viewModelScope.launch {
            val id = boards.create(name, settings.settings.first().defaultViewMode)
            // Created while browsing a folder: put it there.
            if (id != null) _currentFolderId.value?.let { boards.moveToFolder(id, it) }
        }
    }

    fun rename(id: String, name: String) { viewModelScope.launch { boards.rename(id, name) } }
    fun toggleFavorite(summary: BoardSummary) { viewModelScope.launch { boards.setFavorite(summary.board.id, !summary.board.isFavorite) } }
    fun togglePin(summary: BoardSummary) { viewModelScope.launch { boards.setPinned(summary.board.id, !summary.board.isPinned) } }
    fun duplicate(id: String) { viewModelScope.launch { boards.duplicate(id) } }
    fun delete(id: String) { viewModelScope.launch { boards.delete(id) } }
    fun moveBoard(boardId: String, folderId: String?) { viewModelScope.launch { boards.moveToFolder(boardId, folderId) } }

    fun createFolder(name: String) { viewModelScope.launch { folders.create(name, _currentFolderId.value) } }
    fun renameFolder(id: String, name: String) { viewModelScope.launch { folders.rename(id, name) } }
    fun moveFolder(id: String, newParentId: String?) { viewModelScope.launch { folders.move(id, newParentId) } }

    fun deleteFolder(id: String) {
        viewModelScope.launch {
            val parent = allFolders.value.firstOrNull { it.id == id }?.parentId
            folders.delete(id)
            if (_currentFolderId.value == id) _currentFolderId.value = parent
        }
    }
}
