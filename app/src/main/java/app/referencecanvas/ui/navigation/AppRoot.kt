package app.referencecanvas.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.referencecanvas.R
import app.referencecanvas.core.design.RcTheme
import app.referencecanvas.feature.annotate.AnnotationEditorScreen
import app.referencecanvas.feature.boards.BoardDetailScreen
import app.referencecanvas.feature.boards.BoardsScreen
import app.referencecanvas.feature.favorites.FavoritesScreen
import app.referencecanvas.feature.home.HomeScreen
import app.referencecanvas.feature.search.SearchScreen
import app.referencecanvas.feature.settings.SettingsScreen
import app.referencecanvas.feature.share.ShareHost
import app.referencecanvas.feature.trash.TrashScreen
import app.referencecanvas.feature.viewer.ViewerScreen

enum class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Home("home", R.string.nav_home, Icons.Filled.Home),
    Boards("boards", R.string.nav_boards, Icons.Filled.Dashboard),
    Search("search", R.string.nav_search, Icons.Filled.Search),
    Favorites("favorites", R.string.nav_favorites, Icons.Filled.Favorite),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

private const val BOARD_ROUTE = "board/{boardId}"
private fun boardRoute(id: String) = "board/$id"
private const val TRASH_ROUTE = "trash"
private const val ANNOTATE_ROUTE = "annotate/{boardId}/{referenceId}"
private fun annotateRoute(boardId: String, referenceId: String) = "annotate/$boardId/$referenceId"
private const val VIEWER_ROUTE = "viewer/{boardId}/{referenceId}"
private fun viewerRoute(boardId: String, referenceId: String) = "viewer/$boardId/$referenceId"

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBar = TopLevelDestination.entries.any { top ->
        destination?.hierarchy?.any { it.route == top.route } == true
    }
    val colors = RcTheme.colors

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = colors.surface) {
                    TopLevelDestination.entries.forEach { top ->
                        val selected = destination?.hierarchy?.any { it.route == top.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(top.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(top.icon, contentDescription = null) },
                            label = { Text(stringResource(top.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.accent,
                                selectedTextColor = colors.accent,
                                indicatorColor = colors.accent.copy(alpha = 0.16f),
                                unselectedIconColor = colors.textSecondary,
                                unselectedTextColor = colors.textSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val openBoard: (String) -> Unit = { nav.navigate(boardRoute(it)) }
        NavHost(
            navController = nav,
            startDestination = TopLevelDestination.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevelDestination.Home.route) { HomeScreen(openBoard) }
            composable(TopLevelDestination.Boards.route) { BoardsScreen(openBoard) }
            composable(TopLevelDestination.Search.route) {
                SearchScreen(
                    onOpenBoard = openBoard,
                    onOpenReference = { boardId, referenceId -> nav.navigate(viewerRoute(boardId, referenceId)) },
                )
            }
            composable(TopLevelDestination.Favorites.route) { FavoritesScreen(openBoard) }
            composable(TopLevelDestination.Settings.route) { SettingsScreen(onOpenTrash = { nav.navigate(TRASH_ROUTE) }) }
            composable(TRASH_ROUTE) { TrashScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = BOARD_ROUTE,
                arguments = listOf(navArgument("boardId") { type = NavType.StringType }),
            ) {
                BoardDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenReference = { boardId, refId -> nav.navigate(viewerRoute(boardId, refId)) },
                )
            }
            composable(
                route = VIEWER_ROUTE,
                arguments = listOf(
                    navArgument("boardId") { type = NavType.StringType },
                    navArgument("referenceId") { type = NavType.StringType },
                ),
            ) {
                ViewerScreen(
                    onBack = { nav.popBackStack() },
                    onAnnotate = { boardId, referenceId -> nav.navigate(annotateRoute(boardId, referenceId)) },
                )
            }
            composable(
                route = ANNOTATE_ROUTE,
                arguments = listOf(
                    navArgument("boardId") { type = NavType.StringType },
                    navArgument("referenceId") { type = NavType.StringType },
                ),
            ) { AnnotationEditorScreen(onBack = { nav.popBackStack() }) }
        }
    }

    ShareHost(onOpenBoard = { nav.navigate(boardRoute(it)) })
}
