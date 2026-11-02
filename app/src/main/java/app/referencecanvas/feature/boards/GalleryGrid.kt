package app.referencecanvas.feature.boards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.domain.model.GalleryDensity
import app.referencecanvas.domain.model.Reference
import coil.compose.AsyncImage
import java.io.File

private fun GalleryDensity.minCell(): Dp = when (this) {
    GalleryDensity.Compact -> 96.dp
    GalleryDensity.Comfortable -> 140.dp
    GalleryDensity.Large -> 200.dp
}

@Composable
fun GalleryGrid(
    items: LazyPagingItems<Reference>,
    density: GalleryDensity,
    selection: Set<String>,
    fileFor: (Reference) -> File?,
    onClick: (Reference) -> Unit,
    onLongClick: (Reference) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(density.minCell()),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val reference = items[index] ?: return@items
            val file = remember(reference.localRelativePath) { fileFor(reference) }
            GalleryCell(
                reference = reference,
                file = file,
                isSelected = reference.id in selection,
                selectionMode = selection.isNotEmpty(),
                onClick = { onClick(reference) },
                onLongClick = { onLongClick(reference) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    reference: Reference,
    file: File?,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = RcTheme.colors
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.accent else colors.border, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = reference.title
                selected = isSelected
            },
    ) {
        if (file != null && reference.isPreviewableImage) {
            // Coil decodes to the cell's size, never the original resolution, and cancels off-screen requests.
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FileTile(reference)
        }
        if (reference.isFavorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.favorite_indicator),
                tint = colors.accent,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp),
            )
        }
        if (selectionMode) {
            // Shape + check mark, so selection never relies on color alone.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accent else colors.background.copy(alpha = 0.6f))
                    .border(1.5.dp, if (isSelected) colors.accent else colors.textSecondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.selected), tint = colors.onAccent, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun FileTile(reference: Reference) {
    val colors = RcTheme.colors
    Column(
        Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(32.dp))
        Text(reference.typeLabel, style = MaterialTheme.typography.labelMedium, color = colors.accent, maxLines = 1)
        Text(
            reference.originalFileName,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
