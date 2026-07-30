package com.example.sratim.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    viewModel: MovieDetailViewModel,
    onPlayClick: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (uiState.movie != null) {
            val movie = uiState.movie!!

            // Backdrop Background
            AsyncImage(
                model = movie.backdropUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.7f // Increased visibility
            )

            // Gradient Overlay - lightened for better backdrop visibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.9f),
                                Color.Black.copy(alpha = 0.3f),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = Float.POSITIVE_INFINITY
                        )
                    )
            )

            // Content
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 56.dp, vertical = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // High-res Poster
                AsyncImage(
                    model = movie.posterUrl,
                    contentDescription = movie.title,
                    modifier = Modifier
                        .fillMaxHeight(0.8f)
                        .aspectRatio(2f / 3f)
                        .background(Color.DarkGray),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(56.dp))

                // Metadata
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                    Text(
                        text = movie.title,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    
                    Text(
                        text = "Released: ${movie.releaseDate}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Play Button - moved up to be ALWAYS accessible
                    Button(
                        onClick = { onPlayClick(movie.id) },
                        modifier = Modifier.width(200.dp)
                    ) {
                        Text(text = "Play")
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Scrollable Overview - now fills the remaining space
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = movie.overview,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.verticalScroll(scrollState)
                        )
                    }
                }
            }
        }
    }
}
