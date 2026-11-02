package app.referencecanvas.domain.annotation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A point in normalized image space: (0,0) is the image's top-left, (1,1) its bottom-right. */
@Serializable
data class Pt(val x: Float, val y: Float)

enum class AnnotationKind { Pen, Highlighter, Line, Arrow, Rect, Ellipse, Text }

/**
 * One editable annotation. Everything is stored relative to the *displayed* image, never in screen pixels,
 * so it stays aligned across zoom levels, screen sizes and rotation. The original file is never modified.
 */
@Serializable
data class AnnotationData(
    val kind: AnnotationKind,
    /** ARGB. */
    val color: Int,
    /** Stroke width as a fraction of the image width. */
    val width: Float,
    /** Pen/highlighter: the path. Line/arrow/rect/ellipse: exactly two corner points. Text: one anchor (top-left). */
    val points: List<Pt>,
    val text: String? = null,
    /** Text height as a fraction of the image height. */
    val textSize: Float = 0f,
    /** Paint order; later is on top. */
    val seq: Long = 0,
    val version: Int = 1,
) {
    fun isValid(): Boolean {
        if (!width.isFinite() || width <= 0f || width > 1f) return false
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        return when (kind) {
            AnnotationKind.Pen, AnnotationKind.Highlighter -> points.isNotEmpty()
            AnnotationKind.Line, AnnotationKind.Arrow, AnnotationKind.Rect, AnnotationKind.Ellipse -> points.size == 2
            AnnotationKind.Text -> points.size == 1 && !text.isNullOrBlank() && textSize.isFinite() && textSize > 0f
        }
    }
}

data class Annotation(val id: String, val referenceId: String, val data: AnnotationData)

/** The stored form: one JSON document per annotation (a row in `annotations`). */
object AnnotationCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(data: AnnotationData): String = json.encodeToString(AnnotationData.serializer(), data)

    /** Null for malformed, unsupported or invalid data, so one bad row can't take down the whole image. */
    fun decode(raw: String): AnnotationData? = try {
        json.decodeFromString(AnnotationData.serializer(), raw).takeIf { it.isValid() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Drawing palette and stroke-size mapping shared by the editor. */
object AnnotationStyle {
    val palette: List<Int> = listOf(
        0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFEF4444.toInt(), 0xFFF59E0B.toInt(),
        0xFFFACC15.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(),
    )
    const val MIN_WIDTH = 0.002f
    const val MAX_WIDTH = 0.03f

    /** Maps a 0..1 slider position to a stroke width fraction. */
    fun widthFor(slider: Float): Float = MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * slider.coerceIn(0f, 1f)

    fun textSizeFor(slider: Float): Float = 0.025f + 0.065f * slider.coerceIn(0f, 1f)
}
