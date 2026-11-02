package app.referencecanvas.data.repository

import app.referencecanvas.core.database.BoardEntity
import app.referencecanvas.core.database.BoardSummaryRow
import app.referencecanvas.core.util.toEnumOr
import app.referencecanvas.domain.model.Board
import app.referencecanvas.domain.model.BoardSortMode
import app.referencecanvas.domain.model.BoardSummary
import app.referencecanvas.domain.model.BoardViewMode

internal fun BoardEntity.toDomain() = Board(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
    coverReferenceId = coverReferenceId,
    viewMode = viewMode.toEnumOr(BoardViewMode.Canvas),
    canvasOffsetX = canvasOffsetX,
    canvasOffsetY = canvasOffsetY,
    canvasZoom = canvasZoom,
    sortMode = sortMode.toEnumOr(BoardSortMode.Manual),
    isFavorite = isFavorite,
    isPinned = isPinned,
    folderId = folderId,
)

internal fun BoardSummaryRow.toDomain() = BoardSummary(board.toDomain(), referenceCount)
