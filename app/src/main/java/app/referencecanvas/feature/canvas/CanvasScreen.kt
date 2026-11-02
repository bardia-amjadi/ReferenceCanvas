package app.referencecanvas.feature.canvas

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.canvas.AlignMode
import app.referencecanvas.domain.canvas.CanvasCard
import app.referencecanvas.domain.canvas.CanvasOps
import app.referencecanvas.domain.canvas.CanvasViewport
import app.referencecanvas.domain.canvas.GRID_STEP
import app.referencecanvas.domain.canvas.fitViewport
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

private const val MIN_THUMB_PX = 40.0 // below this a card is drawn as a plain rectangle
private const val MAX_THUMBS = 120 // render budget for decoded images on screen at once
private const val VISIBLE_MARGIN_PX = 96.0
private const val MIN_GRID_PX = 14.0
private const val KEY_PAN_PX = 80.0
private const val ZOOM_STEP = 1.25

@Composable
fun CanvasContent(
    onOpenReference: (String) -> Unit,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onAddCamera: () -> Unit,
    onAddLink: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CanvasViewModel = hiltViewModel(),
) {
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        if (!ready) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = RcTheme.colors.accent)
        } else {
            CanvasSurface(viewModel, onOpenReference, onAddPhotos, onAddFiles, onAddCamera, onAddLink, onPaste)
        }
    }
}

