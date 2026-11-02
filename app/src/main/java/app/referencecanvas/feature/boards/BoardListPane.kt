package app.referencecanvas.feature.boards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.ui.components.FolderPickerDialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.ui.components.BoardNameDialog
import app.referencecanvas.ui.components.ScreenHeader

/**
 * Shared layout for every screen that lists boards.
 * [boards] == null shows a loading indicator; an empty list shows [empty].
 */
@Composable
fun BoardListPane(
    title: String,
    boards: List<BoardSummary>?,
    viewModel: BoardsViewModel,
    onOpenBoard: (String) -> Unit,
    showCreateButton: Boolean,
    modifier: Modifier = Modifier,
    topContent: @Composable ColumnScope.() -> Unit = {},
    empty: @Composable (onCreate: () -> Unit) -> Unit,
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf<BoardSummary?>(null) }
    val folders by viewModel.allFolders.collectAsStateWithLifecycle()
    val colors = RcTheme.colors

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title)
            topContent()
            Box(Modifier.weight(1f).fillMaxSize()) {
                when {
                    boards == null -> CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    boards.isEmpty() -> empty { showCreate = true }
                    else -> BoardGrid(
                        boards = boards,
                        onOpen = onOpenBoard,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onTogglePin = viewModel::togglePin,
                        onDuplicate = viewModel::duplicate,
                        onRename = viewModel::rename,
                        onDelete = viewModel::delete,
                        onMoveToFolder = { moving = it },
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 4.dp,
                            bottom = if (showCreateButton) 96.dp else 16.dp,
                        ),
                    )
                }
            }
        }
        if (showCreateButton) {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_board)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    moving?.let { target ->
        FolderPickerDialog(
            folders = folders,
            title = stringResource(R.string.move_to_folder),
            onPick = { viewModel.moveBoard(target.board.id, it); moving = null },
            onDismiss = { moving = null },
        )
    }

    if (showCreate) {
        BoardNameDialog(
            title = R.string.create_board,
            confirmLabel = R.string.create,
            initial = "",
            onConfirm = { viewModel.createBoard(it); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }
}
