package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CompletableDeferred

/**
 * Routes the hardware volume rocker to the media stream instead of forwarding it to
 * LightOS (which always adjusts the ringer). Used by [com.thelightphone.sdk.LightActivity]
 * when the tool opts in via [com.thelightphone.sdk.LightEntryPoint.volumeKeysControlMedia].
 */
internal class MediaVolumeKeys(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Handles key-down (including auto-repeat) for the volume keys; returns false for any other key. */
    fun onKeyDown(keyCode: Int): Boolean {
        val direction = volumeDirection(keyCode) ?: return false
        audioManager.adjustStreamVolume(STREAM, direction, 0)
        LightModalManager.show(MediaVolumeModal(currentLevel()))
        return true
    }

    /** Swallows the matching key-up so LightOS never sees half a key press. */
    fun onKeyUp(keyCode: Int): Boolean = volumeDirection(keyCode) != null

    private fun currentLevel(): Float {
        val max = audioManager.getStreamMaxVolume(STREAM).coerceAtLeast(1)
        return audioManager.getStreamVolume(STREAM).toFloat() / max
    }

    companion object {
        private const val STREAM = AudioManager.STREAM_MUSIC

        internal fun volumeDirection(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> null
        }
    }
}

// Modeled after the LightOS volume rocker modal (see the emulator's DeviceKeyModal).
private class MediaVolumeModal(private val level: Float) : LightModal {
    private val dismissDeferred = CompletableDeferred<Unit>()

    override val onExpired: () -> Unit = {}
    override fun dismiss() { dismissDeferred.complete(Unit) }
    override suspend fun awaitDismiss() { dismissDeferred.await() }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(themeColors) {
            Surface {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 3.5f.gridUnitsAsDp())
                ) {
                    Spacer(Modifier.height(10.5f.gridUnitsAsDp()))
                    LightText("volume", variant = LightTextVariant.Subtitle)
                    if (level <= 0f) {
                        Spacer(Modifier.height(2f.gridUnitsAsDp()))
                        LightIcon(LightIcons.SPEAKER_MUTED)
                    } else {
                        Spacer(Modifier.height(2.5f.gridUnitsAsDp()))
                        LightProgressBar(themeColors, level)
                    }
                }
            }
        }
    }
}
