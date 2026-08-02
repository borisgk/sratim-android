package com.ru9n.sratim.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.ru9n.sratim.core.viewmodel.ShowDetailViewModel
import com.ru9n.sratim.core.viewmodel.EpisodeUiModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    viewModel: ShowDetailViewModel,
    onEpisodeClick: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uiState.show) {
        if (selectedSeason == null && uiState.show != null && uiState.show!!.seasons.isNotEmpty()) {
            selectedSeason = uiState.show!!.seasons.keys.minOrNull()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (uiState.show != null) {
            val show = uiState.show!!

            // Backdrop Background
            AsyncImage(
                model = show.backdropUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.5f
            )

            // Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            startY = 0f
                        )
                    )
            )

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header Row - with explicit horizontal padding
                Row(
                    modifier = Modifier
                        .padding(start = 56.dp, end = 56.dp, top = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = show.posterUrl,
                        contentDescription = show.title,
                        modifier = Modifier
                            .height(150.dp)
                            .aspectRatio(2f / 3f)
                            .background(Color.DarkGray),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(24.dp))
                    Column {
                        Text(
                            text = show.title,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = show.overview,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Season Selector - move horizontal padding to contentPadding
                val seasons = show.seasons.keys.toList().sorted()
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    items(seasons) { season ->
                        FilterChip(
                            selected = selectedSeason == season,
                            onClick = { selectedSeason = season }
                        ) {
                            Text("Season $season")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Episode List - move horizontal padding to contentPadding
                val episodes = selectedSeason?.let { show.seasons[it] } ?: emptyList()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 16.dp, bottom = 48.dp)
                ) {
                    items(episodes) { episode ->
                        EpisodeRow(episode = episode, onClick = { onEpisodeClick(episode.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodeRow(episode: EpisodeUiModel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(16f / 9f)
                    .background(Color.DarkGray),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Episode ${episode.episode}: ${episode.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}
