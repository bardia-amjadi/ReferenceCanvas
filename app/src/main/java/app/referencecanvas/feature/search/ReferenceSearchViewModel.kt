package app.referencecanvas.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.referencecanvas.data.repository.BoardRepository
import app.referencecanvas.data.repository.FolderRepository
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.data.repository.SearchRepository
import app.referencecanvas.data.repository.TagRepository
import app.referencecanvas.domain.model.Board
import app.referencecanvas.domain.model.BoardRef
import app.referencecanvas.domain.model.DateRange
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.domain.model.Reference
import app.referencecanvas.domain.model.SearchCategory
import app.referencecanvas.domain.model.SearchFilters
import app.referencecanvas.domain.model.SearchSort
import app.referencecanvas.domain.model.Tag
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReferenceSearchViewModel @Inject constructor(
    private val search: SearchRepository,
    private val tags: TagRepository,
    boards: BoardRepository,
    folders: FolderRepository,
    private val references: ReferenceRepository,
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    /** Everything except the text, which is debounced separately. */
    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    @OptIn(FlowPreview::class)
    private val effective: Flow<SearchFilters> =
        combine(_text.debounce(250), _filters) { t, f -> f.copy(text = t.trim()) }.distinctUntilChanged()

    val hasCriteria: StateFlow<Boolean> = effective.map { it.hasCriteria }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val results: Flow<PagingData<Reference>> = effective
        .flatMapLatest { if (it.hasCriteria) search.search(it) else flowOf(PagingData.empty()) }
        .cachedIn(viewModelScope)

    val recent: StateFlow<List<String>> = search.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val tagSuggestions: StateFlow<List<Tag>> = _text.debounce(150)
        .flatMapLatest { t -> flow { emit(tags.suggest(t)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<Tag>> = tags.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allBoards: StateFlow<List<Board>> = boards.observeBoards().map { rows -> rows.map { it.board } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allFolders: StateFlow<List<Folder>> = folders.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setText(value: String) { _text.value = value }
    fun setCategory(v: SearchCategory) { _filters.value = _filters.value.copy(category = v) }
    fun setFavoritesOnly(v: Boolean) { _filters.value = _filters.value.copy(favoritesOnly = v) }
    fun setTag(id: String?) { _filters.value = _filters.value.copy(tagId = id) }
    fun setBoard(id: String?) { _filters.value = _filters.value.copy(boardId = id) }
    fun setFolder(id: String?) { _filters.value = _filters.value.copy(folderId = id) }
    fun setDateRange(v: DateRange) { _filters.value = _filters.value.copy(dateRange = v) }
    fun setSort(v: SearchSort) { _filters.value = _filters.value.copy(sort = v) }

    fun clearFilters() {
        _text.value = ""
        _filters.value = SearchFilters(sort = _filters.value.sort)
    }

    fun remember(term: String) { viewModelScope.launch { search.remember(term) } }
    fun forget(term: String) { viewModelScope.launch { search.forget(term) } }
    fun clearRecent() { viewModelScope.launch { search.clearRecent() } }

    suspend fun boardsFor(referenceId: String): List<BoardRef> = search.boardsFor(referenceId)
    fun fileFor(reference: Reference): File? = references.fileFor(reference)
}
