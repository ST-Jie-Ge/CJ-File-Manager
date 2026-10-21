package com.cj.music

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cj.music.resources.R

/** 格式工厂（Compose 版）。FFmpeg 的会话、进度回调、取消与产物处理全部留在 Java 侧
 *  （FormatFactoryActivity.start/cancel/finishRun…），这里只负责画。
 *  数据流和 EQ 页一样：Java 把状态写进 FormatUiState，Compose 读它重组；
 *  交互（选类型、换格式、开始转换）通过桥接方法回传。 */

enum class FormatResult { NONE, FAILED, DONE }

internal class FormatUiState {
    /** -1 = 还没选媒体类别，0 = 视频，1 = 音频。 */
    var sourceType by mutableIntStateOf(-1)
    var hasSource by mutableStateOf(false)
    var previewFrame by mutableStateOf<Bitmap?>(null)
    var sourceName by mutableStateOf("")
    var sourceInfo by mutableStateOf("")
    var formatIndex by mutableIntStateOf(0)
    var codecIndex by mutableIntStateOf(0)
    var resolutionIndex by mutableIntStateOf(1)
    var qualityIndex by mutableIntStateOf(1)
    var audioIndex by mutableIntStateOf(0)
    var audioFormatIndex by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    /** 进度百分比；-1 = 探不到总时长，转不定量。 */
    var progress by mutableIntStateOf(0)
    var status by mutableStateOf("")
    var result by mutableStateOf(FormatResult.NONE)
    var resultTitle by mutableStateOf("")
    var resultDetail by mutableStateOf("")
    var accent by mutableIntStateOf(0xffb55283.toInt())
    var revision by mutableIntStateOf(0)
    fun bump() { revision += 1 }
}

internal fun formatFactoryView(activity: FormatFactoryActivity, state: FormatUiState): ComposeView {
    val view = ComposeView(activity)
    view.setContent { FormatFactoryPage(activity, state) }
    return view
}

