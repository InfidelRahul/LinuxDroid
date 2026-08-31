package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
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
    val environment by viewModel.environment.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val isShellActive by viewModel.isShellActive.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val shellExitCode by viewModel.shellExitCode.collectAsState()

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var textInput by remember { mutableStateOf(TextFieldValue("")) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    var showFnRow by remember { mutableStateOf(false) }

    // Scroll to bottom on new output
    LaunchedEffect(lines.size) {
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
                .background(Color(0xFF0C0C0C))
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
                        },
                        label = { Text(cmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF1E1E1E),
                            labelColor = Color(0xFF81C784),
                        ),
                    )
                }
            }

            // Terminal canvas / output stream
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val density = LocalDensity.current
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
                        items(lines) { lineData ->
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

                            Text(
                                text = annotatedString,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
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

                if (!isShellActive && !isStarting && shellExitCode != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF212121),
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Shell exited (code $shellExitCode)",
                                color = Color(0xFFEF5350),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Button(
                                onClick = { viewModel.restartShell() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Restart Shell", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Extra Terminal Control Key Bar
            Surface(
                color = Color(0xFF181818),
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 8.dp
            ) {
                Column {
                    if (showFnRow) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            (1..12).forEach { fNum ->
                                TerminalKeyButton("F$fNum") {
                                    viewModel.sendFunctionKey(fNum)
                                    focusRequester.requestFocus()
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TerminalKeyButton(
                            text = "ESC",
                            onClick = {
                                viewModel.sendEscape()
                                focusRequester.requestFocus()
                            }
                        )

                        TerminalKeyButton(
                            text = "TAB",
                            onClick = {
                                viewModel.sendTab()
                                focusRequester.requestFocus()
                            }
                        )

                        TerminalKeyButton(
                            text = "CTRL",
                            isActive = isCtrlActive,
                            onClick = { isCtrlActive = !isCtrlActive }
                        )

                        TerminalKeyButton(
                            text = "ALT",
                            isActive = isAltActive,
                            onClick = { isAltActive = !isAltActive }
                        )

                        TerminalKeyButton("C-c", highlight = true) {
                            viewModel.sendCtrlC()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("C-d", highlight = true) {
                            viewModel.sendCtrlD()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("C-l") {
                            viewModel.sendCtrlL()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("C-z") {
                            viewModel.sendCtrlZ()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("↑") {
                            viewModel.sendArrowUp()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("↓") {
                            viewModel.sendArrowDown()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("←") {
                            viewModel.sendArrowLeft()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("→") {
                            viewModel.sendArrowRight()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("HOME") {
                            viewModel.sendHome()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("END") {
                            viewModel.sendEnd()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("PGUP") {
                            viewModel.sendPageUp()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("PGDN") {
                            viewModel.sendPageDown()
                            focusRequester.requestFocus()
                        }

                        TerminalKeyButton("FN", isActive = showFnRow) {
                            showFnRow = !showFnRow
                        }
                    }
                }
            }

            // Hidden transparent BasicTextField capturing Android IME keyboard input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
            ) {
                BasicTextField(
                    value = textInput,
                    onValueChange = { newVal ->
                        val oldStr = textInput.text
                        val newStr = newVal.text

                        if (newStr.length > oldStr.length) {
                            val typed = newStr.substring(oldStr.length)
                            if (isCtrlActive) {
                                typed.forEach { ch ->
                                    val upper = ch.uppercaseChar()
                                    if (upper in 'A'..'Z') {
                                        val ctrlCode = (upper.code - 'A'.code + 1).toByte()
                                        viewModel.sendBytes(byteArrayOf(ctrlCode))
                                    } else {
                                        viewModel.sendInput(ch.toString())
                                    }
                                }
                                isCtrlActive = false
                            } else if (isAltActive) {
                                viewModel.sendInput("\u001B$typed")
                                isAltActive = false
                            } else {
                                viewModel.sendInput(typed)
                            }
                        } else if (newStr.length < oldStr.length) {
                            val backspaces = oldStr.length - newStr.length
                            repeat(backspaces) {
                                viewModel.sendBackspace()
                            }
                        }
                        // Reset input field so it always stays ready for keystrokes
                        textInput = TextFieldValue("")
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
        isActive -> Color(0xFF81C784)
        highlight -> Color(0xFF37474F)
        else -> Color(0xFF262626)
    }
    val textColor = when {
        isActive -> Color.Black
        highlight -> Color(0xFF81C784)
        else -> Color(0xFFE0E0E0)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive || highlight) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
