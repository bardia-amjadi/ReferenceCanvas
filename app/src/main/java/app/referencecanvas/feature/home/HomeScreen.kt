package app.referencecanvas.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.feature.boards.BoardListPane
import app.referencecanvas.feature.boards.BoardsViewModel
import app.referencecanvas.ui.components.EmptyState

private const val RECENT_LIMIT = 6

@Composable
fun HomeScreen(onOpenBoard: (String) -> Unit, viewModel: BoardsViewModel = hiltViewModel()) {
    val boards by viewModel.results.collectAsStateWithLifecycle()
    BoardListPane(
        title = stringResource(R.string.home_recent),
        // Results are ordered pinned-first, then most recently modified.
        boards = boards?.take(RECENT_LIMIT),
        viewModel = viewModel,
        onOpenBoard = onOpenBoard,
        showCreateButton = true,
    ) { onCreate ->
        EmptyState(
            icon = Icons.Filled.Dashboard,
            title = stringResource(R.string.boards_empty_title),
            message = stringResource(R.string.boards_empty_message),
            actionLabel = stringResource(R.string.create_board),
            onAction = onCreate,
        )
    }
}
