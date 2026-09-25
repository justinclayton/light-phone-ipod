package com.thelightphone.ipod.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.ipod.AppContainer
import com.thelightphone.ipod.data.LibraryRepository
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.ipod.player.PlayerController
import com.thelightphone.ipod.ui.components.ListRow
import com.thelightphone.ipod.ui.components.TextEditorRequest
import com.thelightphone.ipod.ui.components.TextEditorScreen
import com.thelightphone.ipod.ui.components.TrackListScreen
import com.thelightphone.ipod.ui.components.formatDuration
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SearchResults(
    val artists: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val songs: List<TrackEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

class SearchViewModel(
    private val repository: LibraryRepository,
    private val player: PlayerController,
) : LightViewModel<Unit>() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<SearchResults> = combine(
        repository.artists,
        repository.albums,
        repository.songs,
        _query,
    ) { artists, albums, songs, query ->
        if (query.isBlank()) {
            SearchResults()
        } else {
            SearchResults(
                artists = artists.filter { it.contains(query, ignoreCase = true) },
                albums = albums.map { it.first.albumName }.distinct().filter { it.contains(query, ignoreCase = true) },
                songs = songs.filter { it.title.contains(query, ignoreCase = true) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchResults())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun playSong(track: TrackEntity) {
        val songs = results.value.songs
        val index = songs.indexOf(track)
        if (index >= 0) player.play(songs, index)
    }
}

/** One query across artists/albums/songs. */
class SearchScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SearchViewModel>(sealedActivity) {

    override val viewModelClass: Class<SearchViewModel>
        get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel {
        val deps = AppContainer.get(lightContext, sealedActivity)
        return SearchViewModel(deps.repository, deps.player)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val query by viewModel.query.collectAsState()
        val results by viewModel.results.collectAsState()

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Search"),
                )
                LightTextField(
                    label = "Query:",
                    value = query,
                    placeholder = "Search artists, albums, songs",
                    onClick = {
                        navigateTo(
                            screenFactory = { TextEditorScreen(it, TextEditorRequest("Search", query)) },
                            resultCallback = { result -> viewModel.setQuery(result.orEmpty()) },
                        )
                    },
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    if (results.artists.isNotEmpty()) {
                        SectionHeader("Artists")
                        results.artists.forEach { artist ->
                            ListRow(title = artist, onClick = { navigateTo(screenFactory = { AlbumsScreen(it, artistFilter = artist) }) })
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        SectionHeader("Albums")
                        results.albums.forEach { album ->
                            ListRow(
                                title = album,
                                onClick = {
                                    navigateTo(
                                        screenFactory = { sealed ->
                                            TrackListScreen(sealed, album) { repo ->
                                                repo.albums.map { all -> all.firstOrNull { it.first.albumName == album }?.second.orEmpty() }
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (results.songs.isNotEmpty()) {
                        SectionHeader("Songs")
                        results.songs.forEach { track ->
                            ListRow(
                                title = track.title,
                                subtitle = "${track.artist} — ${formatDuration(track.durationMs)}",
                                onClick = {
                                    viewModel.playSong(track)
                                    navigateTo(screenFactory = { NowPlayingScreen(it) })
                                },
                            )
                        }
                    }
                    if (query.isNotBlank() && results.isEmpty) {
                        LightText(text = "No results", variant = LightTextVariant.Detail, lighten = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    LightText(
        text = title.uppercase(),
        variant = LightTextVariant.Superfine,
        lighten = true,
        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
    )
}
