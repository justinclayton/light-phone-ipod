package com.thelightphone.ipod.ui

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
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.ipod.AppContainer
import com.thelightphone.ipod.data.LibraryRepository
import com.thelightphone.ipod.data.db.PlaylistEntity
import com.thelightphone.ipod.ui.components.ListRow
import com.thelightphone.ipod.ui.components.TextEditorRequest
import com.thelightphone.ipod.ui.components.TextEditorScreen
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val repository: LibraryRepository) : LightViewModel<Unit>() {
    val playlists: StateFlow<List<PlaylistEntity>> =
        repository.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createPlaylist(name.trim()) }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renamePlaylist(id, name.trim()) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.deletePlaylist(id) }
    }
}

/** Playlist list + create ("New Playlist…") / rename / delete. */
class PlaylistsScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlaylistsViewModel>(sealedActivity) {

    override val viewModelClass: Class<PlaylistsViewModel>
        get() = PlaylistsViewModel::class.java

    override fun createViewModel() = PlaylistsViewModel(AppContainer.get(lightContext, sealedActivity).repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val playlists by viewModel.playlists.collectAsState()
        var editMode by remember { mutableStateOf(false) }

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Playlists"),
                    rightButton = if (playlists.isNotEmpty()) {
                        LightBarButton.Text(if (editMode) "DONE" else "EDIT", onClick = { editMode = !editMode })
                    } else null,
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    if (!editMode) {
                        ListRow(
                            title = "New Playlist…",
                            onClick = {
                                navigateTo(
                                    screenFactory = {
                                        TextEditorScreen(it, TextEditorRequest(title = "Playlist Name", initialValue = "", initialCaps = true))
                                    },
                                    resultCallback = viewModel::create,
                                )
                            },
                        )
                    }
                    playlists.forEach { playlist ->
                        ListRow(
                            title = playlist.name,
                            onClick = {
                                if (editMode) {
                                    navigateTo(
                                        screenFactory = {
                                            TextEditorScreen(
                                                it,
                                                TextEditorRequest(title = "Playlist Name", initialValue = playlist.name, initialCaps = true),
                                            )
                                        },
                                        resultCallback = { name -> viewModel.rename(playlist.id, name) },
                                    )
                                } else {
                                    navigateTo(screenFactory = { PlaylistDetailScreen(it, playlist.id, playlist.name) })
                                }
                            },
                            trailing = if (editMode) {
                                {
                                    LightIcon(
                                        icon = LightIcons.TRASH,
                                        modifier = Modifier.lightClickable { viewModel.delete(playlist.id) },
                                        contentDescription = "Delete ${playlist.name}",
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
