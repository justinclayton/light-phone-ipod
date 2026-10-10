package com.thelightphone.ipod.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
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
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackListViewModel(
    val title: String,
    tracks: Flow<List<TrackEntity>>,
    private val player: PlayerController,
    private val repository: LibraryRepository,
) : LightViewModel<Unit>() {
    val tracks: StateFlow<List<TrackEntity>> = tracks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun play(index: Int) = player.play(tracks.value, index)

    /** Deletes the song from the phone, then drops it from the playback queue. */
    fun delete(track: TrackEntity) {
        viewModelScope.launch {
            repository.deleteTrack(track.id)
            player.removeTrack(track.id)
        }
    }
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
        return TrackListViewModel(title, tracksFlow(deps.repository), deps.player, deps.repository)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val tracks by viewModel.tracks.collectAsState()
        var editMode by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<TrackEntity?>(null) }

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(viewModel.title),
                    rightButton = if (tracks.isNotEmpty() && pendingDelete == null) {
                        LightBarButton.Text(if (editMode) "DONE" else "EDIT", onClick = { editMode = !editMode })
                    } else null,
                )
                val doomed = pendingDelete
                if (doomed != null) {
                    DeleteConfirmation(
                        track = doomed,
                        onConfirm = {
                            viewModel.delete(doomed)
                            pendingDelete = null
                        },
                        onCancel = { pendingDelete = null },
                    )
                } else {
                    LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                        tracks.forEachIndexed { index, track ->
                            ListRow(
                                title = track.title,
                                subtitle = "${track.artist} — ${formatDuration(track.durationMs)}",
                                onClick = {
                                    if (editMode) {
                                        pendingDelete = track
                                    } else {
                                        viewModel.play(index)
                                        navigateTo(screenFactory = { NowPlayingScreen(it) })
                                    }
                                },
                                trailing = if (editMode) {
                                    {
                                        LightIcon(
                                            icon = LightIcons.TRASH,
                                            modifier = Modifier.lightClickable { pendingDelete = track },
                                            contentDescription = "Delete ${track.title}",
                                        )
                                    }
                                } else null,
                            )
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

/** Full-screen "are you sure" for a permanent song delete; nothing is removed until DELETE is tapped. */
@Composable
private fun DeleteConfirmation(track: TrackEntity, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp())) {
        LightText(text = "Delete this song?", variant = LightTextVariant.Heading)
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
        LightText(text = track.artist, variant = LightTextVariant.Detail, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LightText(
            text = "It will be removed from this phone and from your playlists. This can't be undone.",
            variant = LightTextVariant.Detail,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
        ListRow(title = "DELETE", onClick = onConfirm)
        ListRow(title = "Cancel", onClick = onCancel)
    }
}
