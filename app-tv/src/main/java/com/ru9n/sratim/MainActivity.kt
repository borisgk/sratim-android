package com.ru9n.sratim

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.viewmodel.*
import com.ru9n.sratim.ui.details.MovieDetailScreen
import com.ru9n.sratim.ui.details.ShowDetailScreen
import com.ru9n.sratim.ui.library.LibraryScreen
import com.ru9n.sratim.ui.main.MainScreen
import com.ru9n.sratim.ui.playback.PlaybackScreen
import com.ru9n.sratim.ui.setup.SetupScreen
import com.ru9n.sratim.ui.theme.SratimTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configManager = ConfigManager(applicationContext)

        setContent {
            SratimTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = Color.Black
                    )
                ) {
                    AppNavigation(configManager)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NavHost(navController = navController, startDestination = "setup") {
            composable("setup") {
                val vm: SetupViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SetupViewModel(context.applicationContext as Application, configManager) as T
                    }
                })
                SetupScreen(
                    viewModel = vm,
                    onComplete = {
                        navController.navigate("main") {
                            popUpTo("setup") { inclusive = true }
                        }
                    }
                )
            }
            composable("main") {
                val vm: MainViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return MainViewModel(context.applicationContext as Application, configManager) as T
                    }
                })
                MainScreen(
                    viewModel = vm,
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
                val vm: LibraryViewModel = viewModel(key = "library_$libraryId", factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return LibraryViewModel(context.applicationContext as Application, configManager, libraryId) as T
                    }
                })
                LibraryScreen(
                    viewModel = vm,
                    onItemClick = { id, type ->
                        if (type == "movie") {
                            navController.navigate("movie/$id")
                        } else {
                            navController.navigate("show/$id")
                        }
                    }
                )
            }
            composable(
                route = "show/{showId}",
                arguments = listOf(navArgument("showId") { type = NavType.IntType })
            ) { backStackEntry ->
                val showId = backStackEntry.arguments?.getInt("showId") ?: 0
                val vm: ShowDetailViewModel = viewModel(key = "show_$showId", factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return ShowDetailViewModel(context.applicationContext as Application, configManager, showId) as T
                    }
                })
                ShowDetailScreen(
                    viewModel = vm,
                    onEpisodeClick = { episodeId ->
                        navController.navigate("playback/show/$showId/episode/$episodeId")
                    }
                )
            }
            composable(
                route = "movie/{movieId}",
                arguments = listOf(navArgument("movieId") { type = NavType.IntType })
            ) { backStackEntry ->
                val movieId = backStackEntry.arguments?.getInt("movieId") ?: 0
                val vm: MovieDetailViewModel = viewModel(key = "movie_$movieId", factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return MovieDetailViewModel(context.applicationContext as Application, configManager, movieId) as T
                    }
                })
                MovieDetailScreen(
                    viewModel = vm,
                    onPlayClick = { id ->
                        navController.navigate("playback/movie/$id")
                    }
                )
            }
            composable(
                route = "playback/movie/{movieId}",
                arguments = listOf(navArgument("movieId") { type = NavType.IntType })
            ) { backStackEntry ->
                val movieId = backStackEntry.arguments?.getInt("movieId") ?: 0
                val vm: PlaybackViewModel = viewModel(key = "playback_movie_$movieId", factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return PlaybackViewModel(application = context.applicationContext as Application, configManager = configManager, movieId = movieId) as T
                    }
                })
                PlaybackScreen(viewModel = vm)
            }
            composable(
                route = "playback/show/{showId}/episode/{episodeId}",
                arguments = listOf(
                    navArgument("showId") { type = NavType.IntType },
                    navArgument("episodeId") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                val showId = backStackEntry.arguments?.getInt("showId") ?: 0
                val episodeId = backStackEntry.arguments?.getInt("episodeId") ?: 0
                val vm: PlaybackViewModel = viewModel(key = "playback_episode_$episodeId", factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return PlaybackViewModel(
                            application = context.applicationContext as Application, 
                            configManager = configManager, 
                            showId = showId,
                            episodeId = episodeId
                        ) as T
                    }
                })
                PlaybackScreen(viewModel = vm)
            }
        }
    }
}
