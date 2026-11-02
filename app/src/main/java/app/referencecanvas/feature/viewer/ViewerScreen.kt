package app.referencecanvas.feature.viewer

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.domain.model.Reference
import app.referencecanvas.ui.FileActions
import app.referencecanvas.feature.annotate.AnnotationOverlay
import app.referencecanvas.feature.tags.TagPickerDialog
import app.referencecanvas.ui.components.BoardPickerDialog
import app.referencecanvas.ui.components.FolderPickerDialog
import app.referencecanvas.ui.components.ConfirmDialog
import java.io.File
import java.text.DateFormat
import java.util.Date

// The viewer is always on black so content is judged on a neutral field; chrome uses fixed light tones.
private val ViewerBackground = Color.Black
private val ViewerText = Color(0xFFF4F5F7)
private val ViewerTextDim = Color(0xFFB0B5C0)
private val ViewerScrim = Color(0xB3000000)

@Composable
fun ViewerScreen(onBack: () -> Unit, onAnnotate: (boardId: String, referenceId: String) -> Unit, viewModel: ViewerViewModel = hiltViewModel()) {
    val data by viewModel.ids.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(ViewerBackground)) {
        val d = data
        when {
            d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = ViewerText)
            d.ids.isEmpty() -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.file_missing_title), color = ViewerText)
                    Button(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            }
            else -> ViewerContent(d, viewModel, onBack, onAnnotate)
        }
    }
}

