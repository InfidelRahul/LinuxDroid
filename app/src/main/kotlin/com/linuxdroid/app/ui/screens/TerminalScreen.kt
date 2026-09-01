package com.linuxdroid.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
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
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.TerminalViewModel
import com.linuxdroid.core.model.LogExportType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    navController: NavController,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val neuColors = NeuTheme.colors

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

    // In-terminal search state
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    // Export options dialog
    var showExportOptions by remember { mutableStateOf(false) }

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
        if (lines.isNotEmpty() && !isSearchActive) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    // Auto-focus keyboard on screen load
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Search matches calculation
    val searchMatchLines = remember(lines, searchQuery) {
        if (searchQuery.isNotBlank()) {
            lines.mapIndexedNotNull { index, lineData ->
                if (lineData.rawText.contains(searchQuery, ignoreCase = true)) index else null
            }
        } else {
            emptyList()
        }
    }

    val quickCommands = listOf(
        "ls -la",
        "pwd",
        "uname -a",
        "whoami",
        "cat /etc/os-release",
        "df -h",
        "free -m",
        "ps aux",
        "clear",
    )

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = environment?.name ?: "Terminal",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = neuColors.textPrimary,
                        )
                        Surface(
                            color = if (isShellActive) neuColors.success.copy(alpha = 0.15f) else neuColors.surfacePressed,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isShellActive) neuColors.success.copy(alpha = 0.4f) else neuColors.borderHighlight.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = if (isShellActive) "ONLINE" else if (isStarting) "STARTING" else "OFFLINE",
                                color = if (isShellActive) neuColors.success else if (isStarting) neuColors.warning else neuColors.textMuted,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    NeuIconButton(
                        onClick = { navController.popBackStack() },
                        size = 38.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    }
                },
                actions = {
                    // Search toggle
                    NeuIconButton(
                        onClick = { isSearchActive = !isSearchActive },
                        size = 38.dp,
                        tint = if (isSearchActive) neuColors.primaryAccent else neuColors.textSecondary,
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Find in Terminal", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    // Copy scrollback
                    NeuIconButton(
                        onClick = {
                            val text = viewModel.getTerminalPlainText()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Scrollback", text))
                            Toast.makeText(context, "Terminal output copied", Toast.LENGTH_SHORT).show()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        size = 38.dp,
                        tint = neuColors.textSecondary,
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Output", modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    // Export dialog
                    NeuIconButton(
                        onClick = { showExportOptions = true },
                        size = 38.dp,
                        tint = neuColors.primaryAccent,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export Logs", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    // Virtual keyboard toggle
                    NeuIconButton(
                        onClick = {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        size = 38.dp,
                        tint = neuColors.primaryAccent,
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Show Keyboard", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
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
                .background(neuColors.background)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
        ) {
            // macOS Spotlight-Style In-Terminal Search Bar
            AnimatedVisibility(
                visible = isSearchActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                NeuCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(18.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                currentMatchIndex = 0
                                if (searchMatchLines.isNotEmpty()) {
                                    coroutineScope.launch {
                                        listState.scrollToItem(searchMatchLines[0])
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = neuColors.textPrimary, fontFamily = SfMono),
                            cursorBrush = SolidColor(neuColors.primaryAccent),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text("Find text in terminal...", style = MaterialTheme.typography.bodyMedium, color = neuColors.textMuted)
                                }
                                innerTextField()
                            }
                        )

                        if (searchMatchLines.isNotEmpty()) {
                            Text(
                                text = "${currentMatchIndex + 1}/${searchMatchLines.size}",
                                fontSize = 11.sp,
                                color = neuColors.textSecondary,
                                fontFamily = SfMono
                            )

                            NeuIconButton(
                                onClick = {
                                    if (searchMatchLines.isNotEmpty()) {
                                        currentMatchIndex = (currentMatchIndex - 1 + searchMatchLines.size) % searchMatchLines.size
                                        coroutineScope.launch { listState.scrollToItem(searchMatchLines[currentMatchIndex]) }
                                    }
                                },
                                size = 28.dp,
                                tint = neuColors.textPrimary
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous Match", modifier = Modifier.size(16.dp))
                            }

                            NeuIconButton(
                                onClick = {
                                    if (searchMatchLines.isNotEmpty()) {
                                        currentMatchIndex = (currentMatchIndex + 1) % searchMatchLines.size
                                        coroutineScope.launch { listState.scrollToItem(searchMatchLines[currentMatchIndex]) }
                                    }
                                },
                                size = 28.dp,
                                tint = neuColors.textPrimary
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next Match", modifier = Modifier.size(16.dp))
                            }
                        }

                        NeuIconButton(
                            onClick = {
                                isSearchActive = false
                                searchQuery = ""
                            },
                            size = 28.dp,
                            tint = neuColors.textMuted
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close Search", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Disconnected / Exit status and quick export banner
            if (!isShellActive && !isStarting) {
                NeuCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (shellExitCode != null && shellExitCode != 0) "Session exited (code $shellExitCode)" else "Session inactive",
                            color = neuColors.error,
                            fontSize = 12.sp,
                            fontFamily = SfMono,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NeuButton(
                                onClick = { showExportOptions = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Export Logs", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export", fontSize = 11.sp)
                            }
                            NeuButton(
                                onClick = { viewModel.restartShell() },
                                isAccent = true,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Restart", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Restart", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Export Options Modal Dialog
            if (showExportOptions) {
                AlertDialog(
                    onDismissRequest = { showExportOptions = false },
                    containerColor = neuColors.background,
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = neuColors.primaryAccent)
                            Text("Export Terminal & Logs", color = neuColors.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Choose the export type for the current terminal session and runtime environment:",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary
                            )

                            Spacer(Modifier.height(4.dp))

                            // Primary: Terminal Session & Failure Log
                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    viewModel.exportLogs(context, LogExportType.TERMINAL_FAILURE_LOG, asJson = false)
                                },
                                isAccent = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Terminal Session & Failure Log", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            // Secondary: Compact Failure Report
                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    viewModel.exportLogs(context, LogExportType.FAILURE_REPORT_COMPACT, asJson = false)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Failure Report (Compact)", fontSize = 13.sp)
                            }

                            // Full Raw Logs Archive (.zip)
                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    viewModel.exportLogs(context, LogExportType.FULL_LOGS, asJson = false)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Full Raw Logs Archive (.zip)", fontSize = 13.sp)
                            }

                            // Copy Raw Terminal Scrollback
                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    val text = viewModel.getTerminalPlainText()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Scrollback", text))
                                    Toast.makeText(context, "Terminal scrollback copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy Terminal Scrollback", fontSize = 13.sp)
                            }
                        }
                    },
                    confirmButton = {
                        NeuButton(
                            onClick = { showExportOptions = false },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Quick Command Chips (Spotlight Style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickCommands.forEach { cmd ->
                    NeuButton(
                        onClick = {
                            viewModel.runCommand(cmd)
                            focusRequester.requestFocus()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        shape = RoundedCornerShape(8.dp),
                        elevation = 2.dp,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(cmd, fontSize = 11.sp, fontFamily = SfMono, color = neuColors.primaryAccent)
                    }
                }
            }

            // macOS Window Terminal Card
            NeuCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                isInset = true,
                shape = RoundedCornerShape(14.dp),
                elevation = 3.dp
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
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

                    Column(modifier = Modifier.fillMaxSize()) {
                        // macOS Terminal Titlebar inside Window
                        MacosWindowHeader(
                            title = environment?.name ?: "bash",
                            badgeText = "${calculatedCols}x${calculatedRows}",
                            subtitle = "pts/0",
                            onClose = { navController.popBackStack() },
                            onMinimize = { viewModel.clear() },
                            onMaximize = {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            },
                            actions = {
                                NeuIconButton(
                                    onClick = { viewModel.clear() },
                                    size = 24.dp,
                                    tint = neuColors.textMuted
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(12.dp))
                                }
                            }
                        )

                        HorizontalDivider(
                            color = neuColors.borderHighlight.copy(alpha = 0.25f),
                            thickness = 0.5.dp
                        )

                        SelectionContainer(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                itemsIndexed(lines) { index, lineData ->
                                    val isLastLine = index == lines.size - 1
                                    val hasSearchMatch = searchQuery.isNotBlank() && lineData.rawText.contains(searchQuery, ignoreCase = true)

                                    val annotatedString = buildAnnotatedString {
                                        for (span in lineData.spans) {
                                            val spanColor = getAdaptiveTerminalColor(
                                                rawColor = span.color,
                                                isDarkTheme = neuColors.isDark,
                                                textPrimary = neuColors.textPrimary
                                            )
                                            val style = SpanStyle(
                                                color = spanColor,
                                                fontFamily = SfMono,
                                                fontSize = 12.sp,
                                                fontWeight = if (span.isBold) FontWeight.Bold else FontWeight.Normal,
                                                textDecoration = if (span.isUnderline) TextDecoration.Underline else TextDecoration.None,
                                                background = if (hasSearchMatch) neuColors.warning.copy(alpha = 0.25f) else Color.Transparent
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
                                            fontFamily = SfMono,
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
                                                        color = (if (neuColors.isDark) Color(0xFF22C55E) else neuColors.primaryAccent).copy(alpha = cursorAlpha),
                                                        shape = RoundedCornerShape(1.dp)
                                                    )
                                            )
                                        }
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
                            color = neuColors.primaryAccent,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // macOS TouchBar / Ergonomic Extra Keyboard Button Strip
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp,
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        showLed = true,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isCtrlActive = !isCtrlActive
                        }
                    )

                    TerminalKeyButton(
                        text = "ALT",
                        isActive = isAltActive,
                        showLed = true,
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

                    TerminalKeyButton("C-d") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendBytes(byteArrayOf(4)) // EOF
                        focusRequester.requestFocus()
                    }

                    TerminalKeyButton("C-z") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendBytes(byteArrayOf(26)) // SIGTSTP
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

                    TerminalKeyButton("$") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.sendInput("$")
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
    showLed: Boolean = false,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    NeuButton(
        onClick = onClick,
        isAccent = isActive || highlight,
        elevation = if (isActive) 1.dp else 3.dp,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showLed) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF22C55E) else neuColors.textMuted.copy(alpha = 0.4f))
                )
            }
            Text(
                text = text,
                fontSize = 12.sp,
                fontFamily = SfMono,
                fontWeight = if (isActive || highlight) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) Color.White else if (highlight) neuColors.primaryAccent else neuColors.textPrimary
            )
        }
    }
}

