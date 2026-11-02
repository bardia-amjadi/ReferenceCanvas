package app.referencecanvas.feature.boards

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.ui.components.EmptyState

@Composable
fun BoardsScreen(onOpenBoard: (String) -> Unit, viewModel: BoardsViewModel = hiltViewModel()) {
    val boards by viewModel.folderBoards.collectAsStateWithLifecycle()
    val breadcrumb by viewModel.breadcrumb.collectAsStateWithLifecycle()
    val subfolders by viewModel.subfolders.collectAsStateWithLifecycle()
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()

    // Back climbs out of a folder before leaving the tab.
    BackHandler(enabled = breadcrumb.isNotEmpty()) {
        viewModel.openFolder(breadcrumb.dropLast(1).lastOrNull()?.id)
    }

    BoardListPane(
        title = breadcrumb.lastOrNull()?.name ?: stringResource(R.string.nav_boards),
        boards = boards,
        viewModel = viewModel,
        onOpenBoard = onOpenBoard,
        showCreateButton = true,
        topContent = {
            FolderBar(
                breadcrumb = breadcrumb,
                subfolders = subfolders,
                allFolders = allFolders,
                onOpen = viewModel::openFolder,
                onCreate = viewModel::createFolder,
                onRename = viewModel::renameFolder,
                onMove = viewModel::moveFolder,
                onDelete = viewModel::deleteFolder,
            )
        },
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
