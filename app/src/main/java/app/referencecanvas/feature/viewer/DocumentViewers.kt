package app.referencecanvas.feature.viewer

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.referencecanvas.R
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val DocText = Color(0xFFF4F5F7)
private val DocTextDim = Color(0xFFB0B5C0)

@Composable
private fun PreviewProblem(title: String, body: String, onRetry: () -> Unit, onOpenExternally: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = DocText, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = DocTextDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            Button(onClick = onOpenExternally) { Text(stringResource(R.string.open_with)) }
        }
    }
}

// ------------------------------------------------------------------ video

/** Local playback with the standard Media3 controls (play/pause, seek bar). The file is never executed or uploaded. */
@Composable
fun VideoPage(file: File, title: String, onRetry: () -> Unit, onOpenExternally: () -> Unit) {
    val context = LocalContext.current
    var failed by remember(file) { mutableStateOf(false) }
    if (failed) {
        PreviewProblem(stringResource(R.string.video_failed_title), title, onRetry, onOpenExternally)
        return
    }
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    val exoPlayer = player
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = exoPlayer; useController = true } },
        modifier = Modifier.fillMaxSize(),
    )
}

// -------------------------------------------------------------------- pdf

private sealed interface PdfState {
    data object Opening : PdfState
    data class Ready(val source: PdfSource) : PdfState
    data object Protected : PdfState
    data object Broken : PdfState
}

/** One open PDF. PdfRenderer allows a single open page at a time, so all rendering is serialized. */
private class PdfSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int = renderer.pageCount
    private val lock = Mutex()
    private var closed = false

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val scale = widthPx.toFloat() / page.width
                val bitmap = Bitmap.createBitmap(widthPx, (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    suspend fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            renderer.close()
            descriptor.close()
        }
    }

    companion object {
        /** Null result types are explicit: protected (needs a password) vs unreadable. */
        fun open(file: File): Any = try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfSource(pfd, PdfRenderer(pfd))
            } catch (e: SecurityException) {
                pfd.close(); PdfState.Protected
            } catch (e: IOException) {
                pfd.close(); PdfState.Broken
            }
        } catch (e: IOException) {
            PdfState.Broken
        }
    }
}

/** Scrollable pages rendered on demand at screen width, with a small memory-bounded cache. */
@Composable
fun PdfPage(file: File, onRetry: () -> Unit, onOpenExternally: () -> Unit) {
    val state by produceState<PdfState>(PdfState.Opening, file) {
        value = withContext(Dispatchers.IO) {
            when (val opened = PdfSource.open(file)) {
                is PdfSource -> if (opened.pageCount > 0) PdfState.Ready(opened) else PdfState.Broken.also { opened.close() }
                else -> opened as PdfState
            }
        }
    }
    val current = state
    DisposableEffect(current) {
        onDispose {
            if (current is PdfState.Ready) CoroutineScope(Dispatchers.IO + NonCancellable).launch { current.source.close() }
        }
    }
    when (current) {
        PdfState.Opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DocText) }
        PdfState.Protected -> PreviewProblem(stringResource(R.string.pdf_protected_title), stringResource(R.string.pdf_protected_message), onRetry, onOpenExternally)
        PdfState.Broken -> PreviewProblem(stringResource(R.string.pdf_failed_title), stringResource(R.string.preview_failed_generic), onRetry, onOpenExternally)
        is PdfState.Ready -> PdfPages(current.source)
    }
}

@Composable
private fun PdfPages(source: PdfSource) {
    val cache = remember(source) {
        object : LruCache<Int, Bitmap>(PDF_CACHE_BYTES) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
    }
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = min(with(LocalDensity.current) { maxWidth.roundToPx() }, PDF_MAX_WIDTH_PX)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(List(source.pageCount) { it }, key = { _, page -> page }) { _, index ->
                val bitmap by produceState<Bitmap?>(cache.get(index), index, widthPx) {
                    value = cache.get(index) ?: source.render(index, widthPx)?.also { cache.put(index, it) }
                }
                val shown = bitmap
                if (shown == null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(PAGE_PLACEHOLDER_ASPECT).background(Color(0xFF1B1E25)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = DocTextDim)
                    }
                } else {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = stringResource(R.string.pdf_page_description, index + 1, source.pageCount),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.pdf_page_indicator, listState.firstVisibleItemIndex + 1, source.pageCount),
            color = DocText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color(0xB3000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private const val PDF_CACHE_BYTES = 48 * 1024 * 1024
private const val PDF_MAX_WIDTH_PX = 1600
private const val PAGE_PLACEHOLDER_ASPECT = 0.707f

// ------------------------------------------------------------------- text

private sealed interface TextState {
    data object Loading : TextState
    data class Loaded(val lines: List<String>, val truncated: Boolean) : TextState
    data object NotText : TextState
    data object Unreadable : TextState
}

private const val TEXT_MAX_BYTES = 512 * 1024

private fun loadText(file: File): TextState = try {
    val buffer = ByteArray(TEXT_MAX_BYTES)
    var read = 0
    FileInputStream(file).use { input ->
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
    }
    val truncated = file.length() > read
    // NUL bytes in the first few KB mean this is binary data, not text.
    if ((0 until min(read, 4096)).any { buffer[it] == 0.toByte() }) {
        TextState.NotText
    } else {
        TextState.Loaded(String(buffer, 0, read, Charsets.UTF_8).lines(), truncated)
    }
} catch (_: IOException) {
    TextState.Unreadable
}

/** Shows the first 512 KB of a text-like file as plain, selectable text. Nothing is rendered or run. */
@Composable
fun TextPage(file: File, onRetry: () -> Unit, onOpenExternally: () -> Unit) {
    val state by produceState<TextState>(TextState.Loading, file) { value = withContext(Dispatchers.IO) { loadText(file) } }
    when (val s = state) {
        TextState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DocText) }
        TextState.NotText -> PreviewProblem(stringResource(R.string.text_binary_title), stringResource(R.string.text_binary_message), onRetry, onOpenExternally)
        TextState.Unreadable -> PreviewProblem(stringResource(R.string.text_failed_title), stringResource(R.string.preview_failed_generic), onRetry, onOpenExternally)
        is TextState.Loaded -> SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 72.dp, bottom = 24.dp)) {
                itemsIndexed(s.lines) { _, line ->
                    Text(line.ifEmpty { " " }, color = DocText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
                if (s.truncated) {
                    item { Text(stringResource(R.string.text_truncated), color = DocTextDim, modifier = Modifier.padding(top = 16.dp)) }
                }
            }
        }
    }
}
