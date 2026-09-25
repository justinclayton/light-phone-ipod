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
import com.thelightphone.ipod.ui.components.ListRow
import com.thelightphone.ipod.ui.components.TextEditorRequest
import com.thelightphone.ipod.ui.components.TextEditorScreen
import com.thelightphone.ipod.ui.components.formatDuration
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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
import kotlinx.coroutines.launch

class SongPickerViewModel(
    private val playlistId: Long,
    private val repository: LibraryRepository,
) : LightViewModel<Unit>() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<List<TrackEntity>> = combine(repository.songs, _query) { songs, query ->
        if (query.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    it.album.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val inPlaylistIds: StateFlow<Set<Long>> = repository.observePlaylistTracks(playlistId)
        .map { rows -> rows.mapNotNull { it.track?.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun add(trackId: Long) {
        viewModelScope.launch { repository.addTrackToPlaylist(playlistId, trackId) }
    }
}

/** Search/browse picker for adding songs to a playlist; tapping a row adds it. */
class SongPickerScreen(
    private val sealedActivity: SealedLightActivity,
    private val playlistId: Long,
) : LightScreen<Unit, SongPickerViewModel>(sealedActivity) {

    override val viewModelClass: Class<SongPickerViewModel>
        get() = SongPickerViewModel::class.java

    override fun createViewModel() =
        SongPickerViewModel(playlistId, AppContainer.get(lightContext, sealedActivity).repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val results by viewModel.results.collectAsState()
        val inPlaylist by viewModel.inPlaylistIds.collectAsState()
        val query by viewModel.query.collectAsState()

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Add Songs"),
                    rightButton = LightBarButton.LightIcon(
                        LightIcons.SEARCH,
                        onClick = {
                            navigateTo(
                                screenFactory = { TextEditorScreen(it, TextEditorRequest("Search", query)) },
                                resultCallback = { result -> viewModel.setQuery(result.orEmpty()) },
                            )
                        },
                    ),
                )
                if (query.isNotBlank()) {
                    ListRow(
                        title = "Searching: \"$query\"",
                        subtitle = "Tap to clear",
                        onClick = { viewModel.setQuery("") },
                    )
                }
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    results.forEach { track ->
                        ListRow(
                            title = track.title,
                            subtitle = "${track.artist} — ${formatDuration(track.durationMs)}",
                            onClick = { viewModel.add(track.id) },
                            trailing = if (track.id in inPlaylist) {
                                { LightIcon(icon = LightIcons.SELECT_ON, contentDescription = "Already in playlist") }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}
