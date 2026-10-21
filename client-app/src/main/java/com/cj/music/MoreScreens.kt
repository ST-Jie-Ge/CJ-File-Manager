package com.cj.music

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cj.music.resources.R
import org.json.JSONArray

/** 三个「表单 / 列表」型界面的 Compose 版：格式工厂转换历史、磁力下载页、解压浮层的等待框。
 *  做法与前面几个页面一致：入口返回 ComposeView，Java 侧只留桥接方法。 */

/* ================= 格式工厂 · 转换历史 ================= */

internal fun formatHistoryView(activity: FormatHistoryActivity): ComposeView {
    val view = ComposeView(activity)
    activity.pageView = view
    view.setContent { screenTheme(activity) { HistoryPage(activity) } }
    return view
}

/** 增删之后重新组一次内容。Java 侧写不出 @Composable 的 lambda，只能由这里发起。 */
internal fun renderHistoryContent(activity: FormatHistoryActivity) {
    activity.pageView?.setContent { screenTheme(activity) { HistoryPage(activity) } }
}

@Composable
private fun screenTheme(activity: android.app.Activity, content: @Composable () -> Unit) {
    val accent = Color(PageTheme.accent(activity))
    val text = Color(PageTheme.text(activity))
    val card = Color(PageTheme.card(activity))
    val outline = Color(PageTheme.outline(activity))
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = accent, onPrimary = Color.White,
            surface = card, onSurface = text,
            surfaceVariant = card, onSurfaceVariant = text.copy(alpha = 0.72f),
            outline = outline, outlineVariant = outline.copy(alpha = 0.5f),
        ),
    ) { content() }
}

