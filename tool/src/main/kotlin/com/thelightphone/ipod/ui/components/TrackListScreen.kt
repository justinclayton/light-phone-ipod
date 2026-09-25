package com.thelightphone.ipod.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Modifier
import com.thelightphone.ipod.AppContainer
import com.thelightphone.ipod.data.LibraryRepository
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.ipod.player.PlayerController
import com.thelightphone.ipod.ui.NowPlayingScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TrackListViewModel(
    val title: String,
    tracks: Flow<List<TrackEntity>>,
    private val player: PlayerController,
) : LightViewModel<Unit>() {
    val tracks: StateFlow<List<TrackEntity>> = tracks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun play(index: Int) = player.play(tracks.value, index)
}

/**
 * A titled, scrollable list of songs that replaces the playback queue when one
 * is tapped (iPod behavior, D6). Backs Songs, an artist's "All Songs", and an
 * album's track list — those differ only in title + which tracks flow feeds them.
 */
class TrackListScreen(
    private val sealedActivity: SealedLightActivity,
    private val title: String,
    private val tracksFlow: (LibraryRepository) -> Flow<List<TrackEntity>>,
) : LightScreen<Unit, TrackListViewModel>(sealedActivity) {

    override val viewModelClass: Class<TrackListViewModel>
        get() = TrackListViewModel::class.java

    override fun createViewModel(): TrackListViewModel {
        val deps = AppContainer.get(lightContext, sealedActivity)
        return TrackListViewModel(title, tracksFlow(deps.repository), deps.player)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val tracks by viewModel.tracks.collectAsState()

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(viewModel.title),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    tracks.forEachIndexed { index, track ->
                        SongRow(track) {
                            viewModel.play(index)
                            navigateTo(screenFactory = { NowPlayingScreen(it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongRow(track: TrackEntity, onClick: () -> Unit) {
    ListRow(
        title = track.title,
        subtitle = "${track.artist} — ${formatDuration(track.durationMs)}",
        onClick = onClick,
    )
}
