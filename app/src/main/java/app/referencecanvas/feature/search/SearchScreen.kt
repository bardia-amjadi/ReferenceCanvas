package app.referencecanvas.feature.search

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.BoardRef
import app.referencecanvas.domain.model.DateRange
import app.referencecanvas.domain.model.FolderTree
import app.referencecanvas.domain.model.NO_BOARD_CONTEXT
import app.referencecanvas.domain.model.Reference
import app.referencecanvas.domain.model.SearchCategory
import app.referencecanvas.domain.model.SearchSort
import app.referencecanvas.feature.boards.BoardListPane
import app.referencecanvas.feature.boards.BoardsViewModel
import app.referencecanvas.ui.components.EmptyState
import app.referencecanvas.ui.components.ScreenHeader
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    onOpenBoard: (String) -> Unit,
    onOpenReference: (boardId: String, referenceId: String) -> Unit,
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val modeSwitch: @Composable ColumnScope.() -> Unit = {
        val colors = RcTheme.colors
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(R.string.search_references, R.string.search_boards).forEachIndexed { index, label ->
                SegmentedButton(
                    selected = mode == index,
                    onClick = { mode = index },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.accent.copy(alpha = 0.18f),
                        activeContentColor = colors.textPrimary,
                        activeBorderColor = colors.accent,
                    ),
                ) { Text(stringResource(label)) }
            }
        }
    }
    if (mode == 0) ReferenceSearch(onOpenBoard, onOpenReference, modeSwitch) else BoardSearch(onOpenBoard, modeSwitch)
}

// ------------------------------------------------------------------ boards

@Composable
private fun BoardSearch(onOpenBoard: (String) -> Unit, modeSwitch: @Composable ColumnScope.() -> Unit) {
    val viewModel: BoardsViewModel = hiltViewModel()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val blank = query.isBlank()
    BoardListPane(
        title = stringResource(R.string.nav_search),
        boards = if (blank) emptyList() else results,
        viewModel = viewModel,
        onOpenBoard = onOpenBoard,
        showCreateButton = false,
        topContent = {
            modeSwitch()
            SearchField(query, stringResource(R.string.search_boards_hint), viewModel::setQuery, onSubmit = {})
        },
    ) {
        EmptyState(
            icon = Icons.Filled.Search,
            title = stringResource(if (blank) R.string.search_prompt_title else R.string.search_no_results_title),
            message = if (blank) stringResource(R.string.search_boards_prompt) else stringResource(R.string.search_no_results_message, query.trim()),
        )
    }
}

// -------------------------------------------------------------- references

