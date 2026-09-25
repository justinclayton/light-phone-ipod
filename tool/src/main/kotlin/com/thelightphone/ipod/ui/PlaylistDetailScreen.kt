package com.thelightphone.ipod.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.ipod.AppContainer
import com.thelightphone.ipod.data.LibraryRepository
import com.thelightphone.ipod.data.PlaylistTrackRow
import com.thelightphone.ipod.player.PlayerController
import com.thelightphone.ipod.ui.components.ListRow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val playlistId: Long,
    val playlistName: String,
    private val repository: LibraryRepository,
    private val player: PlayerController,
) : LightViewModel<Unit>() {
    val rows: StateFlow<List<PlaylistTrackRow>> =
        repository.observePlaylistTracks(playlistId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun playAll() {
        val available = rows.value.mapNotNull { it.track }
        if (available.isNotEmpty()) player.play(available, 0)
    }

    fun playFrom(row: PlaylistTrackRow) {
        val track = row.track ?: return
        val available = rows.value.mapNotNull { it.track }
        val index = available.indexOf(track)
        if (index >= 0) player.play(available, index)
    }

    fun remove(entryId: Long) {
        viewModelScope.launch { repository.removeEntryFromPlaylist(playlistId, entryId) }
    }

    fun move(entryId: Long, delta: Int) {
        val ids = rows.value.map { it.entryId }.toMutableList()
        val from = ids.indexOf(entryId)
        val to = (from + delta).coerceIn(ids.indices)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        viewModelScope.launch { repository.reorderPlaylist(playlistId, ids) }
    }
}

/** Play, add songs (SongPicker), remove, reorder, edit mode. */
class PlaylistDetailScreen(
    private val sealedActivity: SealedLightActivity,
    private val playlistId: Long,
    private val playlistName: String,
) : LightScreen<Unit, PlaylistDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<PlaylistDetailViewModel>
        get() = PlaylistDetailViewModel::class.java

    override fun createViewModel(): PlaylistDetailViewModel {
        val deps = AppContainer.get(lightContext, sealedActivity)
        return PlaylistDetailViewModel(playlistId, playlistName, deps.repository, deps.player)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val rows by viewModel.rows.collectAsState()
        var editMode by remember { mutableStateOf(false) }

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(viewModel.playlistName),
                    rightButton = if (rows.isNotEmpty()) {
                        LightBarButton.Text(if (editMode) "DONE" else "EDIT", onClick = { editMode = !editMode })
                    } else null,
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    if (!editMode) {
                        ListRow(title = "Add Songs…", onClick = { navigateTo(screenFactory = { SongPickerScreen(it, playlistId) }) })
                        if (rows.isNotEmpty()) {
                            ListRow(
                                title = "Play All",
                                onClick = { viewModel.playAll(); navigateTo(screenFactory = { NowPlayingScreen(it) }) },
                            )
                        }
                    }
                    rows.forEachIndexed { index, row ->
                        PlaylistRow(
                            row = row,
                            editMode = editMode,
                            canMoveUp = index > 0,
                            canMoveDown = index < rows.lastIndex,
                            onClick = {
                                if (!editMode && row.track != null) {
                                    viewModel.playFrom(row)
                                    navigateTo(screenFactory = { NowPlayingScreen(it) })
                                }
                            },
                            onMoveUp = { viewModel.move(row.entryId, -1) },
                            onMoveDown = { viewModel.move(row.entryId, 1) },
                            onRemove = { viewModel.remove(row.entryId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    row: PlaylistTrackRow,
    editMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val track = row.track
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (!editMode) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = track?.title ?: "Unavailable",
                variant = LightTextVariant.Copy,
                lighten = track == null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track != null) {
                LightText(text = track.artist, variant = LightTextVariant.Detail, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (editMode) {
            if (canMoveUp) LightIcon(icon = LightIcons.UP, modifier = Modifier.lightClickable(onClick = onMoveUp))
            if (canMoveDown) LightIcon(icon = LightIcons.DOWN, modifier = Modifier.lightClickable(onClick = onMoveDown))
            LightIcon(icon = LightIcons.TRASH, modifier = Modifier.lightClickable(onClick = onRemove))
        }
    }
}
