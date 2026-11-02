package app.referencecanvas.feature.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.data.repository.AnnotationRepository
import app.referencecanvas.data.repository.BoardRepository
import app.referencecanvas.domain.annotation.AnnotationData
import app.referencecanvas.data.repository.FolderRepository
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.domain.model.NO_BOARD_CONTEXT
import app.referencecanvas.domain.model.BoardSortMode
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.domain.model.Reference
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The viewer pages through a snapshot of ids (strings only), taken in the board's current sort order.
 * Metadata and files are loaded per visible page.
 */
data class ViewerIds(val ids: List<String>, val startIndex: Int)

@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boards: BoardRepository,
    private val references: ReferenceRepository,
    folders: FolderRepository,
    private val annotations: AnnotationRepository,
) : ViewModel() {
    val boardId: String = checkNotNull(savedStateHandle["boardId"])
    private val startId: String = checkNotNull(savedStateHandle["referenceId"])

    private val _ids = MutableStateFlow<ViewerIds?>(null)
    val ids: StateFlow<ViewerIds?> = _ids.asStateFlow()

    val otherBoards: StateFlow<List<BoardSummary>> = boards.observeBoards()
        .map { list -> list.filter { it.board.id != boardId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allFolders: StateFlow<List<Folder>> = folders.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            if (boardId == NO_BOARD_CONTEXT) {
                // Opened from search: no board order to page through.
                _ids.value = ViewerIds(listOf(startId), 0)
                return@launch
            }
            val sort = boards.observeBoard(boardId).first()?.sortMode ?: BoardSortMode.Manual
            val list = references.orderedIds(boardId, sort)
            _ids.value = ViewerIds(list, list.indexOf(startId).coerceAtLeast(0))
        }
    }

    fun observe(id: String): Flow<Reference?> = references.observe(id)
    fun annotationsFor(id: String): Flow<List<AnnotationData>> = annotations.observe(id).map { set -> set.items.map { it.data } }
    fun annotationCount(id: String): Flow<Int> = annotations.observeCount(id)
    fun fileFor(reference: Reference): File? = references.fileFor(reference)
    fun markOpened(id: String) { viewModelScope.launch { references.markOpened(id) } }

    fun toggleFavorite(reference: Reference) {
        viewModelScope.launch { references.setFavorite(listOf(reference.id), !reference.isFavorite) }
    }

    fun setFolder(referenceId: String, folderId: String?) {
        viewModelScope.launch { references.setFolder(listOf(referenceId), folderId) }
    }

    fun addToBoard(targetBoardId: String, referenceId: String) {
        viewModelScope.launch { references.addToBoard(targetBoardId, listOf(referenceId)) }
    }

    fun moveToTrash(referenceId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            references.moveToTrash(listOf(referenceId))
            onDone()
        }
    }
}
