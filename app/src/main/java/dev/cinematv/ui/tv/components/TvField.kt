package dev.cinematv.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cinematv.ui.tv.design.TvColors
import dev.cinematv.ui.tv.design.TvHeight
import dev.cinematv.ui.tv.design.TvRadius
import dev.cinematv.ui.tv.design.TvSpace
import dev.cinematv.ui.typography.CinemaTvText as Text
import dev.cinematv.ui.typography.cinemaUiFontFamily

/**
 * TV text entry with an explicit edit step. D-pad focus stays on the field shell, so
 * merely navigating onto it never raises the IME; OK enters editing, Back leaves it.
 * The caller still guards blank submits at the action button and the callback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    shellModifier: Modifier = Modifier,
    isPassword: Boolean = false,
    onSubmit: () -> Unit = {},
    enabled: Boolean = true,
    error: String? = null,
    tag: String = "tv-field",
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    val shell = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun leaveEdit() {
        editing = false
        keyboard?.hide()
        runCatching { shell.requestFocus() }
    }

    fun submit() {
        if (!enabled) return
        // An empty query is not a search. Keep editing so the user can correct it.
        if (value.isBlank()) return
        onSubmit()
        leaveEdit()
    }

    LaunchedEffect(editing) {
        if (editing) {
            // Compose has to switch readOnly before the IME attaches.
            withFrameNanos { }
            runCatching { input.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            editing = false
            keyboard?.hide()
        }
    }
    // A system IME Back hides the keyboard but may not reach the field. Leave edit
    // mode as soon as the IME disappears, so the next Back is a route Back.
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) {
            imeWasVisible = false
            editing = false
        }
    }

    Column(modifier) {
        Box(
            shellModifier.fillMaxWidth().height(TvHeight.field)
                .clip(RoundedCornerShape(TvRadius.control))
                .background(TvColors.Surface.copy(alpha = if (enabled) .88f else .45f))
                .border(1.5.dp,
                    when {
                        !enabled -> TvColors.Secondary.copy(alpha = .2f)
                        error != null -> TvColors.Danger
                        focused || fieldFocused -> TvColors.Accent
                        else -> TvColors.Secondary.copy(alpha = .28f)
                    },
                    RoundedCornerShape(TvRadius.control))
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .testTag("$tag-shell")
                .focusRequester(shell)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    // Moving focus to the inner editable field keeps hasFocus true.
                    if (!state.hasFocus) {
                        editing = false
                        keyboard?.hide()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (!enabled || event.type != KeyEventType.KeyDown) false
                    else when {
                        !editing && (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                            editing = true; true
                        }
                        editing && event.key == Key.Back -> {
                            // Consume only while the IME was actually visible. After
                            // the keyboard is gone, let the route owner handle Back.
                            val consume = imeVisible
                            leaveEdit()
                            consume
                        }
                        else -> false
                    }
                }
                .focusable(enabled),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                readOnly = !editing,
                singleLine = true,
                textStyle = TextStyle(color = TvColors.Text, fontSize = 18.sp,
                    fontFamily = cinemaUiFontFamily()),
                cursorBrush = SolidColor(TvColors.Accent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(hint, color = TvColors.Secondary.copy(alpha = .8f), fontSize = 17.sp)
                    inner()
                },
                modifier = Modifier.fillMaxWidth().testTag("$tag-input")
                    .focusRequester(input)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { state ->
                        fieldFocused = state.isFocused
                        if (state.isFocused) editing = true
                    }
                    .onPreviewKeyEvent { event ->
                        if (enabled && editing && event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            val consume = imeVisible
                            leaveEdit()
                            consume
                        } else false
                    },
            )
        }
        if (error != null) {
            Text(error, color = TvColors.Danger, fontSize = 12.sp,
                modifier = Modifier.padding(start = TvSpace.sm, top = TvSpace.xxs))
        }
    }
}
