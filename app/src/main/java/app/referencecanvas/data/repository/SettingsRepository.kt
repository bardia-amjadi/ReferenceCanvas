package app.referencecanvas.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.referencecanvas.core.util.toEnumOr
import app.referencecanvas.domain.model.AccentOption
import app.referencecanvas.domain.model.AppSettings
import app.referencecanvas.domain.model.BoardViewMode
import app.referencecanvas.domain.model.GalleryDensity
import app.referencecanvas.domain.model.ThemeMode
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setAccent(value: AccentOption)
    suspend fun setThemeMode(value: ThemeMode)
    suspend fun setDefaultViewMode(value: BoardViewMode)
    suspend fun setGalleryDensity(value: GalleryDensity)
    suspend fun setSnapToGrid(value: Boolean)
    suspend fun setShowCanvasGrid(value: Boolean)
}

class SettingsRepositoryImpl @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            AppSettings(
                accent = p[ACCENT].toEnumOr(AccentOption.Violet),
                themeMode = p[THEME].toEnumOr(ThemeMode.Dark),
                defaultViewMode = p[DEFAULT_VIEW].toEnumOr(BoardViewMode.Canvas),
                galleryDensity = p[DENSITY].toEnumOr(GalleryDensity.Comfortable),
                snapToGrid = p[SNAP] ?: false,
                showCanvasGrid = p[SHOW_GRID] ?: true,
            )
        }

    override suspend fun setAccent(value: AccentOption) { store.edit { it[ACCENT] = value.name } }
    override suspend fun setThemeMode(value: ThemeMode) { store.edit { it[THEME] = value.name } }
    override suspend fun setDefaultViewMode(value: BoardViewMode) { store.edit { it[DEFAULT_VIEW] = value.name } }
    override suspend fun setGalleryDensity(value: GalleryDensity) { store.edit { it[DENSITY] = value.name } }
    override suspend fun setSnapToGrid(value: Boolean) { store.edit { it[SNAP] = value } }
    override suspend fun setShowCanvasGrid(value: Boolean) { store.edit { it[SHOW_GRID] = value } }

    private companion object {
        val ACCENT = stringPreferencesKey("accent")
        val THEME = stringPreferencesKey("theme_mode")
        val DEFAULT_VIEW = stringPreferencesKey("default_view_mode")
        val DENSITY = stringPreferencesKey("gallery_density")
        val SNAP = booleanPreferencesKey("snap_to_grid")
        val SHOW_GRID = booleanPreferencesKey("show_canvas_grid")
    }
}
