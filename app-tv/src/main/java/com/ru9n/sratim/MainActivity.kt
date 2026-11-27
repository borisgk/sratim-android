package com.ru9n.sratim

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.ru9n.sratim.channel.TvHomeChannelPublisher
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
    private var deepLinkUri by mutableStateOf<Uri?>(null)

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configManager = ConfigManager(applicationContext)
        deepLinkUri = intent?.data

        setContent {
            SratimTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = Color.Black
                    )
                ) {
                    AppNavigation(
                        configManager = configManager,
                        pendingDeepLink = deepLinkUri,
                        onDeepLinkConsumed = { deepLinkUri = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkUri = intent.data
    }
}

private fun parseDeepLinkRoute(uri: Uri?): String? {
    if (uri == null || uri.scheme != "sratim") return null
    val host = uri.host ?: return null
    val segments = uri.pathSegments.orEmpty()
    if (host == "playback") {
        if (segments.size == 2 && segments[0] == "movie") {
            val movieId = segments[1].toIntOrNull() ?: return null
            return "playback/movie/$movieId"
        }
        if (segments.size == 4 && segments[0] == "show" && segments[2] == "episode") {
            val showId = segments[1].toIntOrNull() ?: return null
            val episodeId = segments[3].toIntOrNull() ?: return null
            return "playback/show/$showId/episode/$episodeId"
        }
    }
    return null
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppNavigation(
    configManager: ConfigManager,
    pendingDeepLink: Uri? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val config by configManager.configFlow.collectAsStateWithLifecycle(initialValue = null)
    val resolvedConfig = config

    // Wait until DataStore emits the initial configuration so SetupScreen never flashes on launch
    if (resolvedConfig == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    val navController = rememberNavController()
    val context = LocalContext.current
    val initialStartDestination = remember {
        if (resolvedConfig.isValid) "main" else "setup"
    }

    LaunchedEffect(resolvedConfig.isValid, pendingDeepLink) {
        val currentRoute = navController.currentDestination?.route

        if (!resolvedConfig.isValid && currentRoute != "setup") {
            navController.navigate("setup") {
                popUpTo(0) { inclusive = true }
            }
        } else if (resolvedConfig.isValid) {
            val deepLinkRoute = parseDeepLinkRoute(pendingDeepLink)
            if (deepLinkRoute != null) {
                onDeepLinkConsumed()
                if (currentRoute == "setup") {
                    navController.navigate("main") {
                        popUpTo(0) { inclusive = true }
                    }
                }
                navController.navigate(deepLinkRoute)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = initialStartDestination,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { fadeIn(animationSpec = tween(150)) },
        exitTransition = { fadeOut(animationSpec = tween(150)) },
        popEnterTransition = { fadeIn(animationSpec = tween(150)) },
        popExitTransition = { fadeOut(animationSpec = tween(150)) }
    ) {
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
        composable("settings") {
            val vm: SetupViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SetupViewModel(context.applicationContext as Application, configManager) as T
                }
            })
            SetupScreen(
                viewModel = vm,
                isSettings = true,
                onCancel = { navController.popBackStack() },
                onComplete = {
                    navController.navigate("main") {
                        popUpTo(0) { inclusive = true }
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
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        vm.refreshRecentlyWatchedIfNeeded()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val uiState by vm.uiState.collectAsState()
            LaunchedEffect(uiState.recentlyWatched) {
                if (uiState.recentlyWatched.isNotEmpty()) {
                    TvHomeChannelPublisher.publishRecentlyWatched(
                        context.applicationContext,
                        uiState.recentlyWatched
                    )
                }
            }
            MainScreen(
                viewModel = vm,
                onLibraryClick = { libraryId ->
                    navController.navigate("library/$libraryId")
                },
                onItemClick = { id, type, showId ->
                    if (type == "movie") {
                        navController.navigate("playback/movie/$id")
                    } else if (showId != null) {
                        navController.navigate("playback/show/$showId/episode/$id")
                    }
                },
                onSettingsClick = {
                    navController.navigate("settings")
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
