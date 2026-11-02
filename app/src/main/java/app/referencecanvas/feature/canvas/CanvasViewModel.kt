package app.referencecanvas.feature.canvas

import android.database.sqlite.SQLiteException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.data.repository.BoardRepository
import app.referencecanvas.data.repository.CanvasRepository
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.data.repository.SettingsRepository
import app.referencecanvas.domain.canvas.AlignMode
import app.referencecanvas.domain.canvas.CanvasCard
import app.referencecanvas.domain.canvas.CanvasOps
import app.referencecanvas.domain.canvas.CanvasViewport
import app.referencecanvas.domain.canvas.CardLayout
import app.referencecanvas.domain.canvas.GRID_STEP
import app.referencecanvas.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CanvasEvent { DuplicateIncomplete, SaveFailed }

data class HistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false)

@HiltViewModel
class CanvasViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boards: BoardRepository,
    private val references: ReferenceRepository,
    private val canvas: CanvasRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val boardId: String = checkNotNull(savedStateHandle["boardId"])

    // ---- cards: database state, with optimistic overrides until the database reflects a change ----
    private val dbCards: StateFlow<List<CanvasCard>?> = canvas.observeCards(boardId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val overrides = MutableStateFlow<Map<String, CardLayout>>(emptyMap())

    val cards: StateFlow<List<CanvasCard>?> = combine(dbCards, overrides) { db, ov ->
        if (db == null || ov.isEmpty()) {
            db
        } else {
            db.map { c -> ov[c.id]?.let { l -> c.copy(x = l.x, y = l.y, z = l.z) } ?: c }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val appSettings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // ---- viewport ----
    private val viewportLoaded = MutableStateFlow(false)
    private var viewport = CanvasViewport()
    private var needsInitialFit = false
    private var saveJob: Job? = null

    val ready: StateFlow<Boolean> = combine(dbCards, viewportLoaded) { cards, loaded -> cards != null && loaded }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- selection ----
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()
    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    // ---- undo / redo (bounded history of layout changes) ----
    private class LayoutChange(val before: List<CardLayout>, val after: List<CardLayout>)
    private val undoStack = ArrayDeque<LayoutChange>()
    private val redoStack = ArrayDeque<LayoutChange>()
    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history.asStateFlow()

    private val _events = MutableSharedFlow<CanvasEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CanvasEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            boards.observeBoard(boardId).first()?.let {
                viewport = CanvasViewport(it.canvasOffsetX, it.canvasOffsetY, it.canvasZoom)
                // A board that has never been navigated starts fitted to its content.
                needsInitialFit = it.canvasOffsetX == 0.0 && it.canvasOffsetY == 0.0 && it.canvasZoom == 1f
            }
            viewportLoaded.value = true
        }
        viewModelScope.launch {
            dbCards.collect { db ->
                if (db != null && overrides.value.isNotEmpty()) {
                    val byId = db.associateBy { it.id }
                    overrides.update { current ->
                        current.filter { (id, l) -> byId[id]?.let { it.x != l.x || it.y != l.y || it.z != l.z } ?: false }
                    }
                }
            }
        }
    }

    fun currentViewport(): CanvasViewport = viewport

    fun consumeInitialFit(): Boolean = needsInitialFit.also { needsInitialFit = false }

    fun onViewportChanged(vp: CanvasViewport) {
        viewport = vp
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            canvas.saveViewport(boardId, vp)
        }
    }

    /** Persists the latest viewport immediately (called when the screen stops or is left). */
    fun flushViewport() {
        saveJob?.cancel()
        val vp = viewport
        saveJob = viewModelScope.launch { canvas.saveViewport(boardId, vp) }
    }

    fun fileFor(relativePath: String?): File? = canvas.fileFor(relativePath)

    // ---- selection ----
    fun toggleSelected(id: String) { _selection.update { if (id in it) it - id else it + id } }
    fun select(ids: Set<String>) { _selection.value = ids }
    fun clearSelection() { _selection.value = emptySet() }
    fun setSelectMode(on: Boolean) { _selectMode.value = on }
    fun selectAll() { _selection.value = cards.value.orEmpty().mapTo(HashSet()) { it.id } }

    // ---- settings toggles ----
    fun setSnap(on: Boolean) { viewModelScope.launch { settingsRepository.setSnapToGrid(on) } }
    fun setShowGrid(on: Boolean) { viewModelScope.launch { settingsRepository.setShowCanvasGrid(on) } }

    // ---- layout operations ----
    fun commitMove(ids: Set<String>, dx: Double, dy: Double) {
        val moving = cards.value.orEmpty().filter { it.id in ids }
        if (moving.isEmpty()) return
        var mx = dx
        var my = dy
        if (appSettings.value.snapToGrid) {
            // Snap the anchor card; everyone else keeps their position relative to it.
            val anchor = moving.first()
            mx = CanvasOps.snap(anchor.x + dx) - anchor.x
            my = CanvasOps.snap(anchor.y + dy) - anchor.y
        }
        applyLayouts(moving.map(::layoutOf), CanvasOps.move(moving, mx, my))
    }

    fun nudge(stepsX: Int, stepsY: Int) {
        val chosen = selectedCards()
        applyLayouts(chosen.map(::layoutOf), CanvasOps.move(chosen, stepsX * GRID_STEP, stepsY * GRID_STEP))
    }

    fun bringToFront() {
        val all = cards.value ?: return
        applyLayouts(selectedCards().map(::layoutOf), CanvasOps.bringToFront(all, _selection.value))
    }

    fun sendToBack() {
        val all = cards.value ?: return
        applyLayouts(selectedCards().map(::layoutOf), CanvasOps.sendToBack(all, _selection.value))
    }

    fun align(mode: AlignMode) {
        val chosen = selectedCards()
        if (chosen.size < 2) return
        applyLayouts(chosen.map(::layoutOf), CanvasOps.align(chosen, mode))
    }

    fun arrangeInGrid() {
        val chosen = selectedCards()
        if (chosen.size < 2) return
        applyLayouts(chosen.map(::layoutOf), CanvasOps.gridArrange(chosen))
    }

    fun duplicateSelected() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val created = canvas.duplicate(boardId, ids)
            if (created.isNotEmpty()) _selection.value = created.toSet()
            if (created.size < ids.size) _events.tryEmit(CanvasEvent.DuplicateIncomplete)
        }
    }

    fun removeSelected() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            references.removeFromBoard(boardId, ids)
            clearSelection()
            undoStack.clear()
            redoStack.clear()
            publishHistory()
        }
    }

    fun undo() {
        val change = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(change)
        publishHistory()
        write(change.before)
    }

    fun redo() {
        val change = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(change)
        publishHistory()
        write(change.after)
    }

    // ---- internals ----
    private fun selectedCards(): List<CanvasCard> {
        val sel = _selection.value
        return cards.value.orEmpty().filter { it.id in sel }
    }

    private fun layoutOf(c: CanvasCard) = CardLayout(c.id, c.x, c.y, c.z)

    private fun applyLayouts(before: List<CardLayout>, after: List<CardLayout>) {
        if (after.isEmpty() || before == after) return
        write(after) {
            undoStack.addLast(LayoutChange(before, after))
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
            publishHistory()
        }
    }

    /** Shows [layouts] immediately, then persists them in one transaction. */
    private fun write(layouts: List<CardLayout>, onSaved: () -> Unit = {}) {
        overrides.update { it + layouts.associateBy { l -> l.id } }
        viewModelScope.launch {
            try {
                canvas.applyLayout(boardId, layouts)
                onSaved()
            } catch (e: SQLiteException) {
                val ids = layouts.mapTo(HashSet()) { it.id }
                overrides.update { current -> current.filterKeys { it !in ids } }
                _events.tryEmit(CanvasEvent.SaveFailed)
            }
        }
    }

    private fun publishHistory() {
        _history.value = HistoryState(undoStack.isNotEmpty(), redoStack.isNotEmpty())
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
        const val MAX_HISTORY = 100
    }
}
