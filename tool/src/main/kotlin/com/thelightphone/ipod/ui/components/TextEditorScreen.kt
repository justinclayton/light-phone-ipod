package com.thelightphone.ipod.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

data class TextEditorRequest(
    val title: String,
    val initialValue: String,
    val initialCaps: Boolean = false,
)

/** Full-screen text entry via the LightOS keyboard; returns the entered text or `null` on cancel. */
class TextEditorScreen(
    sealedActivity: SealedLightActivity,
    private val request: TextEditorRequest,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val textState = rememberTextFieldState(request.initialValue)
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = request.title,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { result -> goBack(result.toString()) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                initialCaps = request.initialCaps,
            )
        }
    }
}
