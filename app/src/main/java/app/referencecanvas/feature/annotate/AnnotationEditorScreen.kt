package app.referencecanvas.feature.annotate

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.referencecanvas.R
import app.referencecanvas.domain.annotation.Annotation
import app.referencecanvas.domain.annotation.AnnotationData
import app.referencecanvas.domain.annotation.AnnotationGeometry
import app.referencecanvas.domain.annotation.AnnotationKind
import app.referencecanvas.domain.annotation.AnnotationStyle
import app.referencecanvas.domain.annotation.Pt
import app.referencecanvas.domain.annotation.ShapeRender
import app.referencecanvas.ui.FileActions
import app.referencecanvas.ui.components.ConfirmDialog
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

private val EditorBackground = Color.Black
private val ChromeBackground = Color(0xFF13151A)
private val ChromeText = Color(0xFFF4F5F7)
private val ChromeDim = Color(0xFFB0B5C0)
private val ChromeAccent = Color(0xFF8B7CF6)
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
private const val MIN_SHAPE_PX = 6f

@Composable
fun AnnotationEditorScreen(onBack: () -> Unit, viewModel: AnnotationViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reference by viewModel.reference.collectAsStateWithLifecycle()
    val set by viewModel.items.collectAsStateWithLifecycle()
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val color by viewModel.color.collectAsStateWithLifecycle()
    val widthSlider by viewModel.widthSlider.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    var textAt by remember { mutableStateOf<Pt?>(null) }
    var showClear by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<File?>(null) }

    val items = set?.items.orEmpty()
    val file = remember(reference?.localRelativePath) { reference?.let { viewModel.sourceFile() } }
    val title = reference?.title.orEmpty()

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val rendered = pendingSave
        pendingSave = null
        if (uri != null && rendered != null) {
            scope.launch {
                val ok = viewModel.copyTo(rendered, uri)
                Toast.makeText(context, if (ok) R.string.export_saved else R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(set?.unreadable) {
        val n = set?.unreadable ?: 0
        if (n > 0) Toast.makeText(context, context.getString(R.string.annotations_unreadable, n), Toast.LENGTH_LONG).show()
    }

    fun exportThen(action: suspend (File) -> Unit) {
        scope.launch {
            exporting = true
            val rendered = viewModel.renderFlattened()
            exporting = false
            if (rendered == null) Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show() else action(rendered)
        }
    }

    Column(Modifier.fillMaxSize().background(EditorBackground)) {
        Row(Modifier.fillMaxWidth().background(ChromeBackground).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.done), tint = ChromeText) }
            Text(
                stringResource(R.string.annotate),
                color = ChromeText,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::undo, enabled = history.canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.canvas_undo), tint = if (history.canUndo) ChromeText else ChromeDim.copy(alpha = 0.4f))
            }
            IconButton(onClick = viewModel::redo, enabled = history.canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.canvas_redo), tint = if (history.canRedo) ChromeText else ChromeDim.copy(alpha = 0.4f))
            }
            IconButton(onClick = viewModel::deleteSelected, enabled = selectedId != null) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete_selected), tint = if (selectedId != null) ChromeText else ChromeDim.copy(alpha = 0.4f))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions), tint = ChromeText) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clear_all)) },
                        enabled = items.isNotEmpty(),
                        onClick = { menuOpen = false; showClear = true },
                    )
                    if (viewModel.canSaveToBoard) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_save_reference)) },
                            onClick = {
                                menuOpen = false
                                exportThen { rendered ->
                                    val report = viewModel.saveAsReference(rendered)
                                    val ok = report != null && report.imported > 0
                                    Toast.makeText(context, if (ok) R.string.export_saved_reference else R.string.export_failed, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_share)) },
                        onClick = { menuOpen = false; exportThen { FileActions.share(context, it, "image/png", title) } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_save_device)) },
                        onClick = {
                            menuOpen = false
                            exportThen { rendered ->
                                pendingSave = rendered
                                createDocument.launch("${title.ifBlank { "image" }}-annotated.png")
                            }
                        },
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (file == null || reference?.isPreviewableImage == false) {
                Text(stringResource(R.string.annotate_images_only), color = ChromeDim, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            } else {
                EditorCanvas(
                    file = file,
                    title = title,
                    items = items,
                    tool = tool,
                    color = color,
                    widthSlider = widthSlider,
                    selectedId = selectedId,
                    onCommit = viewModel::commit,
                    onErase = viewModel::erase,
                    onSelect = viewModel::select,
                    onTextRequest = { textAt = it },
                )
            }
            if (exporting) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = ChromeText)
            }
        }

        StyleBar(color, widthSlider, viewModel::setColor, viewModel::setWidthSlider)
        ToolBar(tool, viewModel::setTool)
    }

    textAt?.let { point ->
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = { Text(stringResource(R.string.add_text)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.text_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        viewModel.commit(
                            AnnotationData(
                                kind = AnnotationKind.Text,
                                color = color,
                                width = AnnotationStyle.widthFor(widthSlider),
                                points = listOf(point),
                                text = text.trim(),
                                textSize = AnnotationStyle.textSizeFor(widthSlider),
                            ),
                        )
                        textAt = null
                    },
                ) { Text(stringResource(R.string.add)) }
            },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_all_title),
            message = stringResource(R.string.clear_all_message),
            confirmLabel = stringResource(R.string.clear_all),
            onConfirm = { showClear = false; viewModel.clearAll() },
            onDismiss = { showClear = false },
        )
    }
}

