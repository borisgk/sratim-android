package com.example.sratim

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
import com.example.sratim.data.ConfigManager
import com.example.sratim.ui.details.MovieDetailScreen
import com.example.sratim.ui.details.MovieDetailViewModel
import com.example.sratim.ui.library.LibraryScreen
import com.example.sratim.ui.library.LibraryViewModel
import com.example.sratim.ui.main.MainScreen
import com.example.sratim.ui.main.MainViewModel
import com.example.sratim.ui.playback.PlaybackScreen
import com.example.sratim.ui.playback.PlaybackViewModel
import com.example.sratim.ui.setup.SetupScreen
import com.example.sratim.ui.setup.SetupViewModel
import com.example.sratim.ui.theme.SratimTheme
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
                    onMovieClick = { movieId ->
                        navController.navigate("movie/$movieId")
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
                        navController.navigate("playback/$id")
                    }
                )
            }
            composable(
                route = "playback/{movieId}",
                arguments = listOf(navArgument("movieId") { type = NavType.IntType })
            ) { backStackEntry ->
                val movieId = backStackEntry.arguments?.getInt("movieId") ?: 0
                val viewModel = remember(movieId) {
                    PlaybackViewModel(context.applicationContext as Application, configManager, movieId)
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