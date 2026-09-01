package com.linuxdroid.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linuxdroid.app.ui.theme.NeuTheme
import com.linuxdroid.core.model.Distribution

/**
 * Reusable square box showing the authentic installed OS distribution logo.
 */
@Composable
fun DistroIcon(
    distribution: Distribution,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val neuColors = NeuTheme.colors
    val (bgColor, brandColor) = when (distribution) {
        Distribution.DEBIAN -> Color(0xFFD70A53) to Color(0xFFFF4081)
        Distribution.UBUNTU -> Color(0xFFE95420) to Color(0xFFFF7043)
        Distribution.KALI -> Color(0xFF2C3E50) to Color(0xFF3498DB)
        Distribution.ARCH_LINUX -> Color(0xFF1793D1) to Color(0xFF4FC3F7)
        Distribution.ALPINE -> Color(0xFF0D597F) to Color(0xFF29B6F6)
    }

    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = neuColors.surfacePressed,
        border = androidx.compose.foundation.BorderStroke(1.dp, brandColor.copy(alpha = 0.45f)),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size * 0.65f)) {
                val canvasWidth = size.toPx() * 0.65f
                val canvasHeight = size.toPx() * 0.65f
                when (distribution) {
                    Distribution.DEBIAN -> drawDebianLogo(bgColor, canvasWidth, canvasHeight)
                    Distribution.UBUNTU -> drawUbuntuLogo(bgColor, canvasWidth, canvasHeight)
                    Distribution.KALI -> drawKaliLogo(brandColor, canvasWidth, canvasHeight)
                    Distribution.ARCH_LINUX -> drawArchLogo(brandColor, canvasWidth, canvasHeight)
                    Distribution.ALPINE -> drawAlpineLogo(brandColor, canvasWidth, canvasHeight)
                }
            }
        }
    }
}

private fun DrawScope.drawDebianLogo(color: Color, width: Float, height: Float) {
    val cx = width / 2f
    val cy = height / 2f
    val path = Path().apply {
        moveTo(cx + width * 0.05f, cy + height * 0.35f)
        cubicTo(
            cx - width * 0.35f, cy + height * 0.35f,
            cx - width * 0.42f, cy - height * 0.15f,
            cx - width * 0.15f, cy - height * 0.35f
        )
        cubicTo(
            cx + width * 0.15f, cy - height * 0.45f,
            cx + width * 0.42f, cy - height * 0.22f,
            cx + width * 0.38f, cy + height * 0.10f
        )
        cubicTo(
            cx + width * 0.34f, cy + height * 0.30f,
            cx + width * 0.12f, cy + height * 0.32f,
            cx - width * 0.05f, cy + height * 0.18f
        )
        cubicTo(
            cx - width * 0.18f, cy + height * 0.05f,
            cx - width * 0.12f, cy - height * 0.18f,
            cx + width * 0.05f, cy - height * 0.15f
        )
        cubicTo(
            cx + width * 0.15f, cy - height * 0.12f,
            cx + width * 0.18f, cy + height * 0.02f,
            cx + width * 0.08f, cy + height * 0.08f
        )
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = width * 0.14f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawUbuntuLogo(color: Color, width: Float, height: Float) {
    val cx = width / 2f
    val cy = height / 2f
    val r = width * 0.34f
    val strokeW = width * 0.10f
    val gap = 24f
    val sweep = (360f - 3f * gap) / 3f

    for (i in 0..2) {
        val startAngle = i * (sweep + gap) + gap / 2f
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
        val dotAngleRad = Math.toRadians((i * (sweep + gap) + sweep / 2f + gap / 2f).toDouble())
        val dotR = r * 1.25f
        val dotX = cx + (dotR * Math.cos(dotAngleRad)).toFloat()
        val dotY = cy + (dotR * Math.sin(dotAngleRad)).toFloat()
        drawCircle(
            color = color,
            radius = width * 0.07f,
            center = Offset(dotX, dotY)
        )
    }
}

private fun DrawScope.drawKaliLogo(color: Color, width: Float, height: Float) {
    val cx = width / 2f
    val cy = height / 2f
    val path = Path().apply {
        moveTo(cx, cy - height * 0.42f)
        cubicTo(cx + width * 0.38f, cy - height * 0.35f, cx + width * 0.42f, cy + height * 0.05f, cx, cy + height * 0.42f)
        cubicTo(cx - width * 0.42f, cy + height * 0.05f, cx - width * 0.38f, cy - height * 0.35f, cx, cy - height * 0.42f)
        close()
    }
    drawPath(path = path, color = color.copy(alpha = 0.25f))
    drawPath(path = path, color = color, style = Stroke(width = width * 0.08f))
    val dragonPath = Path().apply {
        moveTo(cx - width * 0.15f, cy + height * 0.15f)
        lineTo(cx + width * 0.10f, cy - height * 0.15f)
        lineTo(cx - width * 0.02f, cy - height * 0.12f)
        lineTo(cx + width * 0.15f, cy - height * 0.25f)
    }
    drawPath(path = dragonPath, color = color, style = Stroke(width = width * 0.07f, cap = StrokeCap.Round))
}

private fun DrawScope.drawArchLogo(color: Color, width: Float, height: Float) {
    val cx = width / 2f
    val cy = height / 2f
    val path = Path().apply {
        moveTo(cx, cy - height * 0.42f)
        lineTo(cx + width * 0.40f, cy + height * 0.38f)
        cubicTo(cx + width * 0.15f, cy + height * 0.25f, cx - width * 0.15f, cy + height * 0.25f, cx - width * 0.40f, cy + height * 0.38f)
        close()
    }
    drawPath(path = path, color = color)
    val innerPath = Path().apply {
        moveTo(cx, cy - height * 0.15f)
        lineTo(cx + width * 0.22f, cy + height * 0.32f)
        lineTo(cx - width * 0.22f, cy + height * 0.32f)
        close()
    }
    drawPath(path = innerPath, color = Color(0xFF1E222B))
}

private fun DrawScope.drawAlpineLogo(color: Color, width: Float, height: Float) {
    val cx = width / 2f
    val cy = height / 2f
    val path1 = Path().apply {
        moveTo(cx - width * 0.05f, cy - height * 0.38f)
        lineTo(cx + width * 0.42f, cy + height * 0.38f)
        lineTo(cx - width * 0.42f, cy + height * 0.38f)
        close()
    }
    drawPath(path = path1, color = color)
    val snowPath = Path().apply {
        moveTo(cx - width * 0.05f, cy - height * 0.38f)
        lineTo(cx + width * 0.12f, cy - height * 0.08f)
        lineTo(cx + width * 0.02f, cy - height * 0.05f)
        lineTo(cx - width * 0.08f, cy - height * 0.08f)
        lineTo(cx - width * 0.22f, cy - height * 0.08f)
        close()
    }
    drawPath(path = snowPath, color = Color.White)
}