private fun buildShape(tool: AnnotationTool, color: Int, slider: Float, pts: List<Pt>): AnnotationData? {
    val width = AnnotationStyle.widthFor(slider)
    return when (tool) {
        AnnotationTool.Pen -> AnnotationData(AnnotationKind.Pen, color, width, pts)
        AnnotationTool.Highlighter -> AnnotationData(AnnotationKind.Highlighter, color, width, pts)
        AnnotationTool.Line -> twoPoint(AnnotationKind.Line, color, width, pts)
        AnnotationTool.Arrow -> twoPoint(AnnotationKind.Arrow, color, width, pts)
        AnnotationTool.Rect -> twoPoint(AnnotationKind.Rect, color, width, pts)
        AnnotationTool.Ellipse -> twoPoint(AnnotationKind.Ellipse, color, width, pts)
        else -> null
    }
}

private fun twoPoint(kind: AnnotationKind, color: Int, width: Float, pts: List<Pt>): AnnotationData? =
    if (pts.size >= 2) AnnotationData(kind, color, width, listOf(pts.first(), pts.last())) else null

/**
 * The picture, its annotations and the drawing gestures. One finger draws (or pans with the Hand tool); two fingers
 * always pinch-zoom and pan, and cancel any half-drawn shape so a pinch never leaves a stray mark.
 */
