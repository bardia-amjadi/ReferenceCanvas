package app.referencecanvas.domain.model

enum class AccentOption { Violet, Cyan, Blue, Green, Orange, Red }

enum class ThemeMode { Dark, Light, System }

enum class GalleryDensity { Compact, Comfortable, Large }

data class AppSettings(
    val accent: AccentOption = AccentOption.Violet,
    val themeMode: ThemeMode = ThemeMode.Dark,
    val defaultViewMode: BoardViewMode = BoardViewMode.Canvas,
    val galleryDensity: GalleryDensity = GalleryDensity.Comfortable,
    val snapToGrid: Boolean = false,
    val showCanvasGrid: Boolean = true,
)
