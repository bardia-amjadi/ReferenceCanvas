package app.referencecanvas.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.Folder
import app.referencecanvas.domain.model.FolderNode
import app.referencecanvas.domain.model.FolderTree

/**
 * Picks a destination folder (null = top level). When [excludeSubtreeOf] is set, that folder and everything
 * inside it are hidden, so a folder can never be moved into itself.
 */
@Composable
fun FolderPickerDialog(
    folders: List<Folder>,
    title: String,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    excludeSubtreeOf: String? = null,
) {
    val nodes = remember(folders, excludeSubtreeOf) { visibleNodes(FolderTree.flatten(folders), excludeSubtreeOf) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item(key = "root") {
                    PickerRow(label = stringResource(R.string.top_level), depth = 0, onClick = { onPick(null) })
                }
                items(nodes, key = { it.folder.id }) { node ->
                    PickerRow(label = node.folder.name, depth = node.depth + 1, onClick = { onPick(node.folder.id) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun visibleNodes(all: List<FolderNode>, excludeId: String?): List<FolderNode> {
    if (excludeId == null) return all
    val out = ArrayList<FolderNode>(all.size)
    var skipBelow: Int? = null
    for (node in all) {
        val limit = skipBelow
        if (limit != null && node.depth > limit) continue
        skipBelow = null
        if (node.folder.id == excludeId) skipBelow = node.depth else out += node
    }
    return out
}

@Composable
private fun PickerRow(label: String, depth: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (depth * 16).dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = RcTheme.colors.textSecondary)
        Text(label, color = RcTheme.colors.textPrimary, modifier = Modifier.padding(start = 12.dp))
    }
}
