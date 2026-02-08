package com.mcsfeb.tvalarmclock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.mcsfeb.tvalarmclock.data.model.*
import com.mcsfeb.tvalarmclock.data.remote.ContentIdMapper
import com.mcsfeb.tvalarmclock.data.remote.TmdbApi
import com.mcsfeb.tvalarmclock.ui.components.StreamingAppCard
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ContentPickerScreen - Where the user picks what to wake up to.
 *
 * FLOW:
 * 1. Pick a streaming app
 * 2. Based on the app type:
 *    - LIVE TV app (Sling, YouTube TV, etc.): Browse channel guide
 *    - ON-DEMAND app (Netflix, Hulu, etc.): Search for shows/movies
 *    - Either: "Just open the app" option always available
 *
 * For live TV: User picks a channel from the built-in guide (ESPN, CNN, etc.)
 * For on-demand: User searches for a show, picks season & episode
 * The app figures out the content ID automatically — no manual entry needed.
 */
@Composable
fun ContentPickerScreen(
    installedApps: List<StreamingApp>,
    onContentSelected: (StreamingContent) -> Unit,
    onTestLaunch: (StreamingApp, String) -> Unit,
    onTestLaunchAppOnly: (StreamingApp) -> Unit,
    onBack: () -> Unit,
    launchResultMessage: String?
) {
    // Navigation state
    var selectedApp by remember { mutableStateOf<StreamingApp?>(null) }
    var browseMode by remember { mutableStateOf<BrowseMode?>(null) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ContentInfo>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedShow by remember { mutableStateOf<ContentInfo?>(null) }
    var seasons by remember { mutableStateOf<List<SeasonInfo>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<SeasonInfo?>(null) }
    var episodes by remember { mutableStateOf<List<EpisodeInfo>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }

    // Channel guide state
    var selectedCategory by remember { mutableStateOf<ChannelCategory?>(null) }

    // Manual entry fallback
    var manualContentId by remember { mutableStateOf("") }
    var manualTitle by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            // ---- HEADER ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        when {
                            selectedShow != null -> {
                                selectedShow = null
                                seasons = emptyList()
                                selectedSeason = null
                                episodes = emptyList()
                            }
                            browseMode != null -> browseMode = null
                            selectedApp != null -> {
                                selectedApp = null
                                browseMode = null
                                searchQuery = ""
                                searchResults = emptyList()
                                selectedCategory = null
                            }
                            else -> onBack()
                        }
                    },
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(
                        text = when {
                            selectedApp == null -> "Home"
                            else -> "Back"
                        },
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = when {
                        selectedShow != null -> selectedShow!!.title
                        browseMode == BrowseMode.CHANNEL_GUIDE -> "${selectedApp!!.displayName} Channels"
                        browseMode == BrowseMode.SEARCH -> "Search ${selectedApp!!.displayName}"
                        browseMode == BrowseMode.MANUAL -> "Manual Entry"
                        selectedApp != null -> selectedApp!!.displayName
                        else -> "Pick a Streaming App"
                    },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedApp != null) Color(selectedApp!!.colorHex) else AlarmBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ================================================================
            // STEP 1: Pick a streaming app
            // ================================================================
            if (selectedApp == null) {
                Text("Which app should the alarm open?", fontSize = 18.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))

                val sortedApps = remember(installedApps) {
                    val installed = StreamingApp.allSorted().filter { installedApps.contains(it) }
                    val notInstalled = StreamingApp.allSorted().filter { !installedApps.contains(it) }
                    installed + notInstalled
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    items(sortedApps) { app ->
                        StreamingAppCard(
                            app = app,
                            isInstalled = installedApps.contains(app),
                            isSelected = false,
                            onClick = {
                                selectedApp = app
                                browseMode = null
                                searchQuery = ""
                                searchResults = emptyList()
                                selectedCategory = null
                                selectedShow = null
                            }
                        )
                    }
                }
            }
            // ================================================================
            // STEP 2: Choose browse mode for the selected app
            // ================================================================
            else if (browseMode == null) {
                val app = selectedApp!!
                // Show channel guide if this app has ANY channels in our guide
                val hasChannels = ChannelGuide.getChannelsForApp(app).isNotEmpty()

                Text(
                    "How do you want to find content?",
                    fontSize = 18.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Option: Just open the app
                    ModeCard(
                        title = "Just Open App",
                        description = "Opens ${app.displayName} to its home screen.",
                        color = AlarmTeal,
                        onClick = {
                            onContentSelected(
                                StreamingContent(
                                    app = app,
                                    contentId = "",
                                    title = "Open ${app.displayName}",
                                    launchMode = LaunchMode.APP_ONLY
                                )
                            )
                        }
                    )

                    // Option: Channel guide (shown if we have channels for this app)
                    if (hasChannels) {
                        ModeCard(
                            title = "Pick a Channel",
                            description = "Browse live channels like ESPN, CNN, HGTV...",
                            color = AlarmBlue,
                            onClick = { browseMode = BrowseMode.CHANNEL_GUIDE }
                        )
                    }

                    // Option: Search for shows/movies (available for ALL apps)
                    ModeCard(
                        title = "Search Shows",
                        description = "Search for a show or movie by name.",
                        color = Color(app.colorHex),
                        onClick = { browseMode = BrowseMode.SEARCH }
                    )

                    // Option: Manual ID entry (fallback)
                    ModeCard(
                        title = "Enter ID Manually",
                        description = "If you already know the content ID.",
                        color = DarkSurfaceVariant,
                        onClick = { browseMode = BrowseMode.MANUAL }
                    )
                }
            }
            // ================================================================
            // CHANNEL GUIDE (for live TV apps)
            // ================================================================
            else if (browseMode == BrowseMode.CHANNEL_GUIDE) {
                val app = selectedApp!!
                val categories = remember(app) { ChannelGuide.getCategoriesForApp(app) }

                if (selectedCategory == null) {
                    // Show category buttons
                    Text("Pick a category:", fontSize = 18.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(categories) { category ->
                            Surface(
                                onClick = { selectedCategory = category },
                                modifier = Modifier.height(60.dp),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = DarkSurface,
                                    focusedContainerColor = Color(app.colorHex).copy(alpha = 0.3f)
                                ),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = androidx.tv.material3.Border(
                                        border = androidx.compose.foundation.BorderStroke(
                                            2.dp, Color(app.colorHex)
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        category.displayName,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Show all channels below categories
                    Text("All channels:", fontSize = 16.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))

                    val allChannels = remember(app) { ChannelGuide.getChannelsForApp(app) }
                    ChannelList(
                        channels = allChannels,
                        app = app,
                        onChannelPicked = { channel ->
                            val channelId = ChannelGuide.getChannelId(channel, app) ?: ""
                            onContentSelected(
                                StreamingContent(
                                    app = app,
                                    contentId = channelId,
                                    title = channel.name,
                                    launchMode = if (channelId.isNotBlank()) LaunchMode.DEEP_LINK
                                    else LaunchMode.APP_ONLY
                                )
                            )
                        }
                    )
                } else {
                    // Show channels in selected category
                    val categoryChannels = remember(app, selectedCategory) {
                        ChannelGuide.getChannelsForAppByCategory(app, selectedCategory!!)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { selectedCategory = null },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("All Categories", fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            selectedCategory!!.displayName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(app.colorHex)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    ChannelList(
                        channels = categoryChannels,
                        app = app,
                        onChannelPicked = { channel ->
                            val channelId = ChannelGuide.getChannelId(channel, app) ?: ""
                            onContentSelected(
                                StreamingContent(
                                    app = app,
                                    contentId = channelId,
                                    title = channel.name,
                                    launchMode = if (channelId.isNotBlank()) LaunchMode.DEEP_LINK
                                    else LaunchMode.APP_ONLY
                                )
                            )
                        }
                    )
                }
            }
            // ================================================================
            // SEARCH MODE (for on-demand apps)
            // ================================================================
            else if (browseMode == BrowseMode.SEARCH && selectedShow == null) {
                val app = selectedApp!!

                // Search bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(fontSize = 20.sp, color = TextPrimary),
                        cursorBrush = SolidColor(Color(app.colorHex)),
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                2.dp,
                                Color(app.colorHex).copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .background(DarkSurface, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Search for a show or movie...",
                                    fontSize = 20.sp,
                                    color = TextSecondary.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { isSearching = true },
                        colors = ButtonDefaults.colors(containerColor = Color(app.colorHex)),
                        modifier = Modifier.height(52.dp),
                        enabled = searchQuery.isNotBlank() && !isSearching
                    ) {
                        Text(
                            if (isSearching) "Searching..." else "Search",
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                // Perform search when triggered
                if (isSearching) {
                    LaunchedEffect(searchQuery) {
                        val results = withContext(Dispatchers.IO) {
                            TmdbApi.searchContent(searchQuery)
                        }
                        searchResults = results
                        isSearching = false
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search results
                if (searchResults.isNotEmpty()) {
                    Text(
                        "${searchResults.size} results:",
                        fontSize = 16.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults) { content ->
                            SearchResultCard(
                                content = content,
                                app = app,
                                onClick = {
                                    if (content.mediaType == MediaType.MOVIE) {
                                        // For movies, use search to open the movie
                                        val contentId = ContentIdMapper.getContentId(
                                            content.tmdbId, app
                                        ) ?: ""
                                        onContentSelected(
                                            StreamingContent(
                                                app = app,
                                                contentId = contentId,
                                                title = "${content.title} (${content.year})",
                                                launchMode = if (contentId.isNotBlank())
                                                    LaunchMode.DEEP_LINK else LaunchMode.SEARCH,
                                                searchQuery = content.title
                                            )
                                        )
                                    } else {
                                        // For TV shows, go to season/episode picker
                                        selectedShow = content
                                    }
                                }
                            )
                        }
                    }
                } else if (!isSearching && searchQuery.isNotBlank()) {
                    Text(
                        "No results found. Try a different search.",
                        fontSize = 16.sp,
                        color = TextSecondary
                    )
                }
            }
            // ================================================================
            // SEASON/EPISODE PICKER (after selecting a TV show)
            // ================================================================
            else if (browseMode == BrowseMode.SEARCH && selectedShow != null) {
                val app = selectedApp!!
                val show = selectedShow!!

                // Load seasons
                LaunchedEffect(show.tmdbId) {
                    val loadedSeasons = withContext(Dispatchers.IO) {
                        TmdbApi.getSeasons(show.tmdbId)
                    }
                    seasons = loadedSeasons
                }

                Text(
                    "${show.title} (${show.year})",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (show.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        show.description.take(150) + if (show.description.length > 150) "..." else "",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Season selector
                if (seasons.isNotEmpty()) {
                    Text("Pick a season:", fontSize = 16.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { season ->
                            val isSelected = selectedSeason == season
                            Surface(
                                onClick = {
                                    selectedSeason = season
                                    isLoadingEpisodes = true
                                },
                                modifier = Modifier.height(48.dp),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isSelected) Color(app.colorHex)
                                    else DarkSurface,
                                    focusedContainerColor = Color(app.colorHex).copy(alpha = 0.7f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "S${season.seasonNumber}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Load episodes for selected season
                    if (isLoadingEpisodes && selectedSeason != null) {
                        LaunchedEffect(selectedSeason!!.seasonNumber) {
                            val loadedEpisodes = withContext(Dispatchers.IO) {
                                TmdbApi.getEpisodes(show.tmdbId, selectedSeason!!.seasonNumber)
                            }
                            episodes = loadedEpisodes
                            isLoadingEpisodes = false
                        }
                    }

                    // Episode list
                    if (selectedSeason != null && episodes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "${selectedSeason!!.name} - ${episodes.size} episodes:",
                            fontSize = 16.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(episodes) { episode ->
                                EpisodeCard(
                                    episode = episode,
                                    app = app,
                                    onClick = {
                                        val contentId = ContentIdMapper.getContentId(
                                            show.tmdbId, app
                                        ) ?: ""
                                        val title = "${show.title} S${episode.seasonNumber}E${episode.episodeNumber}"
                                        onContentSelected(
                                            StreamingContent(
                                                app = app,
                                                contentId = contentId,
                                                title = title,
                                                launchMode = if (contentId.isNotBlank())
                                                    LaunchMode.DEEP_LINK else LaunchMode.SEARCH,
                                                searchQuery = show.title
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    } else if (isLoadingEpisodes) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading episodes...", fontSize = 16.sp, color = TextSecondary)
                    }
                } else {
                    Text("Loading seasons...", fontSize = 16.sp, color = TextSecondary)
                }
            }
            // ================================================================
            // MANUAL ENTRY (fallback)
            // ================================================================
            else if (browseMode == BrowseMode.MANUAL) {
                val app = selectedApp!!

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color(app.colorHex).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .background(DarkSurface, RoundedCornerShape(12.dp))
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            "Enter the content details manually:",
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Name field
                        Text("Name:", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))
                        BasicTextField(
                            value = manualTitle,
                            onValueChange = { manualTitle = it },
                            textStyle = TextStyle(fontSize = 18.sp, color = TextPrimary),
                            cursorBrush = SolidColor(AlarmBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(14.dp),
                            decorationBox = { innerTextField ->
                                if (manualTitle.isEmpty()) {
                                    Text("e.g., My Favorite Show", fontSize = 18.sp, color = TextSecondary.copy(alpha = 0.4f))
                                }
                                innerTextField()
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Content ID field
                        Text("${app.contentIdLabel}:", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(app.description, fontSize = 12.sp, color = TextSecondary.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(6.dp))
                        BasicTextField(
                            value = manualContentId,
                            onValueChange = { manualContentId = it },
                            textStyle = TextStyle(fontSize = 18.sp, color = TextPrimary),
                            cursorBrush = SolidColor(Color(app.colorHex)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(app.colorHex).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(14.dp),
                            decorationBox = { innerTextField ->
                                if (manualContentId.isEmpty()) {
                                    Text("Content ID", fontSize = 18.sp, color = TextSecondary.copy(alpha = 0.4f))
                                }
                                innerTextField()
                            }
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    onContentSelected(
                                        StreamingContent(
                                            app = app,
                                            contentId = manualContentId.trim(),
                                            title = manualTitle.ifEmpty {
                                                if (manualContentId.isNotBlank()) "${app.displayName} Content"
                                                else "Open ${app.displayName}"
                                            },
                                            launchMode = if (manualContentId.isNotBlank())
                                                LaunchMode.DEEP_LINK else LaunchMode.APP_ONLY
                                        )
                                    )
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Save", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp))
                            }
                            if (manualContentId.isNotBlank()) {
                                Button(
                                    onClick = { onTestLaunch(app, manualContentId.trim()) },
                                    colors = ButtonDefaults.colors(containerColor = Color(app.colorHex)),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text("Test Launch", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                }
                            }
                            Button(
                                onClick = { onTestLaunchAppOnly(app) },
                                colors = ButtonDefaults.colors(containerColor = Color(app.colorHex).copy(alpha = 0.7f)),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Test Open", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Launch result message
            if (launchResultMessage != null) {
                Text(
                    text = launchResultMessage,
                    fontSize = 16.sp,
                    color = if (launchResultMessage.startsWith("✓")) AlarmActiveGreen
                    else AlarmFiringRed,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---- Browse modes ----
private enum class BrowseMode {
    CHANNEL_GUIDE,
    SEARCH,
    MANUAL
}

// ---- Mode selection card ----
@Composable
private fun ModeCard(
    title: String,
    description: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(220.dp).height(140.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = DarkSurface,
            focusedContainerColor = DarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, color),
                shape = RoundedCornerShape(14.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(modifier = Modifier.height(6.dp))
            Text(description, fontSize = 13.sp, color = TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Channel list ----
@Composable
private fun ChannelList(
    channels: List<LiveChannel>,
    app: StreamingApp,
    onChannelPicked: (LiveChannel) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(channels) { channel ->
            Surface(
                onClick = { onChannelPicked(channel) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = DarkSurface,
                    focusedContainerColor = Color(app.colorHex).copy(alpha = 0.2f)
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(app.colorHex)),
                        shape = RoundedCornerShape(8.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        channel.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        channel.category.displayName,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// ---- Search result card ----
@Composable
private fun SearchResultCard(
    content: ContentInfo,
    app: StreamingApp,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = DarkSurface,
            focusedContainerColor = Color(app.colorHex).copy(alpha = 0.15f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(app.colorHex)),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(app.colorHex).copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (content.mediaType == MediaType.TV_SHOW) "TV" else "Film",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    content.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${content.year} • ${if (content.mediaType == MediaType.TV_SHOW) "TV Show" else "Movie"}",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
            // Show if we have a known content ID
            val hasMapping = ContentIdMapper.getContentId(content.tmdbId, app) != null
            if (hasMapping) {
                Text("Direct Link", fontSize = 12.sp, color = AlarmActiveGreen)
            }
        }
    }
}

// ---- Episode card ----
@Composable
private fun EpisodeCard(
    episode: EpisodeInfo,
    app: StreamingApp,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = DarkSurface,
            focusedContainerColor = Color(app.colorHex).copy(alpha = 0.15f)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(app.colorHex)),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Episode number badge
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(app.colorHex).copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "E${episode.episodeNumber}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    episode.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.overview.isNotBlank()) {
                    Text(
                        episode.overview.take(80) + if (episode.overview.length > 80) "..." else "",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
