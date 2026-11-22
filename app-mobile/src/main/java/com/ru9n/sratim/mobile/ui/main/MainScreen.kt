package com.ru9n.sratim.mobile.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ru9n.sratim.core.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onLibraryClick: (Int) -> Unit,
    onItemClick: (id: Int, type: String, showId: Int?) -> Unit = { _, _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Libraries") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    scrolledContainerColor = Color.Black,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                actions = {
                    IconButton(onClick = viewModel::fetchLibraries) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = viewModel::logout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding)) {
            if (uiState.isLoading && uiState.libraries.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null && uiState.libraries.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = uiState.error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = viewModel::fetchLibraries) { Text("Retry") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (uiState.recentlyWatched.isNotEmpty()) {
                        item {
                            Text(
                                text = "Recently Watched",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(uiState.recentlyWatched) { item ->
                                    ElevatedCard(
                                        onClick = { onItemClick(item.id, item.type, item.showId) },
                                        modifier = Modifier.width(110.dp),
                                    ) {
                                        Column {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(2f / 3f),
                                            ) {
                                                if (item.posterUrl.isNotEmpty()) {
                                                    AsyncImage(
                                                        model = item.posterUrl,
                                                        contentDescription = item.title,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.DarkGray),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(item.title, style = MaterialTheme.typography.labelMedium)
                                                    }
                                                }

                                                if (item.progressPercent > 0f) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(3.dp)
                                                            .align(Alignment.BottomCenter)
                                                            .background(Color.Black.copy(alpha = 0.6f)),
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth(fraction = (item.progressPercent / 100f).coerceIn(0f, 1f))
                                                                .fillMaxHeight()
                                                                .background(MaterialTheme.colorScheme.primary),
                                                        )
                                                    }
                                                }
                                            }

                                            Column(modifier = Modifier.padding(6.dp)) {
                                                Text(
                                                    text = item.showTitle ?: item.title,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (item.type == "episode") {
                                                    val subtitle = if (item.season != null && item.episode != null) {
                                                        "S${item.season}:E${item.episode}"
                                                    } else {
                                                        item.title
                                                    }
                                                    Text(
                                                        text = subtitle,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Libraries",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                    }

                    items(uiState.libraries) { library ->
                        ElevatedCard(
                            onClick = { onLibraryClick(library.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = library.name, style = MaterialTheme.typography.titleLarge)
                                Text(text = library.type, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
