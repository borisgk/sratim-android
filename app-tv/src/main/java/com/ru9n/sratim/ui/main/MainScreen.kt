package com.ru9n.sratim.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.ru9n.sratim.core.viewmodel.MainViewModel
import com.ru9n.sratim.core.viewmodel.RecentlyWatchedUiModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onLibraryClick: (Int) -> Unit,
    onItemClick: (id: Int, type: String, showId: Int?) -> Unit = { _, _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.isLoading && uiState.libraries.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (uiState.error != null && uiState.libraries.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row {
                    Button(onClick = viewModel::fetchLibraries) {
                        Text("Retry")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(onClick = viewModel::logout) {
                        Text("Reset Settings")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(vertical = 48.dp),
            ) {
                if (uiState.recentlyWatched.isNotEmpty()) {
                    Text(
                        text = "Recently Watched",
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.padding(start = 48.dp, bottom = 20.dp),
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(uiState.recentlyWatched) { item ->
                            RecentlyWatchedCard(
                                item = item,
                                onClick = { onItemClick(item.id, item.type, item.showId) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))
                }

                Text(
                    text = "Libraries",
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(start = 48.dp, bottom = 20.dp),
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(uiState.libraries) { library ->
                        Card(
                            onClick = { onLibraryClick(library.id) },
                            modifier = Modifier.size(width = 200.dp, height = 150.dp),
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = library.name, style = MaterialTheme.typography.headlineSmall)
                                    Text(text = library.type, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecentlyWatchedCard(
    item: RecentlyWatchedUiModel,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(180.dp),
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                // Progress Bar at the bottom of the poster
                if (item.progressPercent > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
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
        }
        Spacer(modifier = Modifier.height(8.dp))
        val primaryTitle = item.showTitle ?: item.title
        Text(
            text = primaryTitle,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.type == "episode") {
            val subtitle = if (item.season != null && item.episode != null) {
                "S${item.season}:E${item.episode} - ${item.title}"
            } else {
                item.title
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
