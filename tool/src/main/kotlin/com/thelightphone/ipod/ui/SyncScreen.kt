package com.thelightphone.ipod.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.ipod.AppContainer
import com.thelightphone.ipod.data.sync.LastSync
import com.thelightphone.ipod.data.sync.MacPairing
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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SyncViewModel(private val engine: SyncEngine) : LightViewModel<Unit>() {
    val state: StateFlow<SyncState> = engine.state
    val lastSync: StateFlow<LastSync?> = engine.lastSync

    /** Shown when a scan produced something that wasn't our pairing code. */
    val scanError: MutableStateFlow<String?> = MutableStateFlow(null)

    fun onPairResult(result: Result<MacPairing>?) {
        if (result == null) return
        result.fold(
            onSuccess = { pairing ->
                scanError.value = null
                viewModelScope.launch {
                    engine.pair(pairing)
                    engine.syncNow()
                }
            },
            onFailure = { scanError.value = SyncMessages.BAD_CODE },
        )
    }

    fun syncNow() = engine.syncNow()

    fun forget() {
        viewModelScope.launch { engine.unpair() }
    }
}

/**
 * Status + the explicit "Sync" fallback (PRD §8), pairing, and every failure spelled
 * out in a sentence she can act on (PRD §5).
 */
class SyncScreen(private val sealedActivity: SealedLightActivity) : LightScreen<Unit, SyncViewModel>(sealedActivity) {

    override val viewModelClass: Class<SyncViewModel>
        get() = SyncViewModel::class.java

    override fun createViewModel() = SyncViewModel(AppContainer.get(lightContext, sealedActivity).sync)

    private fun scanCode() {
        navigateTo(screenFactory = { PairMacScreen(it) }, resultCallback = viewModel::onPairResult)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val lastSync by viewModel.lastSync.collectAsState()
        val scanError by viewModel.scanError.collectAsState()

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Sync"),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    Status(state, lastSync)
                    scanError?.let { Body(it) }
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    Actions(state)
                }
            }
        }
    }

    @Composable
    private fun Status(state: SyncState, lastSync: LastSync?) {
        when (state) {
            SyncState.NotPaired -> {
                Heading(SyncMessages.NOT_PAIRED_TITLE)
                Body(SyncMessages.NOT_PAIRED_BODY)
            }
            is SyncState.Idle -> {
                Heading("Connected to ${state.macName}")
                Body(lastSync?.let { "Last sync: ${it.summary}, ${formatTime(it.atEpochMs)}" } ?: "Hasn't synced yet.")
            }
            is SyncState.Connecting -> {
                Heading("Looking for ${state.macName}…")
                Body("Both need to be awake and on the same Wi-Fi.")
            }
            is SyncState.Downloading -> {
                Heading(SyncMessages.progress(state.done, state.total))
                Body(state.currentName)
            }
            is SyncState.Done -> {
                Heading(SyncMessages.summary(state.received, state.failures.size))
                if (state.failures.isEmpty()) {
                    Body(if (state.received > 0) "They're in your library now." else "Drop songs on the app on ${state.macName} first.")
                } else {
                    state.failures.forEach { ListRow(title = it.path, subtitle = it.reason) }
                }
            }
            is SyncState.Failed -> {
                Heading("Couldn't sync")
                Body(state.message)
            }
        }
    }

    @Composable
    private fun Actions(state: SyncState) {
        val busy = state is SyncState.Connecting || state is SyncState.Downloading
        when {
            state is SyncState.NotPaired -> ListRow("Scan Code on Mac…", onClick = ::scanCode)
            busy -> Unit
            else -> {
                ListRow(if (state is SyncState.Failed) "Try Again" else "Sync Now", onClick = viewModel::syncNow)
                ListRow("Scan Code on Mac Again…", subtitle = "If you moved to a new Mac or it stopped recognizing this phone", onClick = ::scanCode)
                ListRow("Forget This Mac", onClick = viewModel::forget)
            }
        }
    }

    @Composable
    private fun Heading(text: String) {
        LightText(text = text, variant = LightTextVariant.Heading, modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
    }

    @Composable
    private fun Body(text: String) {
        LightText(text = text, variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()))
    }

    private fun formatTime(epochMs: Long): String =
        DateTimeFormatter.ofPattern("MMM d 'at' h:mm a").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}
