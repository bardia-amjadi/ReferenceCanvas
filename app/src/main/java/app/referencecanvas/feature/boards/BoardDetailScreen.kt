package app.referencecanvas.feature.boards

import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.BoardSortMode
import app.referencecanvas.feature.canvas.CanvasContent
import app.referencecanvas.domain.model.BoardViewMode
import app.referencecanvas.domain.model.LinkParser
import app.referencecanvas.feature.tags.TagPickerDialog
import app.referencecanvas.ui.components.BoardPickerDialog
import app.referencecanvas.ui.components.FolderPickerDialog
import app.referencecanvas.ui.components.ConfirmDialog
import app.referencecanvas.ui.components.EmptyState
import java.io.File

@Composable
fun BoardDetailScreen(
    onBack: () -> Unit,
    onOpenReference: (boardId: String, referenceId: String) -> Unit,
    viewModel: BoardDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val colors = RcTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val density by viewModel.density.collectAsStateWithLifecycle()
    val otherBoards by viewModel.otherBoards.collectAsStateWithLifecycle()

    var showPicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showTags by rememberSaveable { mutableStateOf(false) }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        viewModel.importUris(it)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.importUris(it)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val captured = pendingCapture
        pendingCapture = null
        if (saved && captured != null) viewModel.importUris(listOf(Uri.parse(captured)))
    }
    val launchPhotos = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    val launchFiles = { filePicker.launch(arrayOf("*/*")) }
    val pasteFromClipboard: () -> Unit = {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val count = clip?.itemCount ?: 0
        val uris = (0 until count).mapNotNull { clip?.getItemAt(it)?.uri }
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris)
        } else {
            val text = if (clip != null && count > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
            val url = LinkParser.extractUrl(text)
            if (url != null) viewModel.addLink(url) else Toast.makeText(context, R.string.clipboard_nothing, Toast.LENGTH_SHORT).show()
        }
    }
    val launchCamera = {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val target = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        pendingCapture = uri.toString()
        camera.launch(uri)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    onClear = viewModel::clearSelection,
                    onFavorite = viewModel::favoriteSelected,
                    onAddToBoard = { showPicker = true },
                    onTag = { showTags = true },
                    onMoveToFolder = { showFolders = true },
                    onRemove = viewModel::removeSelectedFromBoard,
                    onDelete = { confirmDelete = true },
                )
            } else {
                val loaded = state as? BoardDetailState.Loaded
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Text(
                        loaded?.board?.name.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (loaded != null) {
                        SortMenu(loaded.board.sortMode, viewModel::setSortMode)
                    }
                }
            }

            when (val s = state) {
                BoardDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                BoardDetailState.Missing -> EmptyState(
                    icon = Icons.Filled.Dashboard,
                    title = stringResource(R.string.board_not_found),
                    message = "",
                    actionLabel = stringResource(R.string.back),
                    onAction = onBack,
                )
                is BoardDetailState.Loaded -> {
                    ViewModeSwitch(s.board.viewMode, viewModel::setViewMode)
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        when {
                            s.board.viewMode == BoardViewMode.Canvas -> CanvasContent(
                                onOpenReference = { onOpenReference(viewModel.boardId, it) },
                                onAddPhotos = launchPhotos,
                                onAddFiles = launchFiles,
                                onAddCamera = launchCamera,
                                onAddLink = { showLink = true },
                                onPaste = pasteFromClipboard,
                            )
                            items.loadState.refresh is LoadState.Loading && items.itemCount == 0 ->
                                CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent)
                            items.itemCount == 0 -> EmptyState(
                                icon = Icons.Filled.PhotoLibrary,
                                title = stringResource(R.string.gallery_empty_title),
                                message = stringResource(R.string.gallery_empty_message),
                                actionLabel = stringResource(R.string.add_from_photos),
                                onAction = { launchPhotos() },
                            )
                            else -> GalleryGrid(
                                items = items,
                                density = density,
                                selection = selection,
                                fileFor = viewModel::fileFor,
                                onClick = {
                                    if (selection.isNotEmpty()) viewModel.toggleSelected(it.id)
                                    else onOpenReference(viewModel.boardId, it.id)
                                },
                                onLongClick = { viewModel.toggleSelected(it.id) },
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                            )
                        }
                    }
                }
            }
        }

        val canvasMode = (state as? BoardDetailState.Loaded)?.board?.viewMode == BoardViewMode.Canvas
        if (state is BoardDetailState.Loaded && selection.isEmpty()) {
            when (val imp = importState) {
                is ImportUiState.Running -> ImportProgressCard(
                    state = imp,
                    onCancel = viewModel::cancelImport,
                    // In canvas mode the toolbar owns the bottom edge, so progress shows at the top.
                    modifier = Modifier
                        .align(if (canvasMode) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(16.dp),
                )
                // The canvas toolbar has its own Add button; the FAB is for the gallery.
                else -> if (!canvasMode) {
                    AddReferencesFab(
                        onPhotos = launchPhotos,
                        onFiles = launchFiles,
                        onCamera = launchCamera,
                        onLink = { showLink = true },
                        onPaste = pasteFromClipboard,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
        }
    }

    (importState as? ImportUiState.Done)?.let { done ->
        ImportReportDialog(
            report = done.report,
            onRetry = { viewModel.retryFailed(done.report) },
            onDismiss = viewModel::dismissImportReport,
        )
    }
    if (showPicker) {
        BoardPickerDialog(
            boards = otherBoards,
            onPick = { viewModel.addSelectedToBoard(it.board.id); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
    if (showLink) AddLinkDialog(onSubmit = viewModel::addLink, onDismiss = { showLink = false })
    if (showTags) TagPickerDialog(referenceIds = selection.toList(), onDismiss = { showTags = false })
    if (showFolders) {
        FolderPickerDialog(
            folders = allFolders,
            title = stringResource(R.string.move_to_folder),
            onPick = { viewModel.moveSelectedToFolder(it); showFolders = false },
            onDismiss = { showFolders = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_refs_title),
            message = pluralStringResource(R.plurals.delete_refs_message, selection.size, selection.size),
            confirmLabel = stringResource(R.string.move_to_trash),
            onConfirm = { confirmDelete = false; viewModel.trashSelected() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ViewModeSwitch(current: BoardViewMode, onSelect: (BoardViewMode) -> Unit) {
    val colors = RcTheme.colors
    val modes = BoardViewMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.accent.copy(alpha = 0.18f),
                    activeContentColor = colors.textPrimary,
                    activeBorderColor = colors.accent,
                ),
            ) {
                Text(stringResource(if (mode == BoardViewMode.Canvas) R.string.view_canvas else R.string.view_gallery))
            }
        }
    }
}

@Composable
private fun SortMenu(current: BoardSortMode, onSelect: (BoardSortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BoardSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(mode.labelRes()),
                            color = if (mode == current) RcTheme.colors.accent else RcTheme.colors.textPrimary,
                        )
                    },
                    onClick = { open = false; onSelect(mode) },
                )
            }
        }
    }
}

private fun BoardSortMode.labelRes(): Int = when (this) {
    BoardSortMode.Manual -> R.string.sort_manual
    BoardSortMode.Name -> R.string.sort_name
    BoardSortMode.DateAdded -> R.string.sort_date_added
    BoardSortMode.DateModified -> R.string.sort_date_modified
    BoardSortMode.FileType -> R.string.sort_file_type
}

@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onFavorite: () -> Unit,
    onAddToBoard: () -> Unit,
    onTag: () -> Unit,
    onMoveToFolder: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = RcTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) { Icon(Icons.Filled.Close, stringResource(R.string.clear_selection)) }
        Text(
            stringResource(R.string.selection_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onFavorite) { Icon(Icons.Filled.Star, stringResource(R.string.favorite)) }
        IconButton(onClick = onTag) { Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.tags)) }
        IconButton(onClick = onAddToBoard) { Icon(Icons.Filled.Dashboard, stringResource(R.string.add_to_board)) }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_to_folder)) },
                    leadingIcon = { Icon(Icons.Filled.Folder, null) },
                    onClick = { menuOpen = false; onMoveToFolder() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_from_board)) },
                    leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, null) },
                    onClick = { menuOpen = false; onRemove() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = colors.danger) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = colors.danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
