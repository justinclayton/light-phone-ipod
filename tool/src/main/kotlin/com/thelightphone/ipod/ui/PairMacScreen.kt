package com.thelightphone.ipod.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.ipod.data.sync.MacPairing
import com.thelightphone.ipod.data.sync.PairingCode
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * The one-time pairing gesture (PRD §6): scan the code the Mac shows. Returns the
 * parsed pairing, a failure when the code wasn't ours, or `null` when backed out.
 */
class PairMacScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Result<MacPairing>>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var scanned by remember { mutableStateOf<String?>(null) }

        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = "Scan Code on Mac",
                onScanned = { scanned = it },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }

        LaunchedEffect(scanned) {
            val raw = scanned ?: return@LaunchedEffect
            goBack(PairingCode.parse(raw))
        }
    }
}