@Composable
private fun ReferenceSearch(
    onOpenBoard: (String) -> Unit,
    onOpenReference: (String, String) -> Unit,
    modeSwitch: @Composable ColumnScope.() -> Unit,
    viewModel: ReferenceSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val hasCriteria by viewModel.hasCriteria.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val suggestions by viewModel.tagSuggestions.collectAsStateWithLifecycle()
    val items = viewModel.results.collectAsLazyPagingItems()
    var boardChoices by remember { mutableStateOf<List<BoardRef>?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.nav_search))
        modeSwitch()
        SearchField(text, stringResource(R.string.search_references_hint), viewModel::setText) { viewModel.remember(text) }
        FilterRow(viewModel)
        if (suggestions.isNotEmpty() && text.isNotBlank() && filters.tagId == null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { tag ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.setTag(tag.id); viewModel.setText("") },
                        label = { Text(stringResource(R.string.tag_suggestion, tag.name)) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            if (!hasCriteria) {
                RecentSearches(recent, onPick = viewModel::setText, onForget = viewModel::forget, onClear = viewModel::clearRecent)
            } else {
                Results(
                    items = items,
                    fileFor = viewModel::fileFor,
                    onOpen = { ref ->
                        viewModel.remember(text)
                        onOpenReference(NO_BOARD_CONTEXT, ref.id)
                    },
                    onShowInBoard = { ref ->
                        scope.launch {
                            val boards = viewModel.boardsFor(ref.id)
                            when (boards.size) {
                                0 -> Toast.makeText(context, R.string.not_on_any_board, Toast.LENGTH_SHORT).show()
                                1 -> onOpenBoard(boards.single().boardId)
                                else -> boardChoices = boards
                            }
                        }
                    },
                )
            }
        }
    }

    boardChoices?.let { choices ->
        AlertDialog(
            onDismissRequest = { boardChoices = null },
            title = { Text(stringResource(R.string.show_in_board)) },
            text = {
                Column {
                    choices.forEach { board ->
                        Text(
                            board.boardName,
                            color = RcTheme.colors.textPrimary,
                            modifier = Modifier.fillMaxWidth().clickable { boardChoices = null; onOpenBoard(board.boardId) }.padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { boardChoices = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun FilterRow(vm: ReferenceSearchViewModel) {
    val filters by vm.filters.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val hasCriteria by vm.hasCriteria.collectAsStateWithLifecycle()
    val tags by vm.allTags.collectAsStateWithLifecycle()
    val boards by vm.allBoards.collectAsStateWithLifecycle()
    val folders by vm.allFolders.collectAsStateWithLifecycle()

    val categoryLabels = SearchCategory.entries.associateWith { stringResource(it.labelRes()) }
    val dateLabels = DateRange.entries.associateWith { stringResource(it.labelRes()) }
    val sortLabels = SearchSort.entries.associateWith { stringResource(it.labelRes()) }
    val folderNodes = remember(folders) { FolderTree.flatten(folders) }
    val anyTag = stringResource(R.string.any_tag)
    val anyBoard = stringResource(R.string.any_board)
    val anyFolder = stringResource(R.string.any_folder)

    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterMenu(
            label = if (filters.category == SearchCategory.All) stringResource(R.string.filter_type) else categoryLabels.getValue(filters.category),
            active = filters.category != SearchCategory.All,
            options = SearchCategory.entries.map { it.name to categoryLabels.getValue(it) },
            onSelect = { key -> vm.setCategory(SearchCategory.valueOf(key!!)) },
        )
        FilterChip(
            selected = filters.favoritesOnly,
            onClick = { vm.setFavoritesOnly(!filters.favoritesOnly) },
            label = { Text(stringResource(R.string.filter_favorites)) },
            leadingIcon = if (filters.favoritesOnly) ({ Icon(Icons.Filled.Check, null) }) else null,
        )
        FilterMenu(
            label = tags.firstOrNull { it.id == filters.tagId }?.name ?: stringResource(R.string.filter_tag),
            active = filters.tagId != null,
            options = listOf<Pair<String?, String>>(null to anyTag) + tags.map { it.id to it.name },
            onSelect = vm::setTag,
        )
        FilterMenu(
            label = boards.firstOrNull { it.id == filters.boardId }?.name ?: stringResource(R.string.filter_board),
            active = filters.boardId != null,
            options = listOf<Pair<String?, String>>(null to anyBoard) + boards.map { it.id to it.name },
            onSelect = vm::setBoard,
        )
        FilterMenu(
            label = folders.firstOrNull { it.id == filters.folderId }?.name ?: stringResource(R.string.filter_folder),
            active = filters.folderId != null,
            options = listOf<Pair<String?, String>>(null to anyFolder) + folderNodes.map { it.folder.id to ("  ".repeat(it.depth) + it.folder.name) },
            onSelect = vm::setFolder,
        )
        FilterMenu(
            label = if (filters.dateRange == DateRange.Any) stringResource(R.string.filter_date) else dateLabels.getValue(filters.dateRange),
            active = filters.dateRange != DateRange.Any,
            options = DateRange.entries.map { it.name to dateLabels.getValue(it) },
            onSelect = { key -> vm.setDateRange(DateRange.valueOf(key!!)) },
        )
        FilterMenu(
            label = sortLabels.getValue(filters.sort),
            active = false,
            options = SearchSort.entries.map { it.name to sortLabels.getValue(it) },
            onSelect = { key -> vm.setSort(SearchSort.valueOf(key!!)) },
        )
        if (hasCriteria || text.isNotEmpty()) {
            TextButton(onClick = vm::clearFilters) { Text(stringResource(R.string.clear_filters)) }
        }
    }
}

@Composable
private fun FilterMenu(label: String, active: Boolean, options: List<Pair<String?, String>>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp)) },
            leadingIcon = if (active) ({ Icon(Icons.Filled.Check, null) }) else null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(key) })
            }
        }
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onForget: (String) -> Unit, onClear: () -> Unit) {
    val colors = RcTheme.colors
    if (recent.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Search,
            title = stringResource(R.string.search_prompt_title),
            message = stringResource(R.string.search_references_prompt),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_searches), color = colors.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text(stringResource(R.string.clear_history)) }
            }
        }
        items(recent, key = { it }) { term ->
            Row(Modifier.fillMaxWidth().clickable { onPick(term) }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, null, tint = colors.textMuted)
                Text(term, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp))
                IconButton(onClick = { onForget(term) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove_recent, term)) }
            }
        }
    }
}

