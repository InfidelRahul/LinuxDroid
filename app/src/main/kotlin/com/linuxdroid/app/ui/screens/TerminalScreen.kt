package com.linuxdroid.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.viewmodel.TerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    navController: NavController,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val environment by viewModel.environment.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val isShellActive by viewModel.isShellActive.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val shellExitCode by viewModel.shellExitCode.collectAsState()

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Sentinel " " ensures IME Backspace is reliably detected across all virtual keyboards
    var textInput by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    // Blinking terminal cursor animation
    val infiniteTransition = rememberInfiniteTransition(label = "terminalCursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 500
                0f at 501
                0f at 1000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorAlpha"
    )

    // Detect soft keyboard height to auto-scroll terminal canvas
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    // Auto-scroll to bottom on new output or when keyboard appears
    LaunchedEffect(lines.size, isImeVisible) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    // Auto-focus keyboard on screen load
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val quickCommands = listOf(
        "pwd",
        "ls -la",
        "uname -a",
        "whoami",
        "cat /etc/os-release",
        "df -h",
        "free -m",
        "ps aux",
    )

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = environment?.name ?: "Linux Shell",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (isShellActive) "Interactive Shell (PTY)" else if (isStarting) "Connecting Shell…" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isShellActive) Color(0xFF81C784) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.exportLogs(context) }) {
                        Icon(Icons.Default.Share, contentDescription = "Export Runtime Logs")
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear Terminal")
                    }
                    IconButton(onClick = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Show Keyboard")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding()
                .background(Color(0xFF080C14))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
        ) {
            // Disconnected / Exit status and quick export banner
            if (!isShellActive && !isStarting) {
                Surface(
                    color = Color(0xFF1E293B),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (shellExitCode != null && shellExitCode != 0) "Session exited (code $shellExitCode)" else "Session inactive",
                            color = Color(0xFFF87171),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.exportLogs(context) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Export Logs", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export Log", fontSize = 11.sp)
                            }
                            Button(
                                onClick = { viewModel.restartShell() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Restart", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Restart", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Quick command shortcut chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickCommands.forEach { cmd ->
                    SuggestionChip(
                        onClick = {
                            viewModel.runCommand(cmd)
                            focusRequester.requestFocus()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        label = { Text(cmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF1E293B),
                            labelColor = Color(0xFF38BDF8),
                        ),
                    )
                }
            }

            // Terminal Canvas / Output Stream (Dynamically resizes with IME)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val fontSizeSp = 12.sp
                val lineHeightSp = 16.sp
                val charWidthDp = with(density) { (fontSizeSp * 0.6f).toDp() }
                val lineHeightDp = with(density) { lineHeightSp.toDp() }

                val calculatedCols = (maxWidth / charWidthDp).toInt().coerceIn(20, 240)
                val calculatedRows = (maxHeight / lineHeightDp).toInt().coerceIn(5, 120)

                LaunchedEffect(calculatedRows, calculatedCols) {
                    viewModel.resize(calculatedRows, calculatedCols)
                }

                SelectionContainer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(lines) { index, lineData ->
                            val isLastLine = index == lines.size - 1
                            val annotatedString = buildAnnotatedString {
                                for (span in lineData.spans) {
                                    val spanColor = Color(span.color)
                                    val style = SpanStyle(
                                        color = spanColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = if (span.isBold) FontWeight.Bold else FontWeight.Normal,
                                        textDecoration = if (span.isUnderline) TextDecoration.Underline else TextDecoration.None,
                                    )
                                    withStyle(style) {
                                        append(span.text)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = annotatedString,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                                // Blinking block cursor rendered at active prompt
                                if (isLastLine && isShellActive) {
                                    Spacer(Modifier.width(1.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(7.dp)
                                            .height(14.dp)
                                            .background(
                                                color = Color(0xFF22C55E).copy(alpha = cursorAlpha),
                                                shape = RoundedCornerShape(1.dp)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }

                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }
            }

            // Streamlined, Ergonomic Extra Keyboard Button Bar (Always positioned directly above IME)
            Surface(
                color = Color(0xFF0B111E),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                    ),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TerminalKeyButton(
                        text = "ESC",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.sendEscape()
                            focusRequester.requestFocus()
                        }
                    )

                    TerminalKeyButton(
                        text = "TAB",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.sendTab()
                            focusRequester.requestFocus()
                        }
                    )

                    TerminalKeyButton(
                        text = "CTRL",
                        isActive = isCtrlActive,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isCtrlActive = !isCtrlActive
                        }
                    )

                    TerminalKeyButton(
                        text = "ALT",
                        isActive = isAltActive,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isAltActive = !isAltActive
                        }
                    )

                    TerminalKeyButton("↑") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendArrowUp()
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("↓") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendArrowDown()
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("←") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendArrowLeft()
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("→") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendArrowRight()
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("C-c", highlight = true) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendCtrlC()
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("/") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendInput("/")
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("-") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendInput("-")
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("|") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendInput("|")
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("~") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendInput("~")
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("⌫", highlight = true) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendBackspace()
                        focusRequester.requestFocus()
                    }
                }
            }

            // Hidden Transparent BasicTextField Capturing Virtual / Hardware Keyboard Input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
            ) {
                BasicTextField(
                    value = textInput,
                    onValueChange = { newVal ->
                        val newText = newVal.text
                        if (newText.isEmpty()) {
                            // Backspace pressed on Android soft keyboard
                            viewModel.sendBackspace()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else if (newText.length > 1) {
                            // Typed text extracted around sentinel character
                            val typed = if (newText.startsWith(" ")) {
                                newText.substring(1)
                            } else {
                                newText.replace(" ", "")
                            }

                            for (ch in typed) {
                                if (isCtrlActive) {
                                    val upper = ch.uppercaseChar()
                                    if (upper in 'A'..'Z') {
                                        val ctrlCode = (upper.code - 'A'.code + 1).toByte()
                                        viewModel.sendBytes(byteArrayOf(ctrlCode))
                                    } else if (ch == '[') {
                                        viewModel.sendEscape()
                                    } else {
                                        viewModel.sendInput(ch.toString())
                                    }
                                    isCtrlActive = false
                                } else if (isAltActive) {
                                    viewModel.sendInput("\u001B$ch")
                                    isAltActive = false
                                } else {
                                    viewModel.sendInput(ch.toString())
                                }
                            }
                        }
                        // Reset input field with sentinel character " "
                        textInput = TextFieldValue(" ", selection = TextRange(1))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.Enter -> {
                                        viewModel.sendEnter()
                                        true
                                    }
                                    Key.Backspace -> {
                                        viewModel.sendBackspace()
                                        true
                                    }
                                    Key.Tab -> {
                                        viewModel.sendTab()
                                        true
                                    }
                                    Key.Escape -> {
                                        viewModel.sendEscape()
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        viewModel.sendArrowUp()
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        viewModel.sendArrowDown()
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        viewModel.sendArrowLeft()
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        viewModel.sendArrowRight()
                                        true
                                    }
                                    Key.Delete -> {
                                        viewModel.sendDelete()
                                        true
                                    }
                                    Key.MoveHome -> {
                                        viewModel.sendHome()
                                        true
                                    }
                                    Key.MoveEnd -> {
                                        viewModel.sendEnd()
                                        true
                                    }
                                    Key.PageUp -> {
                                        viewModel.sendPageUp()
                                        true
                                    }
                                    Key.PageDown -> {
                                        viewModel.sendPageDown()
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                    cursorBrush = SolidColor(Color.Transparent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    keyboardActions = KeyboardActions(onAny = { viewModel.sendEnter() })
                )
            }
        }
    }
}

@Composable
private fun TerminalKeyButton(
    text: String,
    isActive: Boolean = false,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val bgColor = when {
        isActive -> Color(0xFF22C55E)
        highlight -> Color(0xFF1E293B)
        else -> Color(0xFF151F30)
    }
    val textColor = when {
        isActive -> Color.Black
        highlight -> Color(0xFF38BDF8)
        else -> Color(0xFFF1F5F9)
    }
    val borderColor = when {
        isActive -> Color(0xFF4ADE80)
        highlight -> Color(0xFF0284C7)
        else -> Color(0xFF2B3A52)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        modifier = Modifier
            .height(36.dp)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(6.dp))
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive || highlight) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
