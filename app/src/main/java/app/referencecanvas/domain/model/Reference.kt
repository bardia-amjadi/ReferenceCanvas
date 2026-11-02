package app.referencecanvas.domain.model

enum class StorageMode { MANAGED, EXTERNAL, LINK }

data class Reference(
    val id: String,
    val title: String,
    val description: String,
    val originalFileName: String,
    val mimeType: String,
    val storageMode: StorageMode,
    val localRelativePath: String?,
    val sourceUri: String?,
    val fileSizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean,
    val widthPx: Int?,
    val heightPx: Int?,
) {
    /** Images the app can decode itself. SVG is excluded: it needs a separate decoder. */
    val isPreviewableImage: Boolean
        get() = !isLink && mimeType.startsWith("image/") && !mimeType.contains("svg")

    val isLink: Boolean get() = storageMode == StorageMode.LINK

    val isVideo: Boolean get() = !isLink && mimeType.startsWith("video/")
    val isPdf: Boolean get() = !isLink && mimeType == "application/pdf"

    /** Plain text we can show safely as text (never rendered or executed): text/*, JSON/XML/YAML, or a known extension. */
    val isText: Boolean
        get() = !isLink && (
            mimeType.startsWith("text/") ||
                mimeType in TEXT_APPLICATION_TYPES ||
                originalFileName.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
            )

    val typeLabel: String
        get() = if (isLink) "LINK" else mimeType.substringAfter('/', mimeType).substringBefore('+').uppercase()
}

/** Where a newly added card lands on the canvas: a tidy grid, so nothing stacks at the origin. */
fun defaultCardPosition(index: Int, columns: Int = 8, cell: Double = 260.0): Pair<Double, Double> {
    require(index >= 0 && columns > 0)
    return (index % columns) * cell to (index / columns) * cell
}

private val TEXT_APPLICATION_TYPES = setOf(
    "application/json", "application/xml", "application/javascript", "application/x-yaml", "application/yaml",
)
private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "log", "json", "xml", "yaml", "yml", "ini", "srt", "html", "htm", "css", "js", "kt", "py")
