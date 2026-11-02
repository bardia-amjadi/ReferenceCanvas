package app.referencecanvas.core.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.referencecanvas.core.util.toEnumOr
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup")

enum class BackupSchedule { Off, Daily, Weekly }

enum class BackupOutcome { Success, NoDestination, DestinationUnavailable, NoSpace, Io, VerificationFailed, Cancelled, Database, Unknown }

/** Backup destination and the honest record of the last attempt. Device-specific, so it is never part of a backup. */
data class BackupSettings(
    val destinationUri: String? = null,
    val destinationName: String? = null,
    val schedule: BackupSchedule = BackupSchedule.Off,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastOutcome: BackupOutcome? = null,
    val lastBytes: Long = 0,
    val lastFiles: Int = 0,
)

@Singleton
class BackupSettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.backupDataStore

    val settings: Flow<BackupSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            BackupSettings(
                destinationUri = p[DEST_URI],
                destinationName = p[DEST_NAME],
                schedule = p[SCHEDULE].toEnumOr(BackupSchedule.Off),
                lastAttemptAt = p[LAST_ATTEMPT],
                lastSuccessAt = p[LAST_SUCCESS],
                lastOutcome = p[LAST_OUTCOME]?.let { name -> BackupOutcome.entries.firstOrNull { it.name == name } },
                lastBytes = p[LAST_BYTES] ?: 0,
                lastFiles = p[LAST_FILES] ?: 0,
            )
        }

    suspend fun setDestination(uri: String, name: String) {
        store.edit { it[DEST_URI] = uri; it[DEST_NAME] = name }
    }

    suspend fun clearDestination() {
        store.edit { it.remove(DEST_URI); it.remove(DEST_NAME); it[SCHEDULE] = BackupSchedule.Off.name }
    }

    suspend fun setSchedule(schedule: BackupSchedule) { store.edit { it[SCHEDULE] = schedule.name } }

    suspend fun recordAttempt(outcome: BackupOutcome, at: Long, bytes: Long = 0, files: Int = 0) {
        store.edit {
            it[LAST_ATTEMPT] = at
            it[LAST_OUTCOME] = outcome.name
            if (outcome == BackupOutcome.Success) {
                it[LAST_SUCCESS] = at
                it[LAST_BYTES] = bytes
                it[LAST_FILES] = files
            }
        }
    }

    private companion object {
        val DEST_URI = stringPreferencesKey("destination_uri")
        val DEST_NAME = stringPreferencesKey("destination_name")
        val SCHEDULE = stringPreferencesKey("schedule")
        val LAST_ATTEMPT = longPreferencesKey("last_attempt_at")
        val LAST_SUCCESS = longPreferencesKey("last_success_at")
        val LAST_OUTCOME = stringPreferencesKey("last_outcome")
        val LAST_BYTES = longPreferencesKey("last_bytes")
        val LAST_FILES = intPreferencesKey("last_files")
    }
}
