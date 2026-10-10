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
import com.thelightphone.ipod.data.sync.SyncEngine
import com.thelightphone.ipod.player.PlayerController
import com.thelightphone.ipod.ui.components.ListRow
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
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
import kotlinx.coroutines.launch

class MainMenuViewModel(
    val repository: LibraryRepository,
    val player: PlayerController,
    private val sync: SyncEngine,
) : LightViewModel<Unit>() {
    /** PRD §8: sync happens on its own when she opens the tool. */
    fun autoSync() = sync.autoSync()

    val hasNowPlaying: StateFlow<Boolean> = player.queue.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, player.queue.value.isNotEmpty())

    fun rescan() {
        viewModelScope.launch { runCatching { repository.rescan() } }
    }
}

@InitialScreen
class MainMenuScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, MainMenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<MainMenuViewModel>
        get() = MainMenuViewModel::class.java

    override fun createViewModel(): MainMenuViewModel {
        val deps = AppContainer.get(lightContext, sealedActivity)
        return MainMenuViewModel(deps.repository, deps.player, deps.sync)
    }

    override fun willShow() {
        viewModel.rescan()
        viewModel.autoSync()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val hasNowPlaying by viewModel.hasNowPlaying.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text("iPod"))
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    ListRow("Artists", onClick = { navigateTo(screenFactory = { ArtistsScreen(it) }) })
                    ListRow("Albums", onClick = { navigateTo(screenFactory = { AlbumsScreen(it) }) })
                    ListRow("Songs", onClick = { navigateTo(screenFactory = { SongsScreen(it) }) })
                    ListRow("Playlists", onClick = { navigateTo(screenFactory = { PlaylistsScreen(it) }) })
                    ListRow("Search", onClick = { navigateTo(screenFactory = { SearchScreen(it) }) })
                    if (hasNowPlaying) {
                        ListRow("Now Playing", onClick = { navigateTo(screenFactory = { NowPlayingScreen(it) }) })
                    }
                    ListRow("Settings", onClick = { navigateTo(screenFactory = { SettingsScreen(it) }) })
                }
            }
        }
    }
}
