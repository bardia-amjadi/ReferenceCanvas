package app.referencecanvas.domain.model

/** Pseudo board id for opening a reference from search, outside any one board. */
const val NO_BOARD_CONTEXT = "_"

enum class BoardViewMode { Canvas, Gallery }

enum class BoardSortMode { Manual, Name, DateAdded, DateModified, FileType }

data class Board(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
    val coverReferenceId: String?,
    val viewMode: BoardViewMode,
    val canvasOffsetX: Double,
    val canvasOffsetY: Double,
    val canvasZoom: Float,
    val sortMode: BoardSortMode,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val folderId: String?,
)

data class BoardSummary(
    val board: Board,
    val referenceCount: Int,
)

object BoardNames {
    /** Trims and collapses whitespace. Returns null when nothing usable remains. */
    fun normalize(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        return cleaned.takeIf { it.isNotEmpty() }
    }

    fun copyName(original: String): String = "$original copy"
}