@Composable
private fun FormatFactoryPage(activity: FormatFactoryActivity, state: FormatUiState) {
    val revision = state.revision
    val accent = Color(state.accent)
    val text = Color(PageTheme.text(activity))
    val secondary = Color(PageTheme.secondary(activity))
    val surface = Color(PageTheme.surface(activity))
    val card = Color(PageTheme.card(activity))
    val outline = Color(PageTheme.outline(activity))
    val density = LocalDensity.current.density
    /* pageContentInset() 返回的是**像素**（Java 侧 dp() 已经乘过一次密度），
       这里必须先除回密度再转 dp —— 不然 20dp 会变成 55dp，整列内容被挤到不对位。 */
    val inset = remember(revision) { Math.round(activity.pageContentInset() / density) }
    val codecs = remember(state.formatIndex) { activity.formatCodecNames() }
    val audioQuality = remember(state.sourceType, state.audioFormatIndex) { activity.formatAudioHasQuality() }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = accent, onPrimary = Color.White,
            surface = surface, onSurface = text,
            surfaceVariant = surface, onSurfaceVariant = secondary,
            outline = outline, outlineVariant = outline.copy(alpha = 0.5f),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(surface)) {
            Column(modifier = Modifier.fillMaxSize()) {
                /* 顶部：返回 + 标题 */
                Row(
                    modifier = Modifier.fillMaxWidth()
                        /* android.R.id.content 已由 ClientWindowInsets 加过状态栏安全边距；
                           这里再叠一次会把标题整体往下推一块空白。 */
                        .padding(start = inset.dp, top = 8.dp, end = inset.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { activity.handleBack() }) {
                        Box(
                            modifier = Modifier.background(Color(PageTheme.card(activity)), RoundedCornerShape(20.dp))
                                .padding(10.dp),
                        ) {
                            Icon(painterResource(R.drawable.client_ic_back), contentDescription = "返回文件管理器", tint = text)
                        }
                    }
                    Text(
                        "格式工厂", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = text,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = inset.dp)
                        .padding(bottom = 116.dp),
                ) {
                    if (state.sourceType < 0) {
                        TypePicker(activity, state, accent, text, secondary, surface, outline)
                    } else {
                        /* 三个动作 */
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                .background(surface, RoundedCornerShape(18.dp))
                                .border(1.dp, outline, RoundedCornerShape(18.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            for (label in listOf("选择文件", "输出目录", "转换历史")) {
                                Box(
                                    modifier = Modifier.weight(1f).height(36.dp)
                                        .padding(horizontal = 3.dp)
                                        .background(card, RoundedCornerShape(12.dp))
                                        .border(1.dp, outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                        .clickable {
                                            when (label) {
                                                "选择文件" -> activity.formatChoose()
                                                "输出目录" -> activity.formatOpenOutputDir()
                                                else -> activity.formatOpenHistory()
                                            }
                                        },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, fontSize = 12.sp, color = text, maxLines = 1) }
                            }
                        }
                        /* 文件信息 */
                        if (state.hasSource) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    .background(surface, RoundedCornerShape(18.dp))
                                    .border(1.dp, outline, RoundedCornerShape(18.dp))
                                    .padding(12.dp),
                            ) {
                                AndroidView(
                                    modifier = Modifier.width(76.dp).height(76.dp),
                                    factory = { context ->
                                        ImageView(context).apply {
                                            scaleType = ImageView.ScaleType.CENTER_CROP
                                            clipToOutline = true
                                            background = android.graphics.drawable.GradientDrawable().apply {
                                                setColor(PageTheme.card(context)); cornerRadius = 8 * density
                                            }
                                        }
                                    },
                                    update = { view ->
                                        val frame = state.previewFrame
                                        if (frame != null) view.setImageBitmap(frame)
                                        else view.setImageResource(R.drawable.client_ic_video)
                                    },
                                )
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(
                                        state.sourceName, fontSize = 14.sp, color = text, maxLines = 2,
                                        overflow = TextOverflow.MiddleEllipsis,
                                    )
                                    Text(
                                        state.sourceInfo, fontSize = 12.sp, color = secondary,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                        /* 输出设置 */
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .background(surface, RoundedCornerShape(18.dp))
                                .border(1.dp, outline, RoundedCornerShape(18.dp))
                                .padding(bottom = 8.dp),
                        ) {
                            Text(
                                "输出设置", fontSize = 13.sp, color = text,
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp),
                            )
                            if (state.sourceType == 0) {
                                ChipRow("目标格式", listOf("MP4", "MKV", "WebM", "AVI", "MOV", "GIF"), state.formatIndex, accent, text) {
                                    activity.formatSetFormat(it)
                                }
                            } else {
                                ChipRow("目标格式", activity.formatAudioFormats().toList(), state.audioFormatIndex, accent, text) {
                                    activity.formatSetAudioFormat(it)
                                }
                            }
                            val video = state.formatIndex != 5 && state.formatIndex != 6
                            if (video) {
                                ChipRow("视频编码", codecs.toList(), state.codecIndex, accent, text) { activity.formatSetCodec(it) }
                                ChipRow("分辨率", listOf("原始分辨率", "1080P", "720P", "480P"), state.resolutionIndex, accent, text) {
                                    activity.formatSetResolution(it)
                                }
                            }
                            if (state.formatIndex != 5 && (state.sourceType != 1 || audioQuality)) {
                                ChipRow(
                                    if (audioQuality) "音质" else "画质",
                                    (if (audioQuality) activity.formatAudioQualities() else activity.formatVideoQualities()).toList(),
                                    state.qualityIndex, accent, text,
                                ) { activity.formatSetQuality(it) }
                            }
                            val audio = state.formatIndex != 5 && state.formatIndex != 6
                            if (audio) {
                                val options = if (state.formatIndex == 2) listOf("Opus 96k", "无音频")
                                else listOf("复制原音轨", "AAC 128k", "MP3 192k", "无音频")
                                val selected = if (state.formatIndex == 2) (if (state.audioIndex == 3) 1 else 0) else state.audioIndex
                                ChipRow("音频", options, selected, accent, text) { activity.formatSetAudio(it) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { activity.formatStart() },
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                        ) { Text("开始转换", fontSize = 14.sp) }
                    }
                    /* 结果区 */
                    if (state.result != FormatResult.NONE) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .background(surface, RoundedCornerShape(18.dp))
                                .border(1.dp, outline, RoundedCornerShape(18.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(state.resultTitle, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = text)
                            if (state.resultDetail.isNotEmpty()) {
                                Text(state.resultDetail, fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 3.dp))
                            }
                            if (state.result == FormatResult.DONE) {
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Button(
                                        onClick = { activity.formatPlay() },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                                    ) { Text("播放", fontSize = 13.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { activity.formatShare() },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(12.dp),
                                    ) { Text("分享", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }
            /* 运行中的转换：固定在底部的胶囊，不随设置滚动消失 */
            if (state.running) {
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .padding(horizontal = inset.dp)
                        .padding(bottom = 14.dp)
                        .background(surface, RoundedCornerShape(18.dp))
                        .border(1.dp, outline, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (state.progress < 0) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = accent, trackColor = outline.copy(alpha = 0.3f), strokeCap = StrokeCap.Round,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = accent, trackColor = outline.copy(alpha = 0.3f),
                            strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                        )
                    }
                    Text(state.status, fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 6.dp))
                    TextButton(
                        onClick = { activity.formatCancel() },
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text("取消转换", color = accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 首屏：媒体类别二选一。 */
@Composable
private fun TypePicker(
    activity: FormatFactoryActivity, state: FormatUiState,
    accent: Color, text: Color, secondary: Color, surface: Color, outline: Color,
) {
    val wide = remember(state.revision) { activity.formatIsWide() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "媒体工具", fontSize = 11.sp, color = accent, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        Text("今天想转换什么？", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = text)
        Spacer(Modifier.height(8.dp))
        if (wide) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TypeTile("视频", "MP4 · MKV · WebM · GIF", R.drawable.client_ic_video, Color(0xffb55283), surface, outline, text, secondary, Modifier.weight(1f).heightIn(min = 168.dp)) {
                    activity.formatPickType(0)
                }
                TypeTile("音频", "音频转码 · 从视频提取音频", R.drawable.client_ic_audio, Color(0xff9161bc), surface, outline, text, secondary, Modifier.weight(1f).heightIn(min = 168.dp)) {
                    activity.formatPickType(1)
                }
            }
        } else {
            TypeTile("视频", "MP4 · MKV · WebM · GIF", R.drawable.client_ic_video, Color(0xffb55283), surface, outline, text, secondary, Modifier.fillMaxWidth().heightIn(min = 144.dp)) {
                activity.formatPickType(0)
            }
            Spacer(Modifier.height(10.dp))
            TypeTile("音频", "音频转码 · 从视频提取音频", R.drawable.client_ic_audio, Color(0xff9161bc), surface, outline, text, secondary, Modifier.fillMaxWidth().heightIn(min = 144.dp)) {
                activity.formatPickType(1)
            }
        }
    }
}

@Composable
private fun TypeTile(
    title: String, subtitle: String, icon: Int, tint: Color,
    surface: Color, outline: Color, text: Color, secondary: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier = modifier.background(surface, RoundedCornerShape(18.dp))
            .border(1.dp, tint, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.width(60.dp).height(60.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = text, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, fontSize = 12.sp, color = secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 一组药丸（对应原来的 chips()）。选中项用实心强调色，未选中描边。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String, options: List<String>, selected: Int,
    accent: Color, text: Color, onPick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(label, fontSize = 11.5.sp, color = text.copy(alpha = 0.72f))
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((index, name) in options.withIndex()) {
                val on = index == selected
                FilterChip(
                    selected = on,
                    onClick = { onPick(index) },
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(13.dp),
                    label = { Text(name, fontSize = 12.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent, selectedLabelColor = Color.White,
                        labelColor = text.copy(alpha = 0.72f),
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = on,
                        selectedBorderColor = Color.Transparent,
                        selectedBorderWidth = 0.dp,
                    ),
                )
            }
        }
    }
}
