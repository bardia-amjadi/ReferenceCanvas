package app.referencecanvas.feature.annotate

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.data.exporter.AnnotationExporter
import app.referencecanvas.data.importer.ImportEvent
import app.referencecanvas.data.importer.ImportReport
import app.referencecanvas.data.importer.ReferenceImporter
import app.referencecanvas.data.repository.AnnotationRepository
import app.referencecanvas.data.repository.AnnotationSet
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.domain.annotation.Annotation
import app.referencecanvas.domain.annotation.AnnotationData
import app.referencecanvas.domain.annotation.AnnotationStyle
import app.referencecanvas.domain.model.NO_BOARD_CONTEXT
import app.referencecanvas.domain.model.Reference
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AnnotationTool { Hand, Pen, Highlighter, Eraser, Line, Arrow, Rect, Ellipse, Text, Select }

data class EditHistory(val canUndo: Boolean = false, val canRedo: Boolean = false)

@HiltViewModel
class AnnotationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val references: ReferenceRepository,
    private val annotations: AnnotationRepository,
    private val exporter: AnnotationExporter,
    private val importer: ReferenceImporter,
) : ViewModel() {

    val referenceId: String = checkNotNull(savedStateHandle["referenceId"])
    val boardId: String = checkNotNull(savedStateHandle["boardId"])
    val canSaveToBoard: Boolean get() = boardId != NO_BOARD_CONTEXT

    val reference: StateFlow<Reference?> = references.observe(referenceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every committed edit is written immediately, so leaving the screen (or process death) never loses work. */
    val items: StateFlow<AnnotationSet?> = annotations.observe(referenceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _tool = MutableStateFlow(AnnotationTool.Pen)
    val tool: StateFlow<AnnotationTool> = _tool.asStateFlow()
    private val _color = MutableStateFlow(AnnotationStyle.palette[2])
    val color: StateFlow<Int> = _color.asStateFlow()
    private val _widthSlider = MutableStateFlow(0.25f)
    val widthSlider: StateFlow<Float> = _widthSlider.asStateFlow()
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun setTool(t: AnnotationTool) { _tool.value = t; if (t != AnnotationTool.Select) _selectedId.value = null }
    fun setColor(c: Int) { _color.value = c }
    fun setWidthSlider(v: Float) { _widthSlider.value = v }
    fun select(id: String?) { _selectedId.value = id }

    // ---- undo / redo ----
    private sealed interface Op {
        class Added(val item: Annotation) : Op
        class Removed(val items: List<Annotation>) : Op
    }

    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()
    private val _history = MutableStateFlow(EditHistory())
    val history: StateFlow<EditHistory> = _history.asStateFlow()
    private val lock = Mutex() // edits apply strictly in the order they were made

    fun commit(data: AnnotationData) {
        viewModelScope.launch {
            lock.withLock {
                push(Op.Added(annotations.add(referenceId, data)))
            }
        }
    }

    fun erase(ids: Set<String>) {
        val doomed = items.value?.items.orEmpty().filter { it.id in ids }
        if (doomed.isEmpty()) return
        viewModelScope.launch {
            lock.withLock {
                annotations.remove(ids)
                push(Op.Removed(doomed))
            }
        }
    }

    fun deleteSelected() {
        val id = _selectedId.value ?: return
        _selectedId.value = null
        erase(setOf(id))
    }

    fun clearAll() {
        viewModelScope.launch {
            lock.withLock {
                val removed = annotations.clear(referenceId)
                if (removed.isNotEmpty()) push(Op.Removed(removed))
            }
            _selectedId.value = null
        }
    }

    fun undo() {
        viewModelScope.launch {
            lock.withLock {
                val op = undoStack.removeLastOrNull() ?: return@withLock
                when (op) {
                    is Op.Added -> annotations.remove(listOf(op.item.id))
                    is Op.Removed -> annotations.restore(op.items)
                }
                redoStack.addLast(op)
                publish()
            }
        }
    }

    fun redo() {
        viewModelScope.launch {
            lock.withLock {
                val op = redoStack.removeLastOrNull() ?: return@withLock
                when (op) {
                    is Op.Added -> annotations.restore(listOf(op.item))
                    is Op.Removed -> annotations.remove(op.items.map { it.id })
                }
                undoStack.addLast(op)
                publish()
            }
        }
    }

    private fun push(op: Op) {
        undoStack.addLast(op)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        publish()
    }

    private fun publish() { _history.value = EditHistory(undoStack.isNotEmpty(), redoStack.isNotEmpty()) }

    // ---- files ----
    fun sourceFile(): File? = reference.value?.let { references.fileFor(it) }

    /** Renders image + annotations into a new PNG (the original is untouched). Null if it can't be produced. */
    suspend fun renderFlattened(): File? {
        val source = sourceFile()?.takeIf { it.exists() } ?: return null
        return exporter.render(source, items.value?.items.orEmpty().map { it.data })
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Adds the flattened PNG to this board as a new, independent reference. */
    suspend fun saveAsReference(file: File): ImportReport? {
        var report: ImportReport? = null
        importer.import(boardId, listOf(uriFor(file))).collect { if (it is ImportEvent.Finished) report = it.report }
        return report
    }

    /** Copies the flattened PNG to a location the user chose in the system file picker. */
    suspend fun copyTo(file: File, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(destination) ?: return@withContext false
            out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        const val MAX_HISTORY = 100
    }
}
