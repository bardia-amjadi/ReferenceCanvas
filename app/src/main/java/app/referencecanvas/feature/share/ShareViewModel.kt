package app.referencecanvas.feature.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.data.importer.ImportEvent
import app.referencecanvas.data.importer.ImportReport
import app.referencecanvas.data.importer.ReferenceImporter
import app.referencecanvas.data.repository.BoardRepository
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.data.repository.SettingsRepository
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.domain.model.LinkParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SharedPayload {
    data class Files(val uris: List<Uri>) : SharedPayload
    data class Link(val url: String) : SharedPayload
}

sealed interface ShareState {
    data object Idle : ShareState
    data object Unsupported : ShareState
    data class Choosing(val payload: SharedPayload) : ShareState
    data class Importing(val done: Int, val total: Int) : ShareState
    data class Finished(val report: ImportReport, val boardId: String) : ShareState
    data class LinkAdded(val boardId: String) : ShareState
}

/**
 * Receives content from the system Share sheet. Nothing is imported until the user picks a destination board,
 * and shared links are stored as text only.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val boards: BoardRepository,
    private val references: ReferenceRepository,
    private val importer: ReferenceImporter,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    val boardList: StateFlow<List<BoardSummary>> = boards.observeBoards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val payload: SharedPayload? = when (action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    SharedPayload.Files(listOf(uri))
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { LinkParser.extractUrl(it) }?.let { SharedPayload.Link(it) }
                }
            }
            else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }?.let { SharedPayload.Files(it.toList()) }
        }
        intent.action = null // don't re-handle the same share after rotation or re-launch
        _state.value = if (payload != null) ShareState.Choosing(payload) else ShareState.Unsupported
    }

    fun importInto(boardId: String) {
        val payload = (_state.value as? ShareState.Choosing)?.payload ?: return
        start(boardId, payload)
    }

    fun createBoardAndImport(name: String) {
        val payload = (_state.value as? ShareState.Choosing)?.payload ?: return
        viewModelScope.launch {
            val id = boards.create(name, settings.settings.first().defaultViewMode) ?: return@launch
            start(id, payload)
        }
    }

    fun retryFailed(finished: ShareState.Finished) {
        val uris = finished.report.failures.map { it.uri }
        if (uris.isNotEmpty()) start(finished.boardId, SharedPayload.Files(uris))
    }

    fun cancel() { job?.cancel() }
    fun dismiss() { _state.value = ShareState.Idle }

    private fun start(boardId: String, payload: SharedPayload) {
        job?.cancel()
        job = viewModelScope.launch {
            when (payload) {
                is SharedPayload.Link -> {
                    references.addLink(boardId, payload.url)
                    _state.value = ShareState.LinkAdded(boardId)
                }
                is SharedPayload.Files -> {
                    _state.value = ShareState.Importing(0, payload.uris.size)
                    var report: ImportReport? = null
                    try {
                        importer.import(boardId, payload.uris).collect { event ->
                            when (event) {
                                is ImportEvent.Progress -> _state.value = ShareState.Importing(event.done, event.total)
                                is ImportEvent.Finished -> report = event.report
                            }
                        }
                    } catch (e: CancellationException) {
                        _state.value = ShareState.Idle
                        throw e
                    }
                    _state.value = report?.let { ShareState.Finished(it, boardId) } ?: ShareState.Idle
                }
            }
        }
    }
}
