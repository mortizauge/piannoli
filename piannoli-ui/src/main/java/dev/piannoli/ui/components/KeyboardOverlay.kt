package dev.piannoli.ui.components

import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piannoli.ui.ButtonStyle
import dev.piannoli.ui.KEY_BACKSPACE
import dev.piannoli.ui.KEY_ENTER
import dev.piannoli.ui.KEY_SHIFT
import dev.piannoli.ui.KEY_SPACE
import dev.piannoli.ui.KEY_SYMBOLS
import dev.piannoli.ui.R
import dev.piannoli.ui.theme.LocalPiannoliColors
import dev.piannoli.ui.theme.LocalPiannoliTypography
import dev.piannoli.ui.theme.Radius
import dev.piannoli.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private val KEY_BG = Color.White.copy(alpha = 0.12f)

@Composable
fun KeyboardOverlay(
    state: KeyboardState,
    title: String? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
    masked: Boolean = false,
    onStateChange: ((KeyboardState) -> Unit)? = null,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onHelp: () -> Unit = {},
    useSystemKeyboard: Boolean = false,
) {
    if (useSystemKeyboard && onStateChange != null) {
        SystemKeyboardOverlay(state, title, buttonStyle, masked, onStateChange, onConfirm, onCancel)
        return
    }
    val context = LocalContext.current
    val text = state.text
    val cursorPos = state.cursorPos
    val keyRow = state.keyRow
    val keyCol = state.keyCol
    val caps = state.caps
    val symbols = state.symbols
    val typo = LocalPiannoliTypography.current
    val rows = getKeyboardRows(state.layout, caps, symbols)
    val row = keyRow.coerceIn(0, rows.lastIndex)
    val col = keyCol.coerceIn(0, rows[row].lastIndex)
    val colors = LocalPiannoliColors.current
    val highlight = colors.highlight
    val highlightText = colors.highlightText

    val scrollState = rememberScrollState()
    val rootFocusRequester = remember { FocusRequester() }
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(onStateChange != null) {
        if (onStateChange != null) rootFocusRequester.requestFocus()
    }

    val uiActive = LocalUiActive.current
    val touchRepeat = remember { DeleteKeyRepeat() }
    val touchState = remember { object { var expected = state } }
    SideEffect {
        if (state !== touchState.expected) touchRepeat.stop()
        touchState.expected = state
    }
    DisposableEffect(touchRepeat) {
        onDispose { touchRepeat.stop() }
    }
    val deleteTouch by rememberUpdatedState<() -> Boolean> {
        if (!uiActive.value || state !== touchState.expected || onStateChange == null) {
            false
        } else {
            val next = KeyboardController.backspace(state)
            if (next == state) {
                false
            } else {
                val backspaceRow = rows.indexOfFirst { KEY_BACKSPACE in it }
                val selected = next.copy(keyRow = backspaceRow, keyCol = rows[backspaceRow].indexOf(KEY_BACKSPACE))
                touchState.expected = selected
                onStateChange(selected)
                true
            }
        }
    }
    LaunchedEffect(uiActive) {
        uiActive.collectLatest { focused ->
            if (!focused) {
                touchRepeat.stop()
                return@collectLatest
            }
            cursorVisible = true
            while (true) {
                delay(530)
                cursorVisible = !cursorVisible
            }
        }
    }

    LaunchedEffect(cursorPos, text) {
        cursorVisible = true
        val clamped = cursorPos.coerceIn(0, text.length)
        val target = if (text.isEmpty()) 0
        else (scrollState.maxValue * clamped / (text.length + 1).coerceAtLeast(1))
        scrollState.scrollTo(target.coerceAtLeast(0))
    }

    val safeCursor = cursorPos.coerceIn(0, text.length)
    val shownText = if (masked) "*".repeat(text.length) else text
    val beforeCursor = shownText.substring(0, safeCursor)
    val afterCursor = shownText.substring(safeCursor)
    val cursorChar = if (cursorVisible) "|" else " "
    val displayText = "$beforeCursor$cursorChar$afterCursor"
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pressKey: (Int, Int) -> Unit = { keyRowIndex, keyColIndex ->
        when (val result = KeyboardController.press(state.copy(keyRow = keyRowIndex, keyCol = keyColIndex))) {
            is KeyboardPress.Update -> onStateChange?.invoke(result.state)
            KeyboardPress.Confirm -> onConfirm()
        }
    }
    val insertText: (String) -> Boolean = { inserted ->
        if (state.layout == KeyboardLayout.Number && !inserted.all { it.isDigit() }) {
            false
        } else {
            onStateChange?.invoke(
                state.copy(
                    text = text.substring(0, safeCursor) + inserted + text.substring(safeCursor),
                    cursorPos = safeCursor + inserted.length,
                )
            )
            true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (onStateChange == null) Modifier else Modifier
                    .focusRequester(rootFocusRequester)
                    .onFocusChanged { if (!it.hasFocus) touchRepeat.stop() }
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        handlePhysicalKey(event, state, safeCursor, onStateChange, onConfirm, insertText)
                    }
            )
            .screenBackground()
            .padding(screenPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = footerReservation()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = typo.titleMedium,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.Md))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.Md))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clipToBounds()
                    .horizontalScroll(scrollState)
            ) {
                Text(
                    text = displayText,
                    style = typo.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    onTextLayout = { textLayout = it },
                    modifier = if (onStateChange == null) Modifier else Modifier.pointerInput(state, safeCursor) {
                        detectTapGestures { position ->
                            val offset = textLayout?.getOffsetForPosition(position) ?: return@detectTapGestures
                            val cursor = (if (offset > safeCursor) offset - 1 else offset).coerceIn(0, text.length)
                            onStateChange(state.copy(cursorPos = cursor))
                        }
                    },
                )
            }

            if (onStateChange != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val pasted = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        if (pasted.isNullOrEmpty() || !insertText(pasted)) {
                            Toast.makeText(context, R.string.keyboard_clipboard_unusable, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.label_paste)) }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.Md))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                val maxKeysPerRow = rows.maxOf { it.size }
                val keyGap = 4.dp
                val heightPerRow = ((maxHeight - keyGap * (rows.size - 1) - 4.dp) / rows.size).coerceAtLeast(1.dp)
                val keySize = ((maxWidth - keyGap * (maxKeysPerRow - 1)) / maxKeysPerRow)
                    .coerceAtMost(48.dp).coerceAtMost(heightPerRow)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(keyGap)
            ) {
            rows.forEachIndexed { ri, keys ->
                if (keys.size == 1 && keys[0] == KEY_SPACE) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val isSelected = ri == row
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .height(keySize)
                                .widthIn(max = 400.dp).fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.Md))
                                .background(if (isSelected) highlight else KEY_BG)
                                .clickable(enabled = onStateChange != null) { pressKey(ri, 0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(2.dp)
                                    .background(if (isSelected) highlightText.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f))
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(keyGap, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        keys.forEachIndexed { ci, key ->
                            val isSelected = ri == row && ci == col
                            val isAction = key in listOf(KEY_SHIFT, KEY_ENTER, KEY_BACKSPACE, KEY_SYMBOLS)
                            val isShiftActive = key == KEY_SHIFT && caps

                            val keyBg = when {
                                isSelected -> highlight
                                isShiftActive -> highlight.copy(alpha = 0.5f)
                                else -> KEY_BG
                            }
                            val keyText = if (isSelected) highlightText else Color.White

                            Box(
                                modifier = Modifier
                                    .size(keySize)
                                    .clip(RoundedCornerShape(Radius.Md))
                                    .background(keyBg)
                                    .then(
                                        if (key == KEY_BACKSPACE && onStateChange != null) {
                                            Modifier
                                                .semantics {
                                                    onClick {
                                                        deleteTouch()
                                                        true
                                                    }
                                                }
                                                .pointerInput(uiActive, state.layout) {
                                                    detectTapGestures(onPress = {
                                                        touchRepeat.start { deleteTouch() }
                                                        try {
                                                            tryAwaitRelease()
                                                        } finally {
                                                            touchRepeat.stop()
                                                        }
                                                    })
                                                }
                                        } else {
                                            Modifier.clickable(enabled = onStateChange != null) { pressKey(ri, ci) }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = if (isAction) 18.sp else 16.sp
                                    ),
                                    color = keyText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            }
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf(
                buttonStyle.west to stringResource(R.string.label_cancel),
                buttonStyle.menu to stringResource(R.string.label_help)
            ),
            rightItems = listOf(
                buttonStyle.start to stringResource(R.string.label_confirm)
            ),
            onLeftItemClick = { index -> if (index == 0) onCancel() else onHelp() },
            onRightItemClick = { onConfirm() },
        )
    }
}

private fun handlePhysicalKey(
    event: KeyEvent,
    state: KeyboardState,
    cursor: Int,
    onStateChange: (KeyboardState) -> Unit,
    onConfirm: () -> Unit,
    insertText: (String) -> Boolean,
): Boolean {
    val down = event.type == KeyEventType.KeyDown
    val direction = when (event.key) {
        Key.DirectionUp -> Direction.UP
        Key.DirectionDown -> Direction.DOWN
        Key.DirectionLeft -> Direction.LEFT
        Key.DirectionRight -> Direction.RIGHT
        else -> null
    }
    if (direction != null) {
        if (down) onStateChange(KeyboardController.moveSelection(state, direction))
        return true
    }
    when (event.key) {
        Key.Backspace -> {
            if (down && cursor > 0) {
                onStateChange(state.copy(text = state.text.removeRange(cursor - 1, cursor), cursorPos = cursor - 1))
            }
            return true
        }
        Key.Delete -> {
            if (down && cursor < state.text.length) {
                onStateChange(state.copy(text = state.text.removeRange(cursor, cursor + 1)))
            }
            return true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (down) onConfirm()
            return true
        }
    }
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
    val codePoint = event.utf16CodePoint
    if (codePoint <= 0 || Character.isISOControl(codePoint)) return false
    if (down) insertText(String(Character.toChars(codePoint)))
    return true
}
