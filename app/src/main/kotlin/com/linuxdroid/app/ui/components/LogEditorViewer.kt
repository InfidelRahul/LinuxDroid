package com.linuxdroid.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linuxdroid.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun LogEditorViewerDialog(
    title: String,
    logContent: String,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val neuColors = NeuTheme.colors
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val lines = remember(logContent) {
        logContent.lines().ifEmpty { listOf("No log output available.") }
    }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    val searchMatchLines = remember(searchQuery, lines) {
        if (searchQuery.isBlank()) emptyList()
        else lines.mapIndexedNotNull { index, line ->
            if (line.contains(searchQuery, ignoreCase = true)) index else null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            NeuCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f),
                elevation = 12.dp,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // macOS Window Title Header
                    MacosWindowHeader(
                        title = title,
                        badgeText = "${lines.size} lines",
                        subtitle = "Editor View",
                        onClose = onDismiss,
                        actions = {
                            Row(
                                modifier = Modifier.wrapContentWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NeuIconButton(
                                    onClick = { isSearchActive = !isSearchActive },
                                    size = 28.dp,
                                    tint = if (isSearchActive) neuColors.primaryAccent else neuColors.textMuted
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(15.dp))
                                }
                                NeuIconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(title, logContent))
                                        Toast.makeText(context, "Copied ${lines.size} lines to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    size = 28.dp,
                                    tint = neuColors.primaryAccent
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy All", modifier = Modifier.size(15.dp))
                                }
                                if (onShare != null) {
                                    NeuIconButton(
                                        onClick = onShare,
                                        size = 28.dp,
                                        tint = neuColors.textPrimary
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    )

                    HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.25f), thickness = 0.5.dp)

                    // In-Editor Spotlight Search Bar
                    if (isSearchActive) {
                        Surface(
                            color = neuColors.surfacePressed,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = neuColors.primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )

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
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = neuColors.textPrimary,
                                        fontFamily = SfMono,
                                        fontSize = 12.sp,
                                    ),
                                    cursorBrush = SolidColor(neuColors.primaryAccent),
                                    singleLine = true,
                                    decorationBox = { innerTextField ->
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                "Search log...",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                                color = neuColors.textMuted,
                                                maxLines = 1,
                                            )
                                        }
                                        innerTextField()
                                    }
                                )

                                if (searchMatchLines.isNotEmpty()) {
                                    Text(
                                        text = "${currentMatchIndex + 1}/${searchMatchLines.size}",
                                        fontSize = 10.sp,
                                        color = neuColors.textSecondary,
                                        fontFamily = SfMono,
                                        maxLines = 1,
                                    )

                                    NeuIconButton(
                                        onClick = {
                                            if (searchMatchLines.isNotEmpty()) {
                                                currentMatchIndex = (currentMatchIndex - 1 + searchMatchLines.size) % searchMatchLines.size
                                                coroutineScope.launch { listState.scrollToItem(searchMatchLines[currentMatchIndex]) }
                                            }
                                        },
                                        size = 26.dp,
                                        tint = neuColors.textPrimary
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Prev", modifier = Modifier.size(14.dp))
                                    }

                                    NeuIconButton(
                                        onClick = {
                                            if (searchMatchLines.isNotEmpty()) {
                                                currentMatchIndex = (currentMatchIndex + 1) % searchMatchLines.size
                                                coroutineScope.launch { listState.scrollToItem(searchMatchLines[currentMatchIndex]) }
                                            }
                                        },
                                        size = 26.dp,
                                        tint = neuColors.textPrimary
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next", modifier = Modifier.size(14.dp))
                                    }
                                }

                                NeuIconButton(
                                    onClick = {
                                        isSearchActive = false
                                        searchQuery = ""
                                    },
                                    size = 26.dp,
                                    tint = neuColors.textMuted
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close Search", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.2f), thickness = 0.5.dp)
                    }

                    // Editor Gutter & Code Area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(if (neuColors.isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC))
                    ) {
                        SelectionContainer(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(lines) { lineIndex, lineText ->
                                    val lineNumber = lineIndex + 1
                                    val isMatch = searchQuery.isNotBlank() && lineText.contains(searchQuery, ignoreCase = true)
                                    val isCurrentFocusMatch = isMatch && searchMatchLines.getOrNull(currentMatchIndex) == lineIndex

                                    val syntaxColor = getLogSyntaxColor(lineText, neuColors.isDark, neuColors.textPrimary)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                when {
                                                    isCurrentFocusMatch -> neuColors.primaryAccent.copy(alpha = 0.25f)
                                                    isMatch -> neuColors.warning.copy(alpha = 0.18f)
                                                    lineIndex % 2 == 1 -> (if (neuColors.isDark) Color.White else Color.Black).copy(alpha = 0.02f)
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        // Left Line Number Gutter
                                        Box(
                                            modifier = Modifier
                                                .width(44.dp)
                                                .padding(end = 8.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Text(
                                                text = "$lineNumber",
                                                fontFamily = SfMono,
                                                fontSize = 11.sp,
                                                color = if (isMatch) neuColors.primaryAccent else neuColors.textMuted.copy(alpha = 0.6f),
                                                fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }

                                        // Gutter Divider Line
                                        Box(
                                            modifier = Modifier
                                                .width(1.dp)
                                                .height(18.dp)
                                                .background(neuColors.borderHighlight.copy(alpha = 0.25f))
                                        )

                                        Spacer(Modifier.width(8.dp))

                                        // Right Log Code Line
                                        val annotatedText = buildAnnotatedString {
                                            if (searchQuery.isNotBlank() && lineText.contains(searchQuery, ignoreCase = true)) {
                                                var startIndex = 0
                                                val query = searchQuery.lowercase()
                                                val lowerLine = lineText.lowercase()

                                                while (startIndex < lineText.length) {
                                                    val matchPos = lowerLine.indexOf(query, startIndex)
                                                    if (matchPos == -1) {
                                                        append(lineText.substring(startIndex))
                                                        break
                                                    }
                                                    if (matchPos > startIndex) {
                                                        append(lineText.substring(startIndex, matchPos))
                                                    }
                                                    withStyle(
                                                        SpanStyle(
                                                            background = Color(0xFFFBBF24).copy(alpha = 0.45f),
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (neuColors.isDark) Color.White else Color.Black
                                                        )
                                                    ) {
                                                        append(lineText.substring(matchPos, matchPos + query.length))
                                                    }
                                                    startIndex = matchPos + query.length
                                                }
                                            } else {
                                                append(lineText)
                                            }
                                        }

                                        Text(
                                            text = annotatedText,
                                            fontFamily = SfMono,
                                            fontSize = 11.5.sp,
                                            lineHeight = 16.sp,
                                            color = syntaxColor,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.2f), thickness = 0.5.dp)

                    // Footer Status Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(neuColors.surface)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Syntax Legend Pills
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (neuColors.isDark) Color(0xFFF87171) else Color(0xFFDC2626)))
                                Text("Error", fontSize = 10.sp, color = neuColors.textSecondary, fontFamily = SfMono)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (neuColors.isDark) Color(0xFFFBBF24) else Color(0xFFB45309)))
                                Text("Warn", fontSize = 10.sp, color = neuColors.textSecondary, fontFamily = SfMono)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (neuColors.isDark) Color(0xFF4ADE80) else Color(0xFF15803D)))
                                Text("OK", fontSize = 10.sp, color = neuColors.textSecondary, fontFamily = SfMono)
                            }
                        }

                        NeuButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            elevation = 2.dp
                        ) {
                            Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun getLogSyntaxColor(line: String, isDark: Boolean, defaultColor: Color): Color {
    val upper = line.uppercase()

    // 1. Critical Errors / Failures -> Red
    if (upper.contains("[ERROR]") || upper.contains("[FATAL]") || upper.contains("[FAIL]") ||
        upper.contains("[SECCOMP_FILTER_FAIL]") || upper.contains("[EXECVE_KERNEL_FAIL]") ||
        upper.contains("EXCEPTION:") || upper.contains("FAILED:") || upper.contains("ERROR:") ||
        upper.contains("EACCES") || upper.contains("EFAULT") || upper.contains("EINVAL") ||
        upper.contains("ENOENT") || upper.contains("EPERM") || upper.contains("ENOSYS") ||
        upper.contains("SIGSEGV") || upper.contains("SIGSYS") || upper.contains("SIGBUS") ||
        upper.contains("SIGKILL") || Regex("EXITED WITH STATUS [1-9]").containsMatchIn(upper) ||
        Regex("STATUS=-?\\d+").containsMatchIn(upper) && !upper.contains("STATUS=0")
    ) {
        return if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    }

    // 2. Warnings / Validation Notices -> Yellow / Amber
    if (upper.contains("[WARN]") || upper.contains("[WARNING]") || upper.contains("[CONFIG]") ||
        upper.contains("[VERIFY]") || upper.contains("[VALIDATE]") || upper.contains("[PROMOTE]") ||
        upper.contains("DEPRECATED") || upper.contains("CAUTION")
    ) {
        return if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
    }

    // 3. Success / OK / Pass -> Green
    if (upper.contains("[OK]") || upper.contains("[PASS]") || upper.contains("[SUCCESS]") ||
        upper.contains("[READY]") || upper.contains("[EXECVE_PATH_OK]") ||
        upper.contains("[LOAD_INFO_OK]") || upper.contains("[INTERP_OK]") ||
        upper.contains("[LOADER_PATH_OK]") || upper.contains("[EXECVE_KERNEL_OK]") ||
        upper.contains("[LOAD_SCRIPT_OK]") || upper.contains("EXITED WITH STATUS 0") ||
        upper.contains("STATUS=0") || upper.contains("NOMINAL")
    ) {
        return if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
    }

    // 4. Info / Traces / Metadata -> Cyan / Blue
    if (upper.contains("[INFO]") || upper.contains("[DEBUG]") || upper.contains("[INIT]") ||
        upper.contains("[DOWNLOAD]") || upper.contains("[EXTRACT]") || upper.contains("[SOURCE]") ||
        upper.contains("[EXECVE_ENTER]") || upper.contains("CHAIN:") || upper.contains("PID=")
    ) {
        return if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    }

    return defaultColor
}
