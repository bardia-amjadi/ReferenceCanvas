package app.referencecanvas.core.util

/** Lowercase alphanumeric extension (max 10 chars) safe for use in a storage file name. */
fun sanitizeExtension(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "")
        .lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' }
        .take(10)
    return ext.ifEmpty { "bin" }
}

/** Human title from a file name: drops one extension, never returns blank. */
fun titleFromFileName(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    return base.trim().ifEmpty { "Untitled" }
}
