package app.referencecanvas.feature.trash

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.data.repository.ReferenceRepository
import app.referencecanvas.domain.model.Reference
import app.referencecanvas.ui.components.ConfirmDialog
import app.referencecanvas.ui.components.EmptyState
import app.referencecanvas.ui.components.ScreenHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TrashViewModel @Inject constructor(private val references: ReferenceRepository) : ViewModel() {
    val items: Flow<PagingData<Reference>> = references.observeTrash().cachedIn(viewModelScope)
    val count: StateFlow<Int> = references.observeTrashCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun restore(id: String) { viewModelScope.launch { references.restoreFromTrash(listOf(id)) } }
    fun deleteForever(id: String) { viewModelScope.launch { references.deletePermanently(listOf(id)) } }
    fun restoreAll() { viewModelScope.launch { references.restoreFromTrash(references.trashedIds()) } }
    fun emptyTrash() { viewModelScope.launch { references.emptyTrash() } }
}

@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val count by viewModel.count.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<Reference?>(null) }
    val colors = RcTheme.colors

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            ScreenHeader(stringResource(R.string.trash), Modifier.weight(1f))
        }
        if (count == 0) {
            EmptyState(
                icon = Icons.Filled.Delete,
                title = stringResource(R.string.trash_empty_title),
                message = stringResource(R.string.trash_empty_message),
            )
        } else {
            Text(
                stringResource(R.string.trash_explainer),
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = viewModel::restoreAll) { Text(stringResource(R.string.restore_all)) }
                TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.empty_trash), color = colors.danger) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    val ref = items[index] ?: return@items
                    TrashRow(ref, onRestore = { viewModel.restore(ref.id) }, onDelete = { confirmOne = ref })
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.empty_trash_title),
            message = pluralStringResource(R.plurals.empty_trash_message, count, count),
            confirmLabel = stringResource(R.string.empty_trash),
            onConfirm = { confirmEmpty = false; viewModel.emptyTrash() },
            onDismiss = { confirmEmpty = false },
        )
    }
    confirmOne?.let { ref ->
        ConfirmDialog(
            title = stringResource(R.string.delete_forever_title),
            message = stringResource(R.string.delete_forever_message, ref.title),
            confirmLabel = stringResource(R.string.delete_forever),
            onConfirm = { viewModel.deleteForever(ref.id); confirmOne = null },
            onDismiss = { confirmOne = null },
        )
    }
}

@Composable
private fun TrashRow(ref: Reference, onRestore: () -> Unit, onDelete: () -> Unit) {
    val colors = RcTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(ref.title, color = colors.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = buildList {
            add(ref.typeLabel)
            if (!ref.isLink) add(Formatter.formatShortFileSize(context, ref.fileSizeBytes))
        }.joinToString(" · ")
        Text(meta, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onRestore) { Text(stringResource(R.string.restore)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_forever), color = colors.danger) }
        }
    }
}