@Composable
private fun Results(
    items: LazyPagingItems<Reference>,
    fileFor: (Reference) -> java.io.File?,
    onOpen: (Reference) -> Unit,
    onShowInBoard: (Reference) -> Unit,
) {
    if (items.itemCount == 0) {
        EmptyState(
            icon = Icons.Filled.Search,
            title = stringResource(R.string.search_no_results_title),
            message = stringResource(R.string.search_refs_no_results_message),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        items(count = items.itemCount, key = androidx.paging.compose.itemKey { it.id }) { index ->
            val ref = items[index] ?: return@items
            ResultRow(ref, remember(ref.localRelativePath) { fileFor(ref) }, onOpen = { onOpen(ref) }, onShowInBoard = { onShowInBoard(ref) })
        }
    }
}

@Composable
private fun ResultRow(ref: Reference, file: java.io.File?, onOpen: () -> Unit, onShowInBoard: () -> Unit) {
    val colors = RcTheme.colors
    val context = LocalContext.current
    Surface(
        onClick = onOpen,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (file != null && ref.isPreviewableImage) {
                    AsyncImage(file, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(if (ref.isLink) Icons.Filled.Link else Icons.Filled.Description, null, tint = colors.textMuted)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(ref.title, color = colors.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    add(ref.typeLabel)
                    if (!ref.isLink) add(Formatter.formatShortFileSize(context, ref.fileSizeBytes))
                    add(DateUtils.getRelativeTimeSpanString(ref.createdAt).toString())
                }.joinToString(" · ")
                Text(meta, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onShowInBoard) {
                Icon(Icons.Filled.Dashboard, stringResource(R.string.show_in_board), tint = colors.textSecondary)
            }
        }
    }
}

private fun SearchCategory.labelRes() = when (this) {
    SearchCategory.All -> R.string.cat_all
    SearchCategory.Image -> R.string.cat_images
    SearchCategory.Video -> R.string.cat_video
    SearchCategory.Pdf -> R.string.cat_pdf
    SearchCategory.Text -> R.string.cat_text
    SearchCategory.Link -> R.string.cat_links
    SearchCategory.Other -> R.string.cat_other
}

private fun DateRange.labelRes() = when (this) {
    DateRange.Any -> R.string.date_any
    DateRange.Week -> R.string.date_week
    DateRange.Month -> R.string.date_month
    DateRange.Year -> R.string.date_year
}

private fun SearchSort.labelRes() = when (this) {
    SearchSort.Newest -> R.string.sort_newest
    SearchSort.Oldest -> R.string.sort_oldest
    SearchSort.Name -> R.string.sort_name
    SearchSort.Size -> R.string.sort_size
}