/**
 * Maps raw ANSI terminal colors to high-contrast legible palette based on Light/Dark theme.
 */
private fun getAdaptiveTerminalColor(rawColor: Long, isDarkTheme: Boolean, textPrimary: Color): Color {
    return if (isDarkTheme) {
        when (rawColor) {
            0xFF1E1E1E, 0xFF000000 -> Color(0xFF94A3B8)
            0xFFE0E0E0 -> Color(0xFFF1F5F9)
            else -> Color(rawColor)
        }
    } else {
        when (rawColor) {
            0xFFE0E0E0, 0xFFFFFFFF, 0xFFF1F5F9 -> textPrimary
            0xFF757575, 0xFF1E1E1E, 0xFF000000 -> Color(0xFF0F172A)
            0xFFE57373, 0xFFFF8A80 -> Color(0xFFDC2626) // Crisp Red
            0xFF81C784, 0xFFA5D6A7 -> Color(0xFF15803D) // Crisp Green
            0xFFFFD54F, 0xFFFFE082 -> Color(0xFFB45309) // Crisp Amber/Yellow
            0xFF64B5F6, 0xFF90CAF9 -> Color(0xFF1D4ED8) // Crisp Blue
            0xFFBA68C8, 0xFFCE93D8 -> Color(0xFF7E22CE) // Crisp Magenta/Purple
            0xFF4DD0E1, 0xFF80DEEA -> Color(0xFF0E7490) // Crisp Cyan/Teal
            else -> {
                val c = Color(rawColor)
                val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
                if (luminance > 0.65f) textPrimary else c
            }
        }
    }
}


