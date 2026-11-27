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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.ru9n.sratim.core.network.Library
import com.ru9n.sratim.core.viewmodel.MainViewModel
import com.ru9n.sratim.core.viewmodel.RecentlyWatchedUiModel
import kotlinx.coroutines.yield

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onLibraryClick: (Int) -> Unit,
    onItemClick: (id: Int, type: String, showId: Int?) -> Unit = { _, _, _ -> },
    onSettingsClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val firstContentFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by rememberSaveable { mutableStateOf(false) }

    val hasContent = uiState.recentlyWatched.isNotEmpty() || uiState.libraries.isNotEmpty()
    LaunchedEffect(hasContent) {
        if (hasContent && !hasRequestedInitialFocus) {
            hasRequestedInitialFocus = true
            yield()
            runCatching { firstContentFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    Button(onClick = onSettingsClick) {
                        Text("Settings")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(vertical = 36.dp),
            ) {
                if (uiState.recentlyWatched.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 48.dp, bottom = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Recently Watched",
                            style = MaterialTheme.typography.displayMedium,
                        )
                        SettingsHeaderButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .focusRequester(settingsFocusRequester)
                                .focusProperties { down = firstContentFocusRequester },
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.focusProperties { up = settingsFocusRequester },
                    ) {
                        itemsIndexed(
                            items = uiState.recentlyWatched,
                            key = { _, item -> "${item.type}_${item.id}" },
                            contentType = { _, item -> item.type },
                        ) { index, item ->
                            RecentlyWatchedCard(
                                item = item,
                                onItemClick = onItemClick,
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstContentFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(36.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, end = 48.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Libraries",
                        style = MaterialTheme.typography.displayMedium,
                    )
                    if (uiState.recentlyWatched.isEmpty()) {
                        SettingsHeaderButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .focusRequester(settingsFocusRequester)
                                .focusProperties { down = firstContentFocusRequester },
                        )
                    }
                }

                val hasRecent = uiState.recentlyWatched.isNotEmpty()
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = if (!hasRecent) {
                        Modifier.focusProperties { up = settingsFocusRequester }
                    } else {
                        Modifier
                    },
                ) {
                    itemsIndexed(
                        items = uiState.libraries,
                        key = { _, library -> library.id },
                        contentType = { _, _ -> "library" },
                    ) { index, library ->
                        val cardModifier = if (!hasRecent && index == 0) {
                            Modifier
                                .size(width = 200.dp, height = 150.dp)
                                .focusRequester(firstContentFocusRequester)
                        } else {
                            Modifier.size(width = 200.dp, height = 150.dp)
                        }
                        LibraryCard(
                            library = library,
                            onLibraryClick = onLibraryClick,
                            modifier = cardModifier,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryCard(
    library: Library,
    onLibraryClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onLibraryClick(library.id) },
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = library.name, style = MaterialTheme.typography.headlineSmall)
                Text(text = library.type, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsHeaderButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Settings",
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Settings")
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecentlyWatchedCard(
    item: RecentlyWatchedUiModel,
    onItemClick: (id: Int, type: String, showId: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(124.dp),
    ) {
        Card(
            onClick = { onItemClick(item.id, item.type, item.showId) },
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
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }

                // Progress Bar at the bottom of the poster
                if (item.progressPercent > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
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
        Spacer(modifier = Modifier.height(6.dp))
        val primaryTitle = item.showTitle ?: item.title
        Text(
            text = primaryTitle,
            style = MaterialTheme.typography.labelMedium,
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
