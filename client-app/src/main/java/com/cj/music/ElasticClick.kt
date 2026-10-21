package com.cj.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/** 只用弹性形变反馈，不绘制按压色；取消手势和滚动由标准 clickable 处理。 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.elasticClickable(
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pulse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val x by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .55f, stiffness = 600f), label = "pressWidth")
    val y by animateFloatAsState(if (pressed) .91f else 1f, spring(dampingRatio = .55f, stiffness = 600f), label = "pressHeight")
    graphicsLayer { scaleX = x * pulse.value; scaleY = y * pulse.value }
        .combinedClickable(interactionSource = interactions, indication = null, role = role, onLongClick = onLongClick, onClick = {
            // 滚动容器可能将快速点按的 Press/Release 合并；点击本身也保证播放一次回弹。
            scope.launch { pulse.snapTo(.92f); pulse.animateTo(1f, spring(dampingRatio = .48f, stiffness = 480f)) }
            onClick()
        })
}