@Composable
private fun ViewerContent(
    data: ViewerIds,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onAnnotate: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = data.startIndex) { data.ids.size }
    var zoomed by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(true) }
    var showTags by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
    val otherBoards by viewModel.otherBoards.collectAsStateWithLifecycle()

    val currentId = data.ids[pager.currentPage]
    val current by remember(currentId) { viewModel.observe(currentId) }.collectAsStateWithLifecycle(null)
    LaunchedEffect(currentId) { viewModel.markOpened(currentId) }
    val annotationCount by remember(currentId) { viewModel.annotationCount(currentId) }.collectAsStateWithLifecycle(0)

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            key = { data.ids[it] },
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ReferencePage(
                id = data.ids[page],
                viewModel = viewModel,
                onTap = { chromeVisible = !chromeVisible },
                onZoomed = { if (page == pager.currentPage) zoomed = it },
                showAnnotations = showAnnotations,
            )
        }

        if (chromeVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(ViewerScrim)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = ViewerText)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.title.orEmpty(),
                        color = ViewerText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.position_of, pager.currentPage + 1, data.ids.size),
                        color = ViewerTextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                current?.let { ref ->
                    IconButton(onClick = { viewModel.toggleFavorite(ref) }) {
                        Icon(
                            if (ref.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(if (ref.isFavorite) R.string.unfavorite else R.string.favorite),
                            tint = ViewerText,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions), tint = ViewerText)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.details)) },
                            onClick = { menuOpen = false; showInfo = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            onClick = {
                                menuOpen = false
                                current?.takeIf { it.isLink }?.let { FileActions.shareText(context, it.sourceUri.orEmpty(), it.title) }
                                    ?: withFile(context, viewModel, current) { f, r -> FileActions.share(context, f, r.mimeType, r.title) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_with)) },
                            onClick = {
                                menuOpen = false
                                current?.takeIf { it.isLink }?.let { FileActions.openUrl(context, it.sourceUri.orEmpty()) }
                                    ?: withFile(context, viewModel, current) { f, r -> FileActions.open(context, f, r.mimeType) }
                            },
                        )
                        val annotatable = current?.isPreviewableImage == true
                        DropdownMenuItem(
                            text = { Text(stringResource(if (annotatable) R.string.annotate else R.string.annotate_images_only)) },
                            enabled = annotatable,
                            onClick = { menuOpen = false; onAnnotate(viewModel.boardId, currentId) },
                        )
                        if (annotatable && annotationCount > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (showAnnotations) R.string.hide_annotations else R.string.show_annotations)) },
                                onClick = { menuOpen = false; showAnnotations = !showAnnotations },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tags)) },
                            onClick = { menuOpen = false; showTags = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_to_board)) },
                            onClick = { menuOpen = false; showPicker = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to_folder)) },
                            onClick = { menuOpen = false; showFolders = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (showTags) TagPickerDialog(referenceIds = listOf(currentId), onDismiss = { showTags = false })
    if (showFolders) {
        FolderPickerDialog(
            folders = allFolders,
            title = stringResource(R.string.move_to_folder),
            onPick = { viewModel.setFolder(currentId, it); showFolders = false },
            onDismiss = { showFolders = false },
        )
    }
    if (showInfo) current?.let { InfoDialog(it) { showInfo = false } }
    if (showPicker) {
        BoardPickerDialog(
            boards = otherBoards,
            onPick = {
                viewModel.addToBoard(it.board.id, currentId)
                Toast.makeText(context, context.getString(R.string.added_to_board, it.board.name), Toast.LENGTH_SHORT).show()
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_refs_title),
            message = stringResource(R.string.delete_one_message),
            confirmLabel = stringResource(R.string.move_to_trash),
            onConfirm = { confirmDelete = false; viewModel.moveToTrash(currentId, onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun withFile(
    context: android.content.Context,
    viewModel: ViewerViewModel,
    reference: Reference?,
    block: (File, Reference) -> Unit,
) {
    val file = reference?.let { viewModel.fileFor(it) }
    if (reference == null || file == null || !file.exists()) {
        Toast.makeText(context, R.string.file_missing_title, Toast.LENGTH_SHORT).show()
    } else {
        block(file, reference)
    }
}

@Composable
private fun ReferencePage(
    id: String,
    viewModel: ViewerViewModel,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    showAnnotations: Boolean,
) {
    val annotations by remember(id) { viewModel.annotationsFor(id) }.collectAsStateWithLifecycle(emptyList())
    val reference by remember(id) { viewModel.observe(id) }.collectAsStateWithLifecycle(null)
    var retry by remember { mutableIntStateOf(0) }
    var decodeFailed by remember(retry) { mutableStateOf(false) }
    val ref = reference

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (ref == null) {
            CircularProgressIndicator(color = ViewerText)
            return@Box
        }
        if (ref.isLink) {
            val context = LocalContext.current
            val url = ref.sourceUri.orEmpty()
            Message(
                icon = { Icon(Icons.Filled.Link, null, tint = ViewerTextDim, modifier = Modifier.size(48.dp)) },
                title = ref.title,
                body = url + "\n" + stringResource(R.string.link_not_fetched),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { FileActions.openUrl(context, url) }) { Text(stringResource(R.string.open_in_browser)) }
                    OutlinedButton(onClick = { FileActions.shareText(context, url, ref.title) }) { Text(stringResource(R.string.share)) }
                }
            }
            return@Box
        }
        val context = LocalContext.current
        val file = remember(ref.localRelativePath, retry) { viewModel.fileFor(ref) }
        val exists = remember(file, retry) { file?.exists() == true }
        when {
            file == null || !exists -> Message(
                icon = { Icon(Icons.Filled.BrokenImage, null, tint = ViewerTextDim, modifier = Modifier.size(48.dp)) },
                title = stringResource(R.string.file_missing_title),
                body = stringResource(R.string.file_missing_message),
            ) { OutlinedButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) } }

            ref.isPreviewableImage && !decodeFailed -> key(retry) {
                ZoomableImage(
                    file = file,
                    contentDescription = ref.title,
                    onTap = onTap,
                    onZoomed = onZoomed,
                    onError = { decodeFailed = true },
                    overlay = { aspect ->
                        if (showAnnotations && annotations.isNotEmpty()) {
                            AnnotationOverlay(annotations, aspect, Modifier.fillMaxSize())
                        }
                    },
                )
            }

            ref.isVideo -> VideoPage(file, ref.title, onRetry = { retry++ }, onOpenExternally = { FileActions.open(context, file, ref.mimeType) })
            ref.isPdf -> PdfPage(file, onRetry = { retry++ }, onOpenExternally = { FileActions.open(context, file, ref.mimeType) })
            ref.isText -> TextPage(file, onRetry = { retry++ }, onOpenExternally = { FileActions.open(context, file, ref.mimeType) })

            else -> {
                Message(
                    icon = { Icon(Icons.Filled.Description, null, tint = ViewerTextDim, modifier = Modifier.size(48.dp)) },
                    title = ref.originalFileName,
                    body = stringResource(
                        if (decodeFailed) R.string.preview_failed_message else R.string.preview_unavailable,
                    ) + "\n" + ref.typeLabel + " · " + Formatter.formatShortFileSize(context, ref.fileSizeBytes),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { FileActions.open(context, file, ref.mimeType) }) {
                            Text(stringResource(R.string.open_with))
                        }
                        OutlinedButton(onClick = { FileActions.share(context, file, ref.mimeType, ref.title) }) {
                            Text(stringResource(R.string.share))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(title, color = ViewerText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = ViewerTextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        actions()
    }
}

@Composable
private fun InfoDialog(reference: Reference, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dimensions = if (reference.widthPx != null && reference.heightPx != null) {
        "${reference.widthPx} × ${reference.heightPx}"
    } else {
        "—"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(reference.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(R.string.info_file_name, reference.originalFileName)
                InfoRow(R.string.info_type, reference.mimeType)
                InfoRow(R.string.info_size, Formatter.formatFileSize(context, reference.fileSizeBytes))
                InfoRow(R.string.info_dimensions, dimensions)
                InfoRow(R.string.info_added, DateFormat.getDateTimeInstance().format(Date(reference.createdAt)))
                InfoRow(R.string.info_storage, stringResource(if (reference.isLink) R.string.storage_link else R.string.storage_managed))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun InfoRow(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
