package app.referencecanvas.feature.boards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.ui.components.BoardNameDialog
import app.referencecanvas.ui.components.ConfirmDialog
import app.referencecanvas.ui.components.FolderPickerDialog

/** Breadcrumb plus the folders inside the current one. Long-press a folder for rename / move / delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderBar(
    breadcrumb: List<Folder>,
    subfolders: List<Folder>,
    allFolders: List<Folder>,
    onOpen: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String, String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = RcTheme.colors
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Folder?>(null) }
    var moving by remember { mutableStateOf<Folder?>(null) }
    var deleting by remember { mutableStateOf<Folder?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Column {
        if (breadcrumb.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpen(null) }) { Text(stringResource(R.string.all_boards)) }
                breadcrumb.forEach { folder ->
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textMuted)
                    TextButton(onClick = { onOpen(folder.id) }) { Text(folder.name) }
                }
            }
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(subfolders, key = { it.id }) { folder ->
                Box {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.border),
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpen(folder.id) },
                            onLongClick = { menuFor = folder.id },
                        ),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = colors.accent)
                            Text(folder.name, color = colors.textPrimary, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    DropdownMenu(expanded = menuFor == folder.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menuFor = null; renaming = folder })
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_to_folder)) }, onClick = { menuFor = null; moving = folder })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = colors.danger) },
                            onClick = { menuFor = null; deleting = folder },
                        )
                    }
                }
            }
            item(key = "new") {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceVariant,
                    onClick = { creating = true },
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CreateNewFolder, null, tint = colors.textSecondary)
                        Text(stringResource(R.string.new_folder), color = colors.textSecondary, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    if (creating) {
        BoardNameDialog(
            title = R.string.new_folder,
            confirmLabel = R.string.create,
            label = R.string.folder_name,
            initial = "",
            onConfirm = { onCreate(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { folder ->
        BoardNameDialog(
            title = R.string.rename_folder,
            confirmLabel = R.string.rename,
            label = R.string.folder_name,
            initial = folder.name,
            onConfirm = { onRename(folder.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    moving?.let { folder ->
        FolderPickerDialog(
            folders = allFolders,
            title = stringResource(R.string.move_to_folder),
            excludeSubtreeOf = folder.id,
            onPick = { onMove(folder.id, it); moving = null },
            onDismiss = { moving = null },
        )
    }
    deleting?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.delete_folder_title),
            message = stringResource(R.string.delete_folder_message, folder.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { onDelete(folder.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}