@Composable
private fun CanvasSurface(
    vm: CanvasViewModel,
    onOpenReference: (String) -> Unit,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onAddCamera: () -> Unit,
    onAddLink: () -> Unit,
    onPaste: () -> Unit,
) {
    val context = LocalContext.current
    val colors = RcTheme.colors
    val haptics = LocalHapticFeedback.current

    val cards by vm.cards.collectAsStateWithLifecycle()
    val cardList = cards.orEmpty()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selectMode by vm.selectMode.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.appSettings.collectAsStateWithLifecycle()

    // Live view state lives in Compose state so panning/zooming only re-runs layout and draw, not composition.
    val viewport = remember { mutableStateOf(vm.currentViewport()) }
    val dragIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val dragDelta = remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val latestCards by rememberUpdatedState(cardList)
    val focus = remember { FocusRequester() }

    fun coerce(vp: CanvasViewport) = vp.coerced(viewSize.width.toDouble(), viewSize.height.toDouble())
    fun zoomBy(factor: Double) {
        viewport.value = coerce(viewport.value.zoomedAround(viewSize.width / 2.0, viewSize.height / 2.0, factor))
    }
    fun panBy(dx: Double, dy: Double) { viewport.value = coerce(viewport.value.panBy(dx, dy)) }
    fun fitAll() {
        val bounds = CanvasOps.bounds(latestCards)
        viewport.value = if (bounds == null) {
            CanvasViewport(24.0, 24.0, 1f)
        } else {
            fitViewport(bounds, viewSize.width.toDouble(), viewSize.height.toDouble())
        }
    }

    val host = remember {
        GestureHost(
            viewport = viewport,
            dragIds = dragIds,
            dragDelta = dragDelta,
            viewSize = { viewSize },
            cards = { latestCards },
            idsToDrag = { hit ->
                val sel = vm.selection.value
                if (hit.id in sel) sel else setOf(hit.id)
            },
            onTap = { card ->
                when {
                    card == null -> vm.clearSelection()
                    vm.selection.value.isNotEmpty() || vm.selectMode.value -> vm.toggleSelected(card.id)
                    else -> onOpenReference(card.id)
                }
            },
            onLongPress = { card ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (card.id !in vm.selection.value) vm.toggleSelected(card.id)
            },
            onMoveCommitted = { ids, dx, dy -> vm.commitMove(ids, dx, dy) },
        )
    }

    LaunchedEffect(Unit) { snapshotFlow { viewport.value }.collect { vm.onViewportChanged(it) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) {
        vm.events.collect {
            val message = when (it) {
                CanvasEvent.DuplicateIncomplete -> R.string.canvas_duplicate_incomplete
                CanvasEvent.SaveFailed -> R.string.canvas_save_failed
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushViewport() }
    DisposableEffect(Unit) { onDispose { vm.flushViewport() } }

    // Fit a never-navigated board once the view has a size.
    LaunchedEffect(viewSize) {
        if (viewSize != IntSize.Zero && vm.consumeInitialFit()) fitAll()
    }
    // Once the database reflects a move, drop the temporary drag offset (avoids a one-frame snap-back).
    LaunchedEffect(cardList) {
        if (!host.active) {
            dragIds.value = emptySet()
            dragDelta.value = Offset.Zero
        }
    }

    // Only cards near the screen are composed/drawn; everything else costs nothing per frame.
    val ordered by remember(cardList) {
        derivedStateOf {
            if (viewSize == IntSize.Zero) {
                emptyList()
            } else {
                val area = viewport.value.visibleRect(viewSize.width.toDouble(), viewSize.height.toDouble(), VISIBLE_MARGIN_PX)
                CanvasOps.visible(cardList, area).sortedBy { it.z }
            }
        }
    }
    val thumbCards by remember(cardList) {
        derivedStateOf {
            val zoom = viewport.value.zoom
            ordered.filter { it.isPreviewableImage && it.w * zoom >= MIN_THUMB_PX }.takeLast(MAX_THUMBS)
        }
    }

    val gridColor = colors.border.copy(alpha = 0.55f)
    val cardFill = colors.surfaceVariant
    val cardEdge = colors.border
    val accent = colors.accent
    val openLabel = stringResource(R.string.canvas_open)
    val toggleLabel = stringResource(R.string.canvas_toggle_select)

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(colors.background)
            .onSizeChanged { viewSize = it }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (e.key) {
                        Key.DirectionLeft -> { panBy(KEY_PAN_PX, 0.0); true }
                        Key.DirectionRight -> { panBy(-KEY_PAN_PX, 0.0); true }
                        Key.DirectionUp -> { panBy(0.0, KEY_PAN_PX); true }
                        Key.DirectionDown -> { panBy(0.0, -KEY_PAN_PX); true }
                        Key.Plus, Key.Equals -> { zoomBy(ZOOM_STEP); true }
                        Key.Minus -> { zoomBy(1 / ZOOM_STEP); true }
                        Key.Delete -> { vm.removeSelected(); true }
                        else -> false
                    }
                }
            }
            .pointerInput(Unit) { canvasGestures(host) },
    ) {
        // Layer 1: grid, plus every visible card as a cheap rectangle (also the far-zoom level of detail).
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val vp = viewport.value
            if (settings.showCanvasGrid) drawWorldGrid(vp, gridColor)
            val moving = dragIds.value
            val delta = dragDelta.value
            for (card in ordered) {
                val shift = card.id in moving
                val sx = vp.toScreenX(card.x + if (shift) delta.x else 0f).toFloat()
                val sy = vp.toScreenY(card.y + if (shift) delta.y else 0f).toFloat()
                val sw = (card.w * vp.zoom).toFloat()
                val sh = (card.h * vp.zoom).toFloat()
                val radius = CornerRadius(minOf(6f, sw / 8f).coerceAtLeast(0f))
                val chosen = card.id in selection
                drawRoundRect(cardFill, Offset(sx, sy), Size(sw.coerceAtLeast(1.5f), sh.coerceAtLeast(1.5f)), radius)
                if (chosen) {
                    drawRoundRect(accent, Offset(sx, sy), Size(sw.coerceAtLeast(1.5f), sh.coerceAtLeast(1.5f)), radius, style = Stroke(3.dp.toPx()))
                } else if (sw > 6f) {
                    drawRoundRect(cardEdge, Offset(sx, sy), Size(sw, sh), radius, style = Stroke(1.dp.toPx()))
                }
            }
        }

        // Layer 2: decoded thumbnails, only for large-enough cards near the screen, capped by MAX_THUMBS.
        for (card in thumbCards) {
            key(card.id) {
                CardThumb(
                    card = card,
                    file = remember(card.relativePath) { vm.fileFor(card.relativePath) },
                    viewport = viewport,
                    dragIds = dragIds,
                    dragDelta = dragDelta,
                    selected = card.id in selection,
                    openLabel = openLabel,
                    toggleLabel = toggleLabel,
                    onOpen = { onOpenReference(card.id) },
                    onToggleSelect = { vm.toggleSelected(card.id) },
                )
            }
        }

        if (cardList.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.canvas_empty_title), color = colors.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.canvas_empty_message), color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        CanvasToolbar(
            selectionCount = selection.size,
            selectMode = selectMode,
            history = history,
            showGrid = settings.showCanvasGrid,
            snap = settings.snapToGrid,
            onAddPhotos = onAddPhotos,
            onAddFiles = onAddFiles,
            onAddCamera = onAddCamera,
            onAddLink = onAddLink,
            onPaste = onPaste,
            onToggleSelectMode = { vm.setSelectMode(!selectMode) },
            onUndo = vm::undo,
            onRedo = vm::redo,
            onFit = ::fitAll,
            onZoomIn = { zoomBy(ZOOM_STEP) },
            onZoomOut = { zoomBy(1 / ZOOM_STEP) },
            onResetView = { viewport.value = CanvasViewport(24.0, 24.0, 1f) },
            onToggleGrid = { vm.setShowGrid(!settings.showCanvasGrid) },
            onToggleSnap = { vm.setSnap(!settings.snapToGrid) },
            onSelectAll = vm::selectAll,
            onClearSelection = {
                vm.clearSelection()
                vm.setSelectMode(false)
            },
            onAlign = vm::align,
            onArrangeGrid = vm::arrangeInGrid,
            onNudge = vm::nudge,
            onFront = vm::bringToFront,
            onBack = vm::sendToBack,
            onDuplicate = vm::duplicateSelected,
            onRemove = vm::removeSelected,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
    }
}