@Composable
private fun HistoryPage(activity: FormatHistoryActivity) {
    val text = Color(PageTheme.text(activity))
    val secondary = Color(PageTheme.secondary(activity))
    val accent = Color(PageTheme.accent(activity))
    val card = Color(PageTheme.card(activity))
    val outline = Color(PageTheme.outline(activity))
    val density = LocalDensity.current.density
    val statusBar = (activity.pageStatusBar() / density).dp
    val items = activity.pageHistoryItems()
    Column(modifier = Modifier.fillMaxSize().background(Color(PageTheme.surface(activity)))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = statusBar + 12.dp, end = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { activity.finish() }) {
                Icon(painter = painterResource(R.drawable.client_ic_back), contentDescription = "返回格式工厂", tint = text)
            }
            Text("转换历史", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = text,
                modifier = Modifier.padding(start = 14.dp).weight(1f))
            if (items.isNotEmpty()) {
                Text(
                    "清空", fontSize = 13.sp, color = secondary,
                    modifier = Modifier
                        .background(card, RoundedCornerShape(14.dp))
                        .clickable { activity.pageHistoryClear() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无转换记录", fontSize = 14.sp, color = secondary)
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items) { item ->
                val exists = remember(item.path) { activity.pageHistoryExists(item) }
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(card, RoundedCornerShape(22.dp))
                        .border(1.dp, outline, RoundedCornerShape(22.dp))
                        .then(
                            if (exists) Modifier.pointerInput(item.path) {
                                detectTapGestures(
                                    onTap = { activity.pageHistoryPlay(item) },
                                    onLongPress = { activity.pageHistoryRemove(item) },
                                )
                            } else Modifier
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        item.name, fontSize = 14.sp, color = text, maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        activity.pageHistorySubtitle(item) + if (exists) "" else "  ·  文件已不存在",
                        fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/* ================= 磁力 / ed2k 下载页 ================= */

internal fun downloadPageView(page: DownloadSidebarPage): ComposeView {
    val view = ComposeView(page.context)
    page.composeView = view
    view.setContent { screenTheme(page.context as android.app.Activity) { DownloadPage(page) } }
    return view
}

private class DownloadTask(
    val id: String, val name: String, val state: String, val link: String,
    val done: Long, val total: Long, val rate: Long, val peers: Int, val error: String,
) {
    val active: Boolean get() = state == "下载中" || state == "寻找资源"
    val finished: Boolean get() = state == "完成"
}

@Composable
private fun DownloadPage(page: DownloadSidebarPage) {
    val text = Color(PageTheme.text(page.context))
    val secondary = Color(PageTheme.secondary(page.context))
    val accent = Color(PageTheme.accent(page.context))
    val card = Color(PageTheme.card(page.context))
    val outline = Color(PageTheme.outline(page.context))
    var snapshot by remember { mutableStateOf(page.pageDownloadSnapshot()) }
    var lastNotice by remember { mutableStateOf("") }
    /* 快照由下载服务每秒推一次；Compose 侧轮询同一个字符串，变了才重组。 */
    DisposableEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                val next = page.pageDownloadSnapshot()
                if (next != snapshot) snapshot = next
                /* 提示语只在新的一条到来时弹一次：notice 本身不会清空，不去重会每秒弹一回。 */
                val notice = page.pageDownloadNotice()
                if (notice.isNotEmpty() && notice != lastNotice) {
                    lastNotice = notice
                    page.pageShowNotice(notice)
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(poll, 1000)
        onDispose { handler.removeCallbacks(poll) }
    }
    val tasks = remember(snapshot) {
        val out = ArrayList<DownloadTask>()
        try {
            val array = JSONArray(if (snapshot.isEmpty()) "[]" else snapshot)
            for (i in 0 until array.length()) {
                val j = array.getJSONObject(i)
                out.add(
                    DownloadTask(
                        j.getString("id"), j.optString("name"), j.optString("state"), j.optString("link"),
                        j.optLong("done"), j.optLong("total"), j.optLong("rate"), j.optInt("peers"), j.optString("error"),
                    )
                )
            }
        } catch (e: Exception) { }
        out
    }
    if (tasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无下载\n点击右上角 ＋ 添加链接", fontSize = 14.sp, color = secondary)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            Text(
                "同时下载：磁力 " + page.pageMaxMagnet() + " 个、ed2k " + page.pageMaxEd2k() + " 个（暂停不计）",
                fontSize = 11.sp, color = secondary, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
            )
        }
        items(tasks) { task ->
            Column(
                modifier = Modifier.fillMaxWidth().background(card, RoundedCornerShape(12.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                Text(task.name, fontSize = 14.sp, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    task.state + " · " + (if (task.link.startsWith("magnet:")) "磁力" else "ed2k"),
                    fontSize = 11.sp, color = secondary, modifier = Modifier.padding(vertical = 3.dp),
                )
                val fraction = if (task.total > 0) (task.done.toFloat() / task.total).coerceIn(0f, 1f) else 0f
                if (task.total > 0) {
                    LinearProgressIndicator(
                        progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = accent, trackColor = outline.copy(alpha = 0.3f),
                        strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                    )
                } else if (task.active) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = accent, trackColor = outline.copy(alpha = 0.3f),
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            page.pageBytes(task.done) + " / " + (if (task.total > 0) page.pageBytes(task.total) else "获取文件信息")
                            + (if (task.active) "\n" + page.pageBytes(task.rate) + "/s · " + task.peers + " 个来源" else ""),
                            fontSize = 11.sp, color = secondary,
                        )
                    }
                    if (!task.finished) {
                        Box(Modifier.width(48.dp).height(48.dp).elasticClickable { page.pageDownloadCommand(if (task.active) "pause" else "resume", task.id) }, contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(if (task.active) R.drawable.client_player_icon_pause else R.drawable.client_player_icon_play),
                                contentDescription = if (task.active) "暂停" else "继续", tint = text,
                            )
                        }
                    }
                    Box(Modifier.width(48.dp).height(48.dp).elasticClickable { page.pageConfirmRemove(task.id) }, contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.client_bottom_icon_cancel),
                            contentDescription = "移除下载记录", tint = text,
                        )
                    }
                }
                if (task.error.isNotEmpty()) {
                    Text(task.error, fontSize = 11.sp, color = Color(0xFFF2A3A3), modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/* ================= 解压浮层 · 等待框 ================= */

/** 原来是一个手搓的「转圈 + 文案」LinearLayout，塞进 MaterialAlertDialog。
    内容就两行，用 Compose 写反而更长 —— 但既然要统一，就和其它界面一致。 */
internal fun archiveWaitingView(activity: ArchiveOpenActivity): ComposeView {
    val view = ComposeView(activity)
    view.setContent {
        screenTheme(activity) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp, 22.dp, 24.dp, 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.width(40.dp).height(40.dp), color = Color(PageTheme.accent(activity)))
                Text(
                    activity.getString(R.string.client_preparing_archive), fontSize = 13.sp,
                    color = Color(PageTheme.text(activity)), modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
    return view
}
