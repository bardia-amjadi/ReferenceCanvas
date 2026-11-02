package app.referencecanvas.domain.model

data class Tag(val id: String, val name: String, val color: Int?)

data class Folder(val id: String, val name: String, val parentId: String?)

data class FolderNode(val folder: Folder, val depth: Int)

object TagNames {
    /** Trims and collapses whitespace, caps length. Returns null if nothing usable remains. */
    fun normalize(raw: String): String? =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }

    const val MAX_LENGTH = 64
}

object FolderTree {
    /** Depth-first, alphabetical. Tolerates orphans and cycles: anything unreachable is listed as a root. */
    fun flatten(folders: List<Folder>): List<FolderNode> {
        val ids = folders.mapTo(HashSet()) { it.id }
        val byParent = folders.groupBy { it.parentId?.takeIf { p -> p in ids } }
        val out = ArrayList<FolderNode>(folders.size)
        val seen = HashSet<String>()
        fun visit(folder: Folder, depth: Int) {
            if (!seen.add(folder.id)) return
            out += FolderNode(folder, depth)
            byParent[folder.id].orEmpty().sortedBy { it.name.lowercase() }.forEach { visit(it, depth + 1) }
        }
        byParent[null].orEmpty().sortedBy { it.name.lowercase() }.forEach { visit(it, 0) }
        folders.sortedBy { it.name.lowercase() }.forEach { visit(it, 0) } // cycle members, if any
        return out
    }

    /** True if making [newParentId] the parent of [folderId] would create a loop (or hits an existing one). */
    fun wouldCreateCycle(folderId: String, newParentId: String?, parentOf: (String) -> String?): Boolean {
        var current = newParentId
        val seen = HashSet<String>()
        while (current != null) {
            if (current == folderId || !seen.add(current)) return true
            current = parentOf(current)
        }
        return false
    }

    /** Root-to-leaf chain for [folderId]; empty for the root. Cycle-safe. */
    fun path(folderId: String?, byId: Map<String, Folder>): List<Folder> {
        val chain = ArrayDeque<Folder>()
        val seen = HashSet<String>()
        var current = folderId
        while (current != null && seen.add(current)) {
            val folder = byId[current] ?: break
            chain.addFirst(folder)
            current = folder.parentId
        }
        return chain.toList()
    }
}

/** Only explicit http(s) links become references; nothing here ever touches the network. */
object LinkParser {
    private val SCHEMES = setOf("http", "https")
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$")

    /** Typed input: a bare domain gets https://. Returns null for anything that isn't a plain web link. */
    fun parse(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in text) text else "https://$text"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme !in SCHEMES) return null
        val rest = withScheme.substringAfter("://")
        val host = hostOf(withScheme)
        val validHost = host == "localhost" || (HOST.matches(host) && '.' in host)
        return if (validHost && rest.isNotEmpty()) "$scheme://$rest" else null
    }

    /** Shared text: the first token that already starts with http:// or https://. */
    fun extractUrl(text: String): String? = text.split(Regex("\\s+"))
        .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
        ?.let { parse(it.trimEnd('.', ',', ')', ';')) }

    fun hostOf(url: String): String = url.substringAfter("://")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase()
}

enum class SearchCategory { All, Image, Video, Pdf, Text, Link, Other }
enum class SearchSort { Newest, Oldest, Name, Size }
enum class DateRange(val days: Int?) { Any(null), Week(7), Month(30), Year(365) }

data class SearchFilters(
    val text: String = "",
    val category: SearchCategory = SearchCategory.All,
    val favoritesOnly: Boolean = false,
    val tagId: String? = null,
    val folderId: String? = null,
    val boardId: String? = null,
    val dateRange: DateRange = DateRange.Any,
    val sort: SearchSort = SearchSort.Newest,
) {
    /** Whether anything narrows the search; with no criteria the screen shows recents instead of the whole library. */
    val hasCriteria: Boolean
        get() = text.isNotBlank() || category != SearchCategory.All || favoritesOnly ||
            tagId != null || folderId != null || boardId != null || dateRange != DateRange.Any
}

data class BoardRef(val boardId: String, val boardName: String)
