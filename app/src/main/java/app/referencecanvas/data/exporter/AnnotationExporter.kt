package app.referencecanvas.data.exporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import app.referencecanvas.domain.annotation.AnnotationData
import app.referencecanvas.domain.annotation.Primitive
import app.referencecanvas.domain.annotation.ShapeRender
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Produces a flattened PNG of an image plus its annotations. The original file is only read; the result is a
 * separate temporary file (in the shareable cache area) that the caller shares, saves or imports.
 */
class AnnotationExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Returns the PNG, or null if the source can't be decoded or there isn't enough memory/storage. */
    suspend fun render(source: File, annotations: List<AnnotationData>): File? = withContext(Dispatchers.IO) {
        try {
            val base = decodeUpright(source) ?: return@withContext null
            val canvasBitmap = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true).also { base.recycle() }
            val canvas = Canvas(canvasBitmap)
            val w = canvasBitmap.width.toFloat()
            val h = canvasBitmap.height.toFloat()
            annotations.sortedBy { it.seq }.forEach { data ->
                ShapeRender.toPrimitives(data, w, h).forEach { canvas.draw(it) }
            }
            val dir = File(context.cacheDir, "capture").apply { mkdirs() }
            val out = File(dir, "annotated-${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            canvasBitmap.recycle()
            out
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Decodes with a size cap and applies the EXIF rotation, matching how the viewer displays the image. */
    private fun decodeUpright(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
            ?: return null
        val matrix = Matrix()
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return decoded
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun Canvas.draw(p: Primitive) {
        when (p) {
            is Primitive.Poly -> {
                val path = Path()
                p.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                if (p.closed) path.close()
                drawPath(path, strokePaint(p.color, p.widthPx))
            }
            is Primitive.Oval -> drawOval(RectF(p.left, p.top, p.right, p.bottom), strokePaint(p.color, p.widthPx))
            is Primitive.TextRun -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color; textSize = p.sizePx }
                var y = p.y - paint.fontMetrics.ascent
                p.text.split('\n').forEach { line ->
                    drawText(line, p.x, y, paint)
                    y += p.sizePx * 1.25f
                }
            }
        }
    }

    private fun strokePaint(argb: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = width
        color = argb
    }

    private companion object {
        /** Keeps the working bitmap under ~36 MB; exported copies are capped to this long side. */
        const val MAX_SIDE_PX = 3072
    }
}