@Composable
private fun CardThumb(
    card: CanvasCard,
    file: File?,
    viewport: State<CanvasViewport>,
    dragIds: State<Set<String>>,
    dragDelta: State<Offset>,
    selected: Boolean,
    openLabel: String,
    toggleLabel: String,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    val context = LocalContext.current
    val colors = RcTheme.colors
    // Decode size is bucketed to powers of two so a pinch doesn't re-decode the image on every frame.
    val bucket by remember(card.id) {
        derivedStateOf { decodeBucket(max(card.w, card.h) * viewport.value.zoom) }
    }
    val request = remember(file, bucket) { ImageRequest.Builder(context).data(file).size(bucket).build() }

    Box(
        Modifier
            .offset {
                val vp = viewport.value
                val moved = card.id in dragIds.value
                val dx = if (moved) dragDelta.value.x else 0f
                val dy = if (moved) dragDelta.value.y else 0f
                IntOffset(vp.toScreenX(card.x + dx).roundToInt(), vp.toScreenY(card.y + dy).roundToInt())
            }
            .layout { measurable, _ ->
                val z = viewport.value.zoom
                val w = (card.w * z).roundToInt().coerceAtLeast(1)
                val h = (card.h * z).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .clip(RoundedCornerShape(6.dp))
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) colors.accent else colors.border,
                RoundedCornerShape(6.dp),
            )
            .semantics {
                contentDescription = card.title
                this.selected = selected
                onClick(label = openLabel) { onOpen(); true }
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { onToggleSelect(); true })
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun decodeBucket(px: Double): Int {
    val clamped = px.coerceIn(32.0, 2048.0)
    return 1 shl ceil(ln(clamped) / ln(2.0)).toInt()
}

private fun DrawScope.drawWorldGrid(vp: CanvasViewport, color: Color) {
    var step = GRID_STEP * vp.zoom
    var guard = 0
    while (step < MIN_GRID_PX && guard++ < 8) step *= 5 // coarser lines when zoomed far out
    if (step < MIN_GRID_PX) return
    var x = ((vp.offsetX % step) + step) % step
    while (x < size.width) {
        drawLine(color, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), strokeWidth = 1f)
        x += step
    }
    var y = ((vp.offsetY % step) + step) % step
    while (y < size.height) {
        drawLine(color, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), strokeWidth = 1f)
        y += step
    }
}