@Composable
private fun EditorCanvas(
    file: File,
    title: String,
    items: List<Annotation>,
    tool: AnnotationTool,
    color: Int,
    widthSlider: Float,
    selectedId: String?,
    onCommit: (AnnotationData) -> Unit,
    onErase: (Set<String>) -> Unit,
    onSelect: (String?) -> Unit,
    onTextRequest: (Pt) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var aspect by remember { mutableFloatStateOf(0f) }
    var pending by remember { mutableStateOf<AnnotationData?>(null) }
    var erasing by remember { mutableStateOf<Set<String>>(emptySet()) }
    val toolNow by rememberUpdatedState(tool)
    val colorNow by rememberUpdatedState(color)
    val sliderNow by rememberUpdatedState(widthSlider)
    val itemsNow by rememberUpdatedState(items)
    val measurer = rememberTextMeasurer()
    val touchReachPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }

    fun clamp(o: Offset, s: Float) = Offset(
        o.x.coerceIn(box.width * (1f - s), 0f),
        o.y.coerceIn(box.height * (1f - s), 0f),
    )

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val t = toolNow
                    val fit = AnnotationGeometry.fit(box.width.toFloat(), box.height.toFloat(), if (aspect > 0f) aspect else 1f)
                    fun toBase(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)
                    fun toNorm(p: Offset): Pt = toBase(p).let { fit.toNormalized(it.x, it.y) }
                    fun hitIds(p: Offset, skip: Set<String>): List<String> {
                        val base = toBase(p)
                        val reach = touchReachPx / scale
                        return itemsNow.filter { a ->
                            a.id !in skip && AnnotationGeometry.hit(a.data, base.x - fit.left, base.y - fit.top, fit.width, fit.height, reach)
                        }.map { it.id }
                    }

                    val slop = viewConfiguration.touchSlop
                    var transforming = t == AnnotationTool.Hand || aspect <= 0f
                    var moved = false
                    val points = ArrayList<Pt>().apply { add(toNorm(down.position)) }
                    val erased = HashSet<String>()
                    if (!transforming && t == AnnotationTool.Eraser) {
                        erased += hitIds(down.position, erased)
                        erasing = erased.toSet()
                    }

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1) {
                            if (!transforming) {
                                transforming = true
                                pending = null
                                erased.clear()
                                erasing = emptySet()
                            }
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid.isSpecified) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(MIN_SCALE, MAX_SCALE)
                                val moved2 = centroid - (centroid - offset) * (newScale / scale) + event.calculatePan()
                                offset = if (newScale <= MIN_SCALE) Offset.Zero else clamp(moved2, newScale)
                                scale = newScale
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressed == 1) {
                            val change = event.changes.first { it.pressed }
                            if (transforming) {
                                if (t == AnnotationTool.Hand || scale > MIN_SCALE) {
                                    offset = clamp(offset + change.positionChange(), scale)
                                    change.consume()
                                }
                            } else {
                                if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                                when (t) {
                                    AnnotationTool.Pen, AnnotationTool.Highlighter -> if (moved) {
                                        points += toNorm(change.position)
                                        pending = buildShape(t, colorNow, sliderNow, points.toList())
                                        change.consume()
                                    }
                                    AnnotationTool.Line, AnnotationTool.Arrow, AnnotationTool.Rect, AnnotationTool.Ellipse -> if (moved) {
                                        pending = buildShape(t, colorNow, sliderNow, listOf(points.first(), toNorm(change.position)))
                                        change.consume()
                                    }
                                    AnnotationTool.Eraser -> {
                                        erased += hitIds(change.position, erased)
                                        erasing = erased.toSet()
                                        change.consume()
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!transforming) {
                        when (t) {
                            AnnotationTool.Pen, AnnotationTool.Highlighter ->
                                buildShape(t, colorNow, sliderNow, if (points.size >= 2) AnnotationGeometry.simplify(points) else points)?.let(onCommit)
                            AnnotationTool.Line, AnnotationTool.Arrow, AnnotationTool.Rect, AnnotationTool.Ellipse -> {
                                val shape = pending
                                if (moved && shape != null) {
                                    val a = shape.points.first(); val b = shape.points.last()
                                    val dx = (b.x - a.x) * fit.width * scale; val dy = (b.y - a.y) * fit.height * scale
                                    if (kotlin.math.hypot(dx, dy) >= MIN_SHAPE_PX) onCommit(shape)
                                }
                            }
                            AnnotationTool.Eraser -> if (erased.isNotEmpty()) onErase(erased.toSet())
                            AnnotationTool.Text -> if (!moved) onTextRequest(points.first())
                            AnnotationTool.Select -> if (!moved) {
                                // Top-most annotation under the finger wins.
                                val base = toBase(down.position)
                                val hit = itemsNow.sortedByDescending { it.data.seq }.firstOrNull { a ->
                                    AnnotationGeometry.hit(a.data, base.x - fit.left, base.y - fit.top, fit.width, fit.height, touchReachPx / scale)
                                }
                                onSelect(hit?.id)
                            }
                            AnnotationTool.Hand -> Unit
                        }
                    }
                    pending = null
                    erasing = emptySet()
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        ) {
            AsyncImage(
                model = file,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val d = state.result.drawable
                    if (d.intrinsicHeight > 0) aspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (aspect > 0f) {
                Canvas(Modifier.fillMaxSize()) {
                    val fit = AnnotationGeometry.fit(size.width, size.height, aspect)
                    val origin = Offset(fit.left, fit.top)
                    val hidden = erasing
                    for (a in itemsNow.sortedBy { it.data.seq }) {
                        if (a.id in hidden) continue
                        drawPrimitives(ShapeRender.toPrimitives(a.data, fit.width, fit.height), origin, measurer)
                    }
                    pending?.let { drawPrimitives(ShapeRender.toPrimitives(it, fit.width, fit.height), origin, measurer) }
                    itemsNow.firstOrNull { it.id == selectedId }?.let { sel ->
                        val b = AnnotationGeometry.bounds(sel.data, fit.width, fit.height)
                        drawRect(
                            ChromeAccent,
                            topLeft = Offset(origin.x + b[0] * fit.width, origin.y + b[1] * fit.height),
                            size = Size((b[2] - b[0]) * fit.width, (b[3] - b[1]) * fit.height),
                            style = Stroke(2.dp.toPx() / scale, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f / scale, 9f / scale))),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleBar(color: Int, slider: Float, onColor: (Int) -> Unit, onSlider: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().background(ChromeBackground).padding(top = 8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnnotationStyle.palette.forEachIndexed { index, argb ->
                val chosen = argb == color
                val label = stringResource(R.string.color_number, index + 1)
                Box(
                    Modifier
                        .padding(horizontal = 5.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(if (chosen) 3.dp else 1.dp, if (chosen) ChromeAccent else ChromeDim, CircleShape)
                        .clickable(role = Role.RadioButton) { onColor(argb) }
                        .semantics { contentDescription = label; selected = chosen },
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) Icon(Icons.Filled.Check, null, tint = if (argb == 0xFFFFFFFF.toInt() || argb == 0xFFFACC15.toInt()) Color.Black else Color.White)
                }
            }
        }
        val widthLabel = stringResource(R.string.stroke_width)
        Slider(
            value = slider,
            onValueChange = onSlider,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = widthLabel },
        )
    }
}

@Composable
private fun ToolBar(current: AnnotationTool, onSelect: (AnnotationTool) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ChromeBackground).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        for ((tool, spec) in toolSpecs) {
            val label = stringResource(spec.second)
            val chosen = tool == current
            Column(
                Modifier
                    .padding(horizontal = 2.dp)
                    .widthIn(min = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(if (chosen) 2.dp else 0.dp, if (chosen) ChromeAccent else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(role = Role.RadioButton) { onSelect(tool) }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .semantics { selected = chosen },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(spec.first, contentDescription = null, tint = if (chosen) ChromeAccent else ChromeText)
                Text(label, color = if (chosen) ChromeAccent else ChromeDim, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

private val toolSpecs: List<Pair<AnnotationTool, Pair<ImageVector, Int>>> = listOf(
    AnnotationTool.Hand to (Icons.Filled.PanTool to R.string.tool_hand),
    AnnotationTool.Pen to (Icons.Filled.Edit to R.string.tool_pen),
    AnnotationTool.Highlighter to (Icons.Filled.Highlight to R.string.tool_marker),
    AnnotationTool.Eraser to (Icons.AutoMirrored.Filled.Backspace to R.string.tool_eraser),
    AnnotationTool.Line to (Icons.Filled.HorizontalRule to R.string.tool_line),
    AnnotationTool.Arrow to (Icons.AutoMirrored.Filled.ArrowRightAlt to R.string.tool_arrow),
    AnnotationTool.Rect to (Icons.Filled.CropSquare to R.string.tool_rectangle),
    AnnotationTool.Ellipse to (Icons.Filled.RadioButtonUnchecked to R.string.tool_ellipse),
    AnnotationTool.Text to (Icons.Filled.Title to R.string.tool_text),
    AnnotationTool.Select to (Icons.Filled.TouchApp to R.string.tool_select),
)
