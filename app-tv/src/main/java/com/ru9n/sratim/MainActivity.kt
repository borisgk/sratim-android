package com.ru9n.sratim

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.CircularProgressIndicator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.viewmodel.MovieDetailViewModel
import com.ru9n.sratim.core.viewmodel.ShowDetailViewModel
import com.ru9n.sratim.core.viewmodel.LibraryViewModel
import com.ru9n.sratim.core.viewmodel.MainViewModel
import com.ru9n.sratim.core.viewmodel.PlaybackViewModel
import com.ru9n.sratim.core.viewmodel.SetupViewModel
import com.ru9n.sratim.ui.details.MovieDetailScreen
import com.ru9n.sratim.ui.details.ShowDetailScreen
import com.ru9n.sratim.ui.library.LibraryScreen
import com.ru9n.sratim.ui.main.MainScreen
import com.ru9n.sratim.ui.playback.PlaybackScreen
import com.ru9n.sratim.ui.setup.SetupScreen
import com.ru9n.sratim.ui.theme.SratimTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configManager = ConfigManager(applicationContext)

        setContent {
            SratimTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
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
                    }
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
                    }
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
                    }
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

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SratimTheme {
        Greeting("Android")
    }
}