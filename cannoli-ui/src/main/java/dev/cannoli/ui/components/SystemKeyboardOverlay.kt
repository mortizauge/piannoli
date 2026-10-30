package dev.cannoli.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.R
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

@Composable
internal fun SystemKeyboardOverlay(
    state: KeyboardState,
    title: String?,
    buttonStyle: ButtonStyle,
    masked: Boolean,
    onStateChange: (KeyboardState) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = LocalCannoliColors.current
    val typography = LocalCannoliTypography.current
    var fieldValue by remember { mutableStateOf(TextFieldValue(state.text, TextRange(state.cursorPos))) }
    val value = if (fieldValue.text == state.text && fieldValue.selection.end == state.cursorPos) fieldValue
        else TextFieldValue(state.text, TextRange(state.cursorPos.coerceIn(0, state.text.length)))

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    DisposableEffect(keyboard) {
        onDispose { keyboard?.hide() }
    }
    ScreenBackground {
        Box(Modifier.fillMaxSize().imePadding().padding(screenPadding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(bottom = footerReservation()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md, Alignment.CenterVertically),
            ) {
                if (title != null) Text(title, style = typography.titleMedium, color = colors.text)
                BasicTextField(
                    value = value,
                    onValueChange = { edited ->
                        if (state.layout != KeyboardLayout.Number || edited.text.all { it.isDigit() }) {
                            fieldValue = edited
                            onStateChange(state.copy(text = edited.text, cursorPos = edited.selection.end))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = typography.bodyLarge.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    singleLine = true,
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = when {
                            masked -> KeyboardType.Password
                            state.layout == KeyboardLayout.Number -> KeyboardType.Number
                            else -> KeyboardType.Text
                        },
                        imeAction = ImeAction.Done,
                        showKeyboardOnFocus = true,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                )
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_cancel)),
                rightItems = listOf(buttonStyle.start to stringResource(R.string.label_confirm)),
                onLeftItemClick = { onCancel() },
                onRightItemClick = { onConfirm() },
            )
        }
    }
}
