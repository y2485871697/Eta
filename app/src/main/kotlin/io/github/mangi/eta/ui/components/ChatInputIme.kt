package io.github.mangi.eta.ui.components

import android.app.Activity
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

internal val LocalChatInputFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

internal suspend fun showChatInputIme(
    requester: FocusRequester?,
    keyboard: SoftwareKeyboardController?,
    view: View,
) {
    repeat(3) {
        withFrameNanos { }
        runCatching { requester?.requestFocus() }
        keyboard?.show()
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
        }
        delay(48)
    }
}

/**
 * Chat action control that never takes text focus, so tapping it cannot dismiss the IME.
 */
@Composable
internal fun ChatInputNonFocusableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .size(ChatInputActionSize)
            .focusProperties { canFocus = false }
            .semantics(mergeDescendants = true) {
                this.role = Role.Button
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
                onClick {
                    TouchHaptics.click(view)
                    onClick()
                    true
                }
            }
            .pointerInput(onClick) {
                detectTapGestures {
                    TouchHaptics.click(view)
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * Capture whether the IME is visible, then restore it after a non-focusable menu window appears.
 */
@Composable
internal fun rememberKeepImeWhenOpeningMenu(): () -> Unit {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = LocalChatInputFocusRequester.current
    val view = LocalView.current
    val restoreToken = remember { mutableIntStateOf(0) }
    val shouldRestore = remember { mutableStateOf(false) }
    val token = restoreToken.intValue
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(token) {
        if (token == 0 || !shouldRestore.value) return@LaunchedEffect
        showChatInputIme(requester, keyboard, view)
    }

    return remember(imeVisible, keyboard, requester, view) {
        {
            shouldRestore.value = imeVisible
            restoreToken.intValue += 1
        }
    }
}
