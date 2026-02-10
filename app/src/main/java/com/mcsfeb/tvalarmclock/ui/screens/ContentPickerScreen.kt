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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.mcsfeb.tvalarmclock.data.model.*
import com.mcsfeb.tvalarmclock.data.remote.ContentIdMapper
import com.mcsfeb.tvalarmclock.data.remote.TmdbApi
import com.mcsfeb.tvalarmclock.ui.components.*
import com.mcsfeb.tvalarmclock.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ContentPickerScreen - Where the user picks what to wake up to.
 *
 * FLOW:
 * 1. Pick a streaming app
 * 2. Based on the app type:
 *    - LIVE TV app: Browse channel guide
 *    - ON-DEMAND app: Search for shows/movies
 *    - Either: "Just open the app" option always available
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

    // Watch provider availability (TMDB tells us which apps have each show)
    var watchProviders by remember { mutableStateOf<Map<Int, List<StreamingApp>>>(emptyMap()) }

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
                TVButton(
                    text = when {
                        selectedApp == null -> "Home"
                        else -> "Back"
                    },
                    color = AlarmBlue,
                    compact = true,
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
                    }
                )
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
            // STEP 2: Choose browse mode
            // ================================================================
            else if (browseMode == null) {
                val app = selectedApp!!
                val hasChannels = ChannelGuide.getChannelsForApp(app).isNotEmpty()

                Text("How do you want to find content?", fontSize = 18.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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

                    if (hasChannels) {
                        ModeCard(
                            title = "Pick a Channel",
                            description = "Browse live channels like ESPN, CNN, HGTV...",
                            color = AlarmBlue,
                            onClick = { browseMode = BrowseMode.CHANNEL_GUIDE }
                        )
                    }

                    ModeCard(
                        title = "Search Shows",
                        description = "Search for a show or movie by name.",
                        color = Color(app.colorHex),
                        onClick = { browseMode = BrowseMode.SEARCH }
                    )

                    ModeCard(
                        title = "Enter ID Manually",
                        description = "If you already know the content ID.",
                        color = DarkSurfaceVariant,
                        onClick = { browseMode = BrowseMode.MANUAL }
                    )
                }
            }
            // ================================================================
            // CHANNEL GUIDE
            // ================================================================
            else if (browseMode == BrowseMode.CHANNEL_GUIDE) {
                val app = selectedApp!!
                val categories = remember(app) { ChannelGuide.getCategoriesForApp(app) }

                if (selectedCategory == null) {
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
                    val categoryChannels = remember(app, selectedCategory) {
                        ChannelGuide.getChannelsForAppByCategory(app, selectedCategory!!)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TVButton(
                            text = "All Categories",
                            color = AlarmBlue,
                            compact = true,
                            onClick = { selectedCategory = null }
                        )
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
            // SEARCH MODE
            // ================================================================
            else if (browseMode == BrowseMode.SEARCH && selectedShow == null) {
                val app = selectedApp!!

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
                    TVButton(
                        text = if (isSearching) "Searching..." else "Search",
                        color = Color(app.colorHex),
                        enabled = searchQuery.isNotBlank() && !isSearching,
                        onClick = { isSearching = true }
                    )
                }

                if (isSearching) {
                    LaunchedEffect(searchQuery) {
                        val results = withContext(Dispatchers.IO) {
                            TmdbApi.searchContent(searchQuery)
                        }
                        searchResults = results
                        isSearching = false

                        // Fetch watch providers for each result in the background
                        withContext(Dispatchers.IO) {
                            val providers = mutableMapOf<Int, List<StreamingApp>>()
                            for (result in results.take(5)) { // Only fetch for top 5 to limit API calls
                                val apps = TmdbApi.getWatchProviderApps(result.tmdbId, result.mediaType)
                                if (apps.isNotEmpty()) {
                                    providers[result.tmdbId] = apps
                                }
                            }
                            watchProviders = providers
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (searchResults.isNotEmpty()) {
                    Text(
                        "${searchResults.size} results (search mode is most reliable):",
                        fontSize = 16.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(searchResults) { content ->
                            SearchResultCard(
                                content = content,
                                app = app,
                                availableOn = watchProviders[content.tmdbId],
                                onClick = {
                                    if (content.mediaType == MediaType.MOVIE) {
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
            // SEASON/EPISODE PICKER
            // ================================================================
            else if (browseMode == BrowseMode.SEARCH && selectedShow != null) {
                val app = selectedApp!!
                val show = selectedShow!!

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

                    if (isLoadingEpisodes && selectedSeason != null) {
                        LaunchedEffect(selectedSeason!!.seasonNumber) {
                            val loadedEpisodes = withContext(Dispatchers.IO) {
                                TmdbApi.getEpisodes(show.tmdbId, selectedSeason!!.seasonNumber)
                            }
                            episodes = loadedEpisodes
                            isLoadingEpisodes = false
                        }
                    }

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
                                        // Always use SEARCH as default — it's the most reliable
                                        onContentSelected(
                                            StreamingContent(
                                                app = app,
                                                contentId = contentId,
                                                title = title,
                                                launchMode = if (contentId.isNotBlank())
                                                    LaunchMode.DEEP_LINK else LaunchMode.SEARCH,
                                                searchQuery = "${show.title} Season ${episode.seasonNumber}"
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
            // MANUAL ENTRY
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
                            TVButton(
                                text = "Save",
                                color = AlarmActiveGreen,
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
                                }
                            )
                            if (manualContentId.isNotBlank()) {
                                TVButton(
                                    text = "Test Launch",
                                    color = Color(app.colorHex),
                                    onClick = { onTestLaunch(app, manualContentId.trim()) }
                                )
                            }
                            TVButton(
                                text = "Test Open",
                                color = Color(app.colorHex),
                                onClick = { onTestLaunchAppOnly(app) }
                            )
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
                    color = if (launchResultMessage.startsWith("\u2713")) AlarmActiveGreen
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
