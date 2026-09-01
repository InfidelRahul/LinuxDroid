package com.linuxdroid.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Neumorphic (Soft UI) Color Tokens for Light and Dark Modes.
 */
data class NeuColorScheme(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfacePressed: Color,
    val lightShadow: Color,
    val darkShadow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val primaryAccent: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryAccent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val borderHighlight: Color,
)

val NeuLightColors = NeuColorScheme(
    isDark = false,
    background = Color(0xFFE8EDF5),
    surface = Color(0xFFE8EDF5),
    surfacePressed = Color(0xFFDCE3EE),
    lightShadow = Color(0xFFFFFFFF).copy(alpha = 0.95f),
    darkShadow = Color(0xFFB4C2D8).copy(alpha = 0.75f),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF94A3B8),
    primaryAccent = Color(0xFF2563EB),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondaryAccent = Color(0xFF0D9488),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    error = Color(0xFFDC2626),
    borderHighlight = Color(0xFFFFFFFF).copy(alpha = 0.7f),
)

val NeuDarkColors = NeuColorScheme(
    isDark = true,
    background = Color(0xFF14171E),
    surface = Color(0xFF14171E),
    surfacePressed = Color(0xFF0E1116),
    lightShadow = Color(0xFF252B38).copy(alpha = 0.85f),
    darkShadow = Color(0xFF07090C).copy(alpha = 0.95f),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    primaryAccent = Color(0xFF60A5FA),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFBFDBFE),
    secondaryAccent = Color(0xFF2DD4BF),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    borderHighlight = Color(0xFF333B4D).copy(alpha = 0.5f),
)

val LocalNeuColors = staticCompositionLocalOf { NeuLightColors }

object NeuTheme {
    val colors: NeuColorScheme
        @Composable
        get() = LocalNeuColors.current
}

/**
 * Modifier that renders dual Neumorphic soft shadows (Top-Left Highlight + Bottom-Right Drop Shadow).
 */
fun Modifier.neuShadow(
    shape: Shape = RoundedCornerShape(16.dp),
    elevation: Dp = 6.dp,
    lightColor: Color? = null,
    darkColor: Color? = null,
    isPressed: Boolean = false,
): Modifier = this
    .shadow(
        elevation = if (isPressed) 1.dp else elevation,
        shape = shape,
        ambientColor = darkColor ?: Color.Black.copy(alpha = 0.25f),
        spotColor = darkColor ?: Color.Black.copy(alpha = 0.35f),
    )
    .drawBehind {
        // Draw top-left ambient light highlight
        val strokeWidth = 1.dp.toPx()
        val highlightColor = lightColor ?: Color.White.copy(alpha = 0.4f)
        if (!isPressed) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(highlightColor, Color.Transparent),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.4f, size.height * 0.4f)
                )
            )
        }
    }

/**
 * Neumorphic Card container.
 */
@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 6.dp,
    isInset: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val neuColors = NeuTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val activePressed = isPressed || isInset

    val currentElevation by animateDpAsState(
        targetValue = if (activePressed) 1.dp else elevation,
        animationSpec = tween(durationMillis = 150),
        label = "neuCardElevation"
    )

    val currentBg by animateColorAsState(
        targetValue = if (activePressed) neuColors.surfacePressed else neuColors.surface,
        animationSpec = tween(durationMillis = 150),
        label = "neuCardBg"
    )

    val clickableMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else Modifier

    Surface(
        modifier = modifier
            .neuShadow(
                shape = shape,
                elevation = currentElevation,
                lightColor = neuColors.lightShadow,
                darkColor = neuColors.darkShadow,
                isPressed = activePressed,
            )
            .clip(shape)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        neuColors.borderHighlight,
                        neuColors.darkShadow.copy(alpha = 0.2f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = shape
            )
            .then(clickableMod),
        shape = shape,
        color = currentBg,
    ) {
        Column(content = content)
    }
}

/**
 * Tactile Neumorphic Button with realistic concave/convex state transitions.
 */
