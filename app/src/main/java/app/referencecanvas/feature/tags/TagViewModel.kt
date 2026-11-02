package app.referencecanvas.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.data.repository.TagRepository
import app.referencecanvas.domain.model.Tag
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TagViewModel @Inject constructor(
    private val tags: TagRepository,
) : ViewModel() {
    val allTags: StateFlow<List<Tag>> = tags.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun usage(ids: Collection<String>): Map<String, Int> = tags.usageFor(ids)

    fun toggle(tagId: String, ids: Collection<String>, attach: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            if (attach) tags.attach(tagId, ids) else tags.detach(tagId, ids)
            onDone()
        }
    }

    fun create(name: String, ids: Collection<String>, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(tags.createAndAttach(name, ids)) }
    }

    fun delete(tagId: String, onDone: () -> Unit) {
        viewModelScope.launch { tags.delete(tagId); onDone() }
    }
}
