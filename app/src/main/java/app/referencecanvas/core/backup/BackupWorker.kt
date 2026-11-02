package app.referencecanvas.core.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerEntryPoint {
    fun engine(): BackupEngine
}

/**
 * Scheduled backup. Android decides when it actually runs (battery, storage, Doze), so "daily" means "about daily".
 * It reports through the same status record as a manual backup; failures never show a notification (the app asks
 * for no notification permission), they appear under Settings > Backup.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = EntryPointAccessors.fromApplication(applicationContext, BackupWorkerEntryPoint::class.java).engine()
        return when (val result = engine.backupNow()) {
            is BackupResult.Success -> Result.success()
            is BackupResult.Failure -> if (result.retryable && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object { const val MAX_RETRIES = 2 }
}

@Singleton
class BackupScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun apply(schedule: BackupSchedule) {
        val work = WorkManager.getInstance(context)
        if (schedule == BackupSchedule.Off) {
            work.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val days = if (schedule == BackupSchedule.Daily) 1L else 7L
        val request = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object { const val UNIQUE_NAME = "reference-canvas-backup" }
}