@Composable
fun NeuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    elevation: Dp = 5.dp,
    isAccent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val neuColors = NeuTheme.colors
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val currentElevation by animateDpAsState(
        targetValue = if (isPressed) 1.dp else elevation,
        animationSpec = tween(durationMillis = 120),
        label = "neuBtnElevation"
    )

    val baseBg = when {
        !enabled -> neuColors.surfacePressed.copy(alpha = 0.5f)
        isAccent -> neuColors.primaryAccent
        isPressed -> neuColors.surfacePressed
        else -> neuColors.surface
    }

    val contentColor = when {
        !enabled -> neuColors.textMuted
        isAccent -> if (neuColors.isDark) Color(0xFF0F172A) else Color.White
        else -> neuColors.primaryAccent
    }

    Surface(
        modifier = modifier
            .neuShadow(
                shape = shape,
                elevation = if (enabled) currentElevation else 0.dp,
                lightColor = neuColors.lightShadow,
                darkColor = neuColors.darkShadow,
                isPressed = isPressed || !enabled,
            )
            .clip(shape)
            .border(
                width = 1.dp,
                color = if (isAccent) neuColors.primaryAccent.copy(alpha = 0.3f) else neuColors.borderHighlight,
                shape = shape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        shape = shape,
        color = baseBg,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                modifier = Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * Neumorphic Icon Button (Round or Rounded Box).
 */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    shape: Shape = CircleShape,
    elevation: Dp = 4.dp,
    tint: Color = NeuTheme.colors.textPrimary,
    content: @Composable () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val currentElevation by animateDpAsState(
        targetValue = if (isPressed) 1.dp else elevation,
        animationSpec = tween(durationMillis = 120),
        label = "neuIconElevation"
    )

    Surface(
        modifier = modifier
            .size(size)
            .neuShadow(
                shape = shape,
                elevation = if (enabled) currentElevation else 0.dp,
                lightColor = neuColors.lightShadow,
                darkColor = neuColors.darkShadow,
                isPressed = isPressed || !enabled,
            )
            .clip(shape)
            .border(width = 1.dp, color = neuColors.borderHighlight, shape = shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        shape = shape,
        color = if (isPressed) neuColors.surfacePressed else neuColors.surface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) tint else neuColors.textMuted) {
                content()
            }
        }
    }
}

/**
 * Neumorphic Segmented Tab Control with smooth inset active pill.
 */
@Composable
fun <T> NeuSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemLabel: (T) -> String = { it.toString() },
    itemIcon: (@Composable (T) -> Unit)? = null,
) {
    val neuColors = NeuTheme.colors
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(width = 1.dp, color = neuColors.borderHighlight.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = neuColors.surfacePressed,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem

                val itemBg by animateColorAsState(
                    targetValue = if (isSelected) neuColors.surface else Color.Transparent,
                    animationSpec = tween(durationMillis = 200),
                    label = "neuSegBg"
                )

                val itemTextColor by animateColorAsState(
                    targetValue = if (isSelected) neuColors.primaryAccent else neuColors.textSecondary,
                    animationSpec = tween(durationMillis = 200),
                    label = "neuSegText"
                )

                val pillModifier = if (isSelected) {
                    Modifier
                        .weight(1f)
                        .neuShadow(
                            shape = RoundedCornerShape(12.dp),
                            elevation = 3.dp,
                            lightColor = neuColors.lightShadow,
                            darkColor = neuColors.darkShadow,
                        )
                        .clip(RoundedCornerShape(12.dp))
                } else {
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                }

                Surface(
                    modifier = pillModifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onItemSelected(item)
                            }
                        }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    color = itemBg,
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (itemIcon != null) {
                            CompositionLocalProvider(LocalContentColor provides itemTextColor) {
                                itemIcon(item)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = itemLabel(item),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                            ),
                            color = itemTextColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * macOS Window Control Traffic Lights (Close: Red, Minimize: Yellow, Maximize: Green).
 */
@Composable
fun MacosTrafficLights(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    onMaximize: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Red / Close
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF5F56))
                .border(width = 0.5.dp, color = Color(0xFFE0443E), shape = CircleShape)
                .clickable(enabled = onClose != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClose?.invoke()
                }
        )

        // Yellow / Minimize
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFBD2E))
                .border(width = 0.5.dp, color = Color(0xFFDEA123), shape = CircleShape)
                .clickable(enabled = onMinimize != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMinimize?.invoke()
                }
        )

        // Green / Maximize / Fullscreen
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFF27C93F))
                .border(width = 0.5.dp, color = Color(0xFF1AAB29), shape = CircleShape)
                .clickable(enabled = onMaximize != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMaximize?.invoke()
                }
        )
    }
}

/**
 * macOS Window Titlebar Header with traffic lights, centered title/badge, and action slot.
 */
@Composable
fun MacosWindowHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeText: String? = null,
    onClose: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    onMaximize: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val neuColors = NeuTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Traffic Lights (Non-shrinkable)
        Row(
            modifier = Modifier.wrapContentWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MacosTrafficLights(
                onClose = onClose,
                onMinimize = onMinimize,
                onMaximize = onMaximize
            )
        }

        // Centered Window Title & Badge with flex weight protection
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                ),
                color = neuColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (badgeText != null) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = badgeText,
                        color = neuColors.primaryAccent,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                    )
                }
            }
            if (subtitle != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = neuColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Right Actions Slot (Non-shrinkable)
        Row(
            modifier = Modifier.wrapContentWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            actions?.invoke(this)
        }
    }
}