@Composable
private fun CanvasToolbar(
    selectionCount: Int,
    selectMode: Boolean,
    history: HistoryState,
    showGrid: Boolean,
    snap: Boolean,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onAddCamera: () -> Unit,
    onAddLink: () -> Unit,
    onPaste: () -> Unit,
    onToggleSelectMode: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFit: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetView: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleSnap: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onAlign: (AlignMode) -> Unit,
    onArrangeGrid: () -> Unit,
    onNudge: (Int, Int) -> Unit,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RcTheme.colors
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        modifier = modifier.widthIn(max = 560.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (selectionCount > 0) {
                ToolButton(Icons.Filled.Close, stringResource(R.string.clear_selection), onClearSelection)
                Text(
                    stringResource(R.string.selection_count, selectionCount),
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                ArrangeMenu(selectionCount, onAlign, onArrangeGrid, onNudge)
                ToolButton(Icons.Filled.FlipToFront, stringResource(R.string.canvas_front), onFront)
                ToolButton(Icons.Filled.FlipToBack, stringResource(R.string.canvas_back), onBack)
                ToolButton(Icons.Filled.ContentCopy, stringResource(R.string.duplicate), onDuplicate)
                ToolButton(Icons.Filled.RemoveCircleOutline, stringResource(R.string.remove_from_board), onRemove)
            } else {
                var addOpen by remember { mutableStateOf(false) }
                Box {
                    ToolButton(Icons.Filled.Add, stringResource(R.string.add_references)) { addOpen = true }
                    DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_from_photos)) }, onClick = { addOpen = false; onAddPhotos() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_from_files)) }, onClick = { addOpen = false; onAddFiles() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_from_camera)) }, onClick = { addOpen = false; onAddCamera() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.add_link)) }, onClick = { addOpen = false; onAddLink() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.paste)) }, onClick = { addOpen = false; onPaste() })
                    }
                }
                ToolButton(
                    if (selectMode) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    stringResource(R.string.canvas_select_mode),
                    onToggleSelectMode,
                    active = selectMode,
                )
                ToolButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.canvas_undo), onUndo, enabled = history.canUndo)
                ToolButton(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.canvas_redo), onRedo, enabled = history.canRedo)
                ToolButton(Icons.Filled.FitScreen, stringResource(R.string.canvas_fit), onFit)
                var moreOpen by remember { mutableStateOf(false) }
                Box {
                    ToolButton(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) { moreOpen = true }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.canvas_zoom_in)) }, onClick = { onZoomIn() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.canvas_zoom_out)) }, onClick = { onZoomOut() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.canvas_reset_view)) }, onClick = { moreOpen = false; onResetView() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.canvas_select_all)) }, onClick = { moreOpen = false; onSelectAll() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.canvas_show_grid)) },
                            leadingIcon = { Check(showGrid) },
                            onClick = { onToggleGrid() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.canvas_snap)) },
                            leadingIcon = { Check(snap) },
                            onClick = { onToggleSnap() },
                        )
                    }
                }
            }
        }
    }
}

/** A check mark, or an empty box of the same size, so on/off never depends on color alone. */
@Composable
private fun Check(on: Boolean) {
    Icon(
        if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
        contentDescription = stringResource(if (on) R.string.state_on else R.string.state_off),
    )
}

@Composable
private fun ArrangeMenu(
    selectionCount: Int,
    onAlign: (AlignMode) -> Unit,
    onArrangeGrid: () -> Unit,
    onNudge: (Int, Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val multi = selectionCount >= 2
    Box {
        ToolButton(Icons.Filled.ViewModule, stringResource(R.string.canvas_arrange)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: Int, enabled: Boolean = true, keepOpen: Boolean = false, action: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    enabled = enabled,
                    onClick = { if (!keepOpen) open = false; action() },
                )
            }
            item(R.string.align_left, multi) { onAlign(AlignMode.Left) }
            item(R.string.align_center, multi) { onAlign(AlignMode.CenterX) }
            item(R.string.align_right, multi) { onAlign(AlignMode.Right) }
            item(R.string.align_top, multi) { onAlign(AlignMode.Top) }
            item(R.string.align_middle, multi) { onAlign(AlignMode.CenterY) }
            item(R.string.align_bottom, multi) { onAlign(AlignMode.Bottom) }
            item(R.string.arrange_grid, multi) { onArrangeGrid() }
            // Button alternatives to dragging, one grid step at a time (menu stays open for repeated taps).
            item(R.string.nudge_left, keepOpen = true) { onNudge(-1, 0) }
            item(R.string.nudge_right, keepOpen = true) { onNudge(1, 0) }
            item(R.string.nudge_up, keepOpen = true) { onNudge(0, -1) }
            item(R.string.nudge_down, keepOpen = true) { onNudge(0, 1) }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val colors = RcTheme.colors
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                !enabled -> colors.textMuted.copy(alpha = 0.5f)
                active -> colors.accent
                else -> colors.textPrimary
            },
        )
    }
}
