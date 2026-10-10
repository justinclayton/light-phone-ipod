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
import com.thelightphone.ipod.BuildConfig
import com.thelightphone.ipod.data.LibraryRepository
import com.thelightphone.ipod.data.sync.SyncEngine
import com.thelightphone.ipod.data.sync.SyncMessages
import com.thelightphone.ipod.data.sync.SyncState
import com.thelightphone.ipod.ui.components.ListRow
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: LibraryRepository,
    sync: SyncEngine,
) : LightViewModel<Unit>() {
    /** One line under the Sync row: what the Mac link is doing right now, or how the last sync went. */
    val syncSubtitle: StateFlow<String> = combine(sync.state, sync.lastSync) { state, last ->
        when (state) {
            SyncState.NotPaired -> "Not connected to a Mac"
            is SyncState.Idle -> last?.summary?.let { "Last sync: $it" } ?: "Connected to ${state.macName}"
            is SyncState.Connecting -> "Looking for ${state.macName}…"
            is SyncState.Downloading -> SyncMessages.progress(state.done, state.total) + "…"
            is SyncState.Done -> SyncMessages.summary(state.received, state.failures.size)
            is SyncState.Failed -> "Couldn't reach ${state.macName}"
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val songCount: StateFlow<Int> = repository.songs.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    var isScanning: Boolean = false
        private set

    fun rescan(onDone: () -> Unit = {}) {
        if (isScanning) return
        isScanning = true
        viewModelScope.launch {
            runCatching { repository.rescan() }
            isScanning = false
            onDone()
        }
    }
}

/** The rows that only matter now and then: Mac sync, a manual rescan, and which build this is. */
class SettingsScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel {
        val deps = AppContainer.get(lightContext, sealedActivity)
        return SettingsViewModel(deps.repository, deps.sync)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val songCount by viewModel.songCount.collectAsState()
        val syncSubtitle by viewModel.syncSubtitle.collectAsState()
        var scanning by remember { mutableStateOf(viewModel.isScanning) }

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    ListRow("Sync", subtitle = syncSubtitle, onClick = { navigateTo(screenFactory = { SyncScreen(it) }) })
                    ListRow(
                        title = if (scanning) "Rescanning…" else "Rescan Library",
                        subtitle = "$songCount songs",
                        onClick = { scanning = true; viewModel.rescan { scanning = false } },
                    )
                    ListRow(
                        title = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        subtitle = "Built ${formatTime(BuildConfig.BUILD_TIME_MS)} · ${BuildConfig.GIT_SHA}",
                    )
                }
            }
        }
    }

    private fun formatTime(epochMs: Long): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
