package com.ru9n.sratim.mobile

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.viewmodel.*
import com.ru9n.sratim.mobile.ui.details.MovieDetailScreen
import com.ru9n.sratim.mobile.ui.details.ShowDetailScreen
import com.ru9n.sratim.mobile.ui.library.LibraryScreen
import com.ru9n.sratim.mobile.ui.main.MainScreen
import com.ru9n.sratim.mobile.ui.playback.PlaybackScreen
import com.ru9n.sratim.mobile.ui.setup.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configManager = ConfigManager(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(configManager)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(configManager: ConfigManager) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val config by configManager.configFlow.collectAsState(initial = null)

    LaunchedEffect(config) {
        val currentConfig = config ?: return@LaunchedEffect
        val currentRoute = navController.currentDestination?.route
        
        if (!currentConfig.isValid && currentRoute != "setup") {
            navController.navigate("setup") {
                popUpTo(0) { inclusive = true }
            }
        } else if (currentConfig.isValid && (currentRoute == "setup" || currentRoute == null)) {
            navController.navigate("main") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "setup") {
            composable("setup") {
                val viewModel = remember { 
                    SetupViewModel(context.applicationContext as Application, configManager) 
                }
                SetupScreen(
                    viewModel = viewModel,
                    onComplete = {
                        navController.navigate("main") {
                            popUpTo("setup") { inclusive = true }
                        }
                    }
                )
            }
            composable("main") {
                val viewModel = remember {
                    MainViewModel(context.applicationContext as Application, configManager)
                }
                MainScreen(
                    viewModel = viewModel,
                    onLibraryClick = { libraryId ->
                        navController.navigate("library/$libraryId")
                    }
                )
            }
            composable(
                route = "library/{libraryId}",
                arguments = listOf(navArgument("libraryId") { type = NavType.IntType })
            ) { backStackEntry ->
                val libraryId = backStackEntry.arguments?.getInt("libraryId") ?: 0
                val viewModel = remember(libraryId) {
                    LibraryViewModel(context.applicationContext as Application, configManager, libraryId)
                }
                LibraryScreen(
                    viewModel = viewModel,
                    onItemClick = { id, type ->
                        if (type == "movie") {
                            navController.navigate("movie/$id")
                        } else {
                            navController.navigate("show/$id")
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "show/{showId}",
                arguments = listOf(navArgument("showId") { type = NavType.IntType })
            ) { backStackEntry ->
                val showId = backStackEntry.arguments?.getInt("showId") ?: 0
                val viewModel = remember(showId) {
                    ShowDetailViewModel(context.applicationContext as Application, configManager, showId)
                }
                ShowDetailScreen(
                    viewModel = viewModel,
                    onEpisodeClick = { episodeId ->
                        navController.navigate("playback/episode/$episodeId")
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "movie/{movieId}",
                arguments = listOf(navArgument("movieId") { type = NavType.IntType })
            ) { backStackEntry ->
                val movieId = backStackEntry.arguments?.getInt("movieId") ?: 0
                val viewModel = remember(movieId) {
                    MovieDetailViewModel(context.applicationContext as Application, configManager, movieId)
                }
                MovieDetailScreen(
                    viewModel = viewModel,
                    onPlayClick = { id ->
                        navController.navigate("playback/movie/$id")
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "playback/movie/{movieId}",
                arguments = listOf(navArgument("movieId") { type = NavType.IntType })
            ) { backStackEntry ->
                val movieId = backStackEntry.arguments?.getInt("movieId") ?: 0
                val viewModel = remember(movieId) {
                    PlaybackViewModel(application = context.applicationContext as Application, configManager = configManager, movieId = movieId)
                }
                PlaybackScreen(viewModel = viewModel)
            }
            composable(
                route = "playback/episode/{episodeId}",
                arguments = listOf(navArgument("episodeId") { type = NavType.IntType })
            ) { backStackEntry ->
                val episodeId = backStackEntry.arguments?.getInt("episodeId") ?: 0
                val viewModel = remember(episodeId) {
                    PlaybackViewModel(application = context.applicationContext as Application, configManager = configManager, episodeId = episodeId)
                }
                PlaybackScreen(viewModel = viewModel)
            }
        }
    }
}
