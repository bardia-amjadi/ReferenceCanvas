package app.referencecanvas.feature.boards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.Board
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.ui.components.BoardNameDialog
import app.referencecanvas.ui.components.ConfirmDialog

@Composable
fun BoardGrid(
    boards: List<BoardSummary>,
    onOpen: (String) -> Unit,
    onToggleFavorite: (BoardSummary) -> Unit,
    onTogglePin: (BoardSummary) -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMoveToFolder: (BoardSummary) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    var renaming by remember { mutableStateOf<Board?>(null) }
    var deleting by remember { mutableStateOf<Board?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(boards, key = { it.board.id }) { summary ->
            BoardCard(
                summary = summary,
                onOpen = { onOpen(summary.board.id) },
                onToggleFavorite = { onToggleFavorite(summary) },
                onTogglePin = { onTogglePin(summary) },
                onDuplicate = { onDuplicate(summary.board.id) },
                onMoveToFolder = { onMoveToFolder(summary) },
                onRename = { renaming = summary.board },
                onDelete = { deleting = summary.board },
            )
        }
    }

    renaming?.let { board ->
        BoardNameDialog(
            title = R.string.rename_board,
            confirmLabel = R.string.rename,
            initial = board.name,
            onConfirm = { onRename(board.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { board ->
        ConfirmDialog(
            title = stringResource(R.string.delete_board_title),
            message = stringResource(R.string.delete_board_message, board.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { onDelete(board.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun BoardCard(
    summary: BoardSummary,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveToFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val board = summary.board
    val colors = RcTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 16.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = board.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.accent,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (board.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.pinned_indicator),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (board.isFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.favorite_indicator),
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.board_options),
                            tint = colors.textSecondary,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(if (board.isFavorite) R.string.unfavorite else R.string.favorite))
                            },
                            leadingIcon = {
                                Icon(if (board.isFavorite) Icons.Filled.StarBorder else Icons.Filled.Star, null)
                            },
                            onClick = { menuOpen = false; onToggleFavorite() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (board.isPinned) R.string.unpin else R.string.pin)) },
                            leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                            onClick = { menuOpen = false; onTogglePin() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.duplicate)) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menuOpen = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to_folder)) },
                            leadingIcon = { Icon(Icons.Filled.Folder, null) },
                            onClick = { menuOpen = false; onMoveToFolder() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = colors.danger) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = colors.danger) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = board.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.reference_count, summary.referenceCount, summary.referenceCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}
