package app.referencecanvas.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import app.referencecanvas.R
import java.io.File

/** Hands managed files to other apps through a read-only FileProvider grant. Nothing is executed by this app. */
object FileActions {
    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun open(context: Context, file: File, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, intent)
    }

    fun share(context: Context, file: File, mimeType: String, title: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, Intent.createChooser(send, title))
    }

    /** Opens a web link in the user's browser. Only ever called from an explicit tap. */
    fun openUrl(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        launch(context, Intent(Intent.ACTION_VIEW, uri))
    }

    fun shareText(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(context, Intent.createChooser(send, title))
    }

    private fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_found, Toast.LENGTH_SHORT).show()
        }
    }
}
