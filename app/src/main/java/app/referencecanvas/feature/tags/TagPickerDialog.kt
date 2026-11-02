package app.referencecanvas.feature.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.Tag
import app.referencecanvas.ui.components.ConfirmDialog

/**
 * Tags for one or many references. A tag shows checked when every selected reference has it,
 * indeterminate when only some do. Tapping applies it to all, or removes it from all.
 */
@Composable
fun TagPickerDialog(
    referenceIds: List<String>,
    onDismiss: () -> Unit,
    viewModel: TagViewModel = hiltViewModel(),
) {
    val tags by viewModel.allTags.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }
    var newName by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    val total = referenceIds.size

    LaunchedEffect(reload, referenceIds) { usage = viewModel.usage(referenceIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.new_tag)) },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = newName.isNotBlank(),
                        onClick = { viewModel.create(newName, referenceIds) { ok -> if (ok) { newName = ""; reload++ } } },
                    ) { Text(stringResource(R.string.add)) }
                }
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.no_tags_yet), color = RcTheme.colors.textSecondary)
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(tags, key = { it.id }) { tag ->
                        val count = usage[tag.id] ?: 0
                        val state = when {
                            count == 0 -> ToggleableState.Off
                            count >= total -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggle(tag.id, referenceIds, attach = state != ToggleableState.On) { reload++ } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(state = state, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(tag.name, color = RcTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { deleting = tag }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete_tag, tag.name), tint = RcTheme.colors.textSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )

    deleting?.let { tag ->
        ConfirmDialog(
            title = stringResource(R.string.delete_tag_title),
            message = stringResource(R.string.delete_tag_message, tag.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { viewModel.delete(tag.id) { reload++ }; deleting = null },
            onDismiss = { deleting = null },
        )
    }
}
