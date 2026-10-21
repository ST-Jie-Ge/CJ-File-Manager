package com.cj.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 统一 Compose 表单里的滑块外观。
 *
 * Material 3 默认的粗轨道和分段留白在紧凑表单中显得笨重；这里仍复用 Slider 的手势、
 * 无障碍和键盘行为，只把可见部分换成细轨道与有白色核心的胶囊拇指。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    inactiveTrack: Color,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val fraction = if (valueRange.endInclusive == valueRange.start) 0f else
        ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    // 轨道、胶囊和白色核心全部在同一个绘制面使用同一个 centerY。
    // Material Slider 只保留透明的手势/语义层，不再分别摆放两种可见组件。
    Box(
        modifier = modifier.fillMaxWidth().height(34.dp).drawBehind {
            val centerY = size.height / 2f
            val halfWidth = minOf(11.dp.toPx(), size.width / 2f)
            val halfHeight = 6.dp.toPx()
            val startX = halfWidth
            val endX = (size.width - halfWidth).coerceAtLeast(startX)
            val thumbX = startX + (endX - startX) * fraction
            val railHeight = 4.dp.toPx()
            val railTop = centerY - railHeight / 2f
            drawRoundRect(inactiveTrack, Offset(startX, railTop), Size(endX - startX, railHeight), CornerRadius(railHeight / 2f))
            drawRoundRect(accent, Offset(startX, railTop), Size(thumbX - startX, railHeight), CornerRadius(railHeight / 2f))
            drawRoundRect(accent, Offset(thumbX - halfWidth, centerY - halfHeight), Size(halfWidth * 2f, halfHeight * 2f), CornerRadius(halfHeight))
            val border = 1.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(thumbX - halfWidth + border / 2f, centerY - halfHeight + border / 2f), Size((halfWidth * 2f - border).coerceAtLeast(0f), halfHeight * 2f - border), CornerRadius(halfHeight - border / 2f), style = Stroke(border))
            drawCircle(Color.White.copy(alpha = 0.9f), 2.dp.toPx(), Offset(thumbX, centerY))
        },
        contentAlignment = Alignment.Center,
    ) {
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color.Transparent,
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
        ),
        track = {
            Box(Modifier.fillMaxWidth().height(12.dp))
        },
        thumb = {
            Box(Modifier.width(22.dp).height(12.dp))
        },
        modifier = Modifier.fillMaxSize(),
    )
    }
}
