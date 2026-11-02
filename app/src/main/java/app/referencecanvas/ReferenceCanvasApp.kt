package app.referencecanvas

import android.app.Application
import app.referencecanvas.core.filesystem.FileStore
import app.referencecanvas.core.util.DiagnosticsLog
import app.referencecanvas.data.repository.ReferenceRepository
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class ReferenceCanvasApp : Application() {
    @Inject lateinit var fileStore: FileStore
    @Inject lateinit var diagnostics: DiagnosticsLog
    @Inject lateinit var references: ReferenceRepository

    override fun onCreate() {
        super.onCreate()
        diagnostics.installCrashHandler()
        // Nothing can be importing at process start, so any staged leftovers are from an interrupted import.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            fileStore.clearStaging()
            File(cacheDir, "capture").deleteRecursively()
            File(cacheDir, "backup-tmp").deleteRecursively()
            val purged = references.purgeExpired(TRASH_RETENTION_DAYS)
            if (purged > 0) diagnostics.record("trash", "purged expired items=$purged")
        }
    }

    private companion object {
        const val TRASH_RETENTION_DAYS = 30
    }
}
