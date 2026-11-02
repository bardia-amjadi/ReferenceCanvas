package app.referencecanvas.feature.favorites

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.feature.boards.BoardListPane
import app.referencecanvas.feature.boards.BoardsViewModel
import app.referencecanvas.ui.components.EmptyState

@Composable
fun FavoritesScreen(onOpenBoard: (String) -> Unit, viewModel: BoardsViewModel = hiltViewModel()) {
    val boards by viewModel.favorites.collectAsStateWithLifecycle()
    BoardListPane(
        title = stringResource(R.string.nav_favorites),
        boards = boards,
        viewModel = viewModel,
        onOpenBoard = onOpenBoard,
        showCreateButton = false,
    ) {
        EmptyState(
            icon = Icons.Filled.StarBorder,
            title = stringResource(R.string.favorites_empty_title),
            message = stringResource(R.string.favorites_empty_message),
        )
    }
}
