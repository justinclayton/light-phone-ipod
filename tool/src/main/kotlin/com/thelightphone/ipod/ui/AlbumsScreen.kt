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
import com.thelightphone.ipod.data.AlbumKey
import com.thelightphone.ipod.data.LibraryRepository
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.ipod.ui.components.ListRow
import com.thelightphone.ipod.ui.components.TrackListScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AlbumsViewModel(
    private val repository: LibraryRepository,
    val artistFilter: String?,
) : LightViewModel<Unit>() {
    val albums: StateFlow<List<Pair<AlbumKey, List<TrackEntity>>>> =
        (if (artistFilter != null) repository.albumsByArtist(artistFilter) else repository.albums)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

/** A–Z albums (or one artist's albums, with an "All Songs" row) -> tap -> that album's track list. */
class AlbumsScreen(
    private val sealedActivity: SealedLightActivity,
    private val artistFilter: String? = null,
) : LightScreen<Unit, AlbumsViewModel>(sealedActivity) {

    override val viewModelClass: Class<AlbumsViewModel>
        get() = AlbumsViewModel::class.java

    override fun createViewModel(): AlbumsViewModel {
        val deps = AppContainer.get(lightContext, sealedActivity)
        return AlbumsViewModel(deps.repository, artistFilter)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val albums by viewModel.albums.collectAsState()
        val artistFilter = viewModel.artistFilter

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(artistFilter ?: "Albums"),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    if (artistFilter != null) {
                        ListRow(
                            title = "All Songs",
                            onClick = {
                                navigateTo(
                                    screenFactory = { sealed ->
                                        TrackListScreen(sealed, artistFilter) { repo -> repo.songsByArtist(artistFilter) }
                                    },
                                )
                            },
                        )
                    }
                    var shownCompilationsHeader = false
                    albums.forEach { (key, tracks) ->
                        if (key.isCompilation && !shownCompilationsHeader) {
                            shownCompilationsHeader = true
                            LightText(
                                text = "COMPILATIONS",
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        val year = tracks.mapNotNull { it.year }.minOrNull()
                        ListRow(
                            title = key.albumName,
                            subtitle = if (year != null) "${key.albumArtist} — $year" else key.albumArtist,
                            onClick = {
                                navigateTo(
                                    screenFactory = { sealed ->
                                        TrackListScreen(sealed, key.albumName) { repo ->
                                            repo.albums.map { all -> all.firstOrNull { it.first == key }?.second.orEmpty() }
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
