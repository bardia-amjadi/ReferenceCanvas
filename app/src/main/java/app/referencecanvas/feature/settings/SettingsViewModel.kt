package app.referencecanvas.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.referencecanvas.data.repository.SettingsRepository
import app.referencecanvas.domain.model.AccentOption
import app.referencecanvas.domain.model.AppSettings
import app.referencecanvas.domain.model.BoardViewMode
import app.referencecanvas.domain.model.GalleryDensity
import app.referencecanvas.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setAccent(v: AccentOption) { viewModelScope.launch { repository.setAccent(v) } }
    fun setThemeMode(v: ThemeMode) { viewModelScope.launch { repository.setThemeMode(v) } }
    fun setDefaultViewMode(v: BoardViewMode) { viewModelScope.launch { repository.setDefaultViewMode(v) } }
    fun setGalleryDensity(v: GalleryDensity) { viewModelScope.launch { repository.setGalleryDensity(v) } }
    fun setSnapToGrid(v: Boolean) { viewModelScope.launch { repository.setSnapToGrid(v) } }
}
