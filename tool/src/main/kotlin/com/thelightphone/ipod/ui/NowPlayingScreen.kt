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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.ipod.AppContainer
import com.thelightphone.ipod.data.db.TrackEntity
import com.thelightphone.ipod.player.PlayerController
import com.thelightphone.ipod.player.RepeatMode
import com.thelightphone.ipod.ui.components.formatDuration
import com.thelightphone.ipod.ui.components.formatRemaining
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

class NowPlayingViewModel(private val player: PlayerController) : LightViewModel<Unit>() {
    val currentTrack: StateFlow<TrackEntity?> = player.currentTrack
    val currentIndex: StateFlow<Int> = player.currentIndex
    val queue: StateFlow<List<TrackEntity>> = player.queue
    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val positionMs: StateFlow<Long> = player.positionMs
    val durationMs: StateFlow<Long> = player.durationMs
    val shuffle: StateFlow<Boolean> = player.shuffle
    val repeat: StateFlow<RepeatMode> = player.repeat

    fun togglePlayPause() = player.togglePlayPause()
    fun skipToNext() = player.skipToNext()
    fun skipToPrevious() = player.skipToPrevious()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
}

class NowPlayingScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, NowPlayingViewModel>(sealedActivity) {

    override val viewModelClass: Class<NowPlayingViewModel>
        get() = NowPlayingViewModel::class.java

    override fun createViewModel() = NowPlayingViewModel(AppContainer.get(lightContext, sealedActivity).player)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val track by viewModel.currentTrack.collectAsState()
        val index by viewModel.currentIndex.collectAsState()
        val queue by viewModel.queue.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val shuffle by viewModel.shuffle.collectAsState()
        val repeat by viewModel.repeat.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.TwoLineDetail("Now Playing", "${index + 1} of ${queue.size}"),
                )

                LightText(
                    text = track?.title ?: "Nothing playing",
                    variant = LightTextVariant.Heading,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = track?.artist.orEmpty(),
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = track?.album.orEmpty(),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                LightTouchableProgressBar(
                    colors = themeColors,
                    progress = if (duration > 0L) (position.toFloat() / duration.toFloat()) else 0f,
                    onValueChange = { fraction -> viewModel.seekTo((fraction * duration).toLong()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1f.gridUnitsAsDp()),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    LightText(text = formatDuration(position), variant = LightTextVariant.Detail, modifier = Modifier.weight(1f))
                    LightText(
                        text = formatRemaining(position, duration),
                        variant = LightTextVariant.Detail,
                        align = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "SHUFFLE",
                        variant = LightTextVariant.Superfine,
                        lighten = !shuffle,
                        modifier = Modifier.weight(1f).lightClickable(onClick = viewModel::toggleShuffle),
                    )
                    LightText(
                        text = "REPEAT: ${repeat.name.uppercase()}",
                        variant = LightTextVariant.Superfine,
                        lighten = repeat == RepeatMode.Off,
                        align = TextAlign.End,
                        modifier = Modifier.weight(1f).lightClickable(onClick = viewModel::cycleRepeat),
                    )
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(LightIcons.REWIND, onClick = viewModel::skipToPrevious, contentDescription = "Previous"),
                        LightBarButton.LightIcon(
                            icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                            onClick = viewModel::togglePlayPause,
                        ),
                        LightBarButton.LightIcon(LightIcons.FAST_FORWARD, onClick = viewModel::skipToNext, contentDescription = "Next"),
                    ),
                )
            }
        }
    }
}
