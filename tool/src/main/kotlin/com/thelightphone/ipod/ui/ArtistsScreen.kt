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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ArtistsViewModel(repository: LibraryRepository) : LightViewModel<Unit>() {
    val artists: StateFlow<List<String>> = repository.artists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

/** A–Z artists -> that artist's albums (AlbumsScreen filtered). */
class ArtistsScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ArtistsViewModel>(sealedActivity) {

    override val viewModelClass: Class<ArtistsViewModel>
        get() = ArtistsViewModel::class.java

    override fun createViewModel() = ArtistsViewModel(AppContainer.get(lightContext, sealedActivity).repository)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val artists by viewModel.artists.collectAsState()

        LightTheme(colors = themeColors) {
            Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Artists"),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 1f.gridUnitsAsDp())) {
                    artists.forEach { artist ->
                        ListRow(title = artist, onClick = { navigateTo(screenFactory = { AlbumsScreen(it, artistFilter = artist) }) })
                    }
                }
            }
        }
    }
}
