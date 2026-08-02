package com.ru9n.sratim.mobile.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ru9n.sratim.core.viewmodel.ShowDetailViewModel
import com.ru9n.sratim.core.viewmodel.EpisodeUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    viewModel: ShowDetailViewModel,
    onEpisodeClick: (Int) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    // Auto-select first season when data loads
    LaunchedEffect(uiState.show) {
        if (selectedSeason == null && uiState.show != null && uiState.show!!.seasons.isNotEmpty()) {
            selectedSeason = uiState.show!!.seasons.keys.minOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.show?.title ?: "Show Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            } else if (uiState.show != null) {
                val show = uiState.show!!
                
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        ShowHeader(show)
                    }
                    
                    item {
                        SeasonSelector(
                            seasons = show.seasons.keys.toList().sorted(),
                            selectedSeason = selectedSeason,
                            onSeasonSelected = { selectedSeason = it }
                        )
                    }
                    
                    val episodes = selectedSeason?.let { show.seasons[it] } ?: emptyList()
                    items(episodes) { episode ->
                        EpisodeItem(episode = episode, onClick = { onEpisodeClick(episode.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun ShowHeader(show: com.ru9n.sratim.core.viewmodel.ShowDetailUiModel) {
    Column {
        AsyncImage(
            model = show.backdropUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentScale = ContentScale.Crop
        )
        
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ElevatedCard(
                    modifier = Modifier
                        .width(100.dp)
                        .aspectRatio(2f / 3f)
                ) {
                    AsyncImage(
                        model = show.posterUrl,
                        contentDescription = show.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = show.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Show",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Overview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = show.overview,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun SeasonSelector(
    seasons: List<Int>,
    selectedSeason: Int?,
    onSeasonSelected: (Int) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = selectedSeason?.let { seasons.indexOf(it) } ?: 0,
        edgePadding = 16.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        divider = {}
    ) {
        seasons.forEach { season ->
            Tab(
                selected = selectedSeason == season,
                onClick = { onSeasonSelected(season) },
                text = { Text("Season $season") }
            )
        }
    }
}

@Composable
fun EpisodeItem(episode: EpisodeUiModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (episode.stillUrl.isNotBlank()) {
                    AsyncImage(
                        model = episode.stillUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${episode.episode}. ${episode.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play",
                modifier = Modifier.padding(8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
