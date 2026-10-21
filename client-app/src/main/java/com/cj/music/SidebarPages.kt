package com.cj.music

import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cj.music.resources.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 侧边栏四个二级页：收藏 / 传输任务 / 网络连接 / 外观（Compose 版），外加「关于软件」。
 *  和音乐设置弹窗、文件管理器的「设置 / 下载设置」同一套做法：入口返回 ComposeView，
 *  而 openPage(View) 本来就吃任意 View，Java 调用方一行都不用改。
 *  页面挂在侧边栏容器里，owner 沿 Activity 视图树就能找到，不用手动挂。 */

internal fun favoritesPageView(activity: MainActivity): View {
    val view = ComposeView(activity)
    view.setContent { sidebarTheme(activity) { FavoritesPage(activity) } }
    return view
}

internal fun tasksPageView(activity: MainActivity): View {
    val view = ComposeView(activity)
    view.setContent { sidebarTheme(activity) { TasksPage(activity) } }
    return view
}

internal fun networkPageView(activity: MainActivity): View {
    val view = ComposeView(activity)
    view.setContent { sidebarTheme(activity) { NetworkPage(activity) } }
    return view
}

internal fun appearancePageView(activity: MainActivity): View {
    val view = ComposeView(activity)
    view.setContent { sidebarTheme(activity) { AppearancePage(activity) } }
    return view
}

internal fun aboutPageView(activity: MainActivity): View {
    val view = ComposeView(activity)
    view.setContent { sidebarTheme(activity) { AboutPage(activity) } }
    return view
}

@Composable
private fun sidebarTheme(activity: MainActivity, content: @Composable () -> Unit) {
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = accent, onPrimary = Color.White,
            surface = card, onSurface = text,
            surfaceVariant = card, onSurfaceVariant = text.copy(alpha = 0.72f),
            outline = outline, outlineVariant = outline.copy(alpha = 0.5f),
            secondaryContainer = accent.copy(alpha = 0.16f),
        ),
    ) { content() }
}

/* ---------- 收藏 ---------- */

private class Favorite(val uri: String, val name: String, val profileId: String)

@Composable
private fun FavoritesPage(activity: MainActivity) {
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val secondary = Color(activity.pageAttrColor(R.attr.client_text_secondary))
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    /* 收藏是一份 JSON 数组：读出来转成数据类再交给 Compose。
       原来那版是边遍历边 addView，长按监听还要逐个挂。 */
    val items = remember {
        val out = ArrayList<Favorite>()
        val array = activity.pageFavorites()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(Favorite(o.optString("uri"), o.optString("name", o.optString("uri")), o.optString("profileId")))
        }
        out
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 28.dp),
    ) {
        if (items.isEmpty()) {
            PageHint("还没有收藏目录。在任意目录下点击底栏的 ☆ 即可收藏，下次从这里一键打开。", secondary)
            return@Column
        }
        PageSection("已收藏的目录（长按可移除）", secondary)
        for (item in items) {
            Column(
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
                    .elasticClickable(onLongClick = { activity.pageRemoveFavorite(item.uri, item.name, item.profileId) }) {
                        activity.pageOpenFavorite(item.uri, item.name, item.profileId)
                    }
                    .pageCard(outline, card)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(item.name, fontSize = 14.sp, color = text)
                Text(
                    if (item.profileId.isEmpty()) item.uri else "远程 · " + item.uri,
                    fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        PageButton("清空收藏", card, outline, accent) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("清空全部收藏？")
                .setPositiveButton("清空") { _, _ ->
                    activity.pageClearFavorites()
                    activity.reopenSettingsPage()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}

/* ---------- 传输任务 ---------- */

/** 任务文字每 400ms 读一次。原来是 refreshTaskText() 往 TextView 里塞，
    而传输进度 250ms 一跳，轮询取 400ms 足够跟上。
    同一轮询顺带刷新「已落盘文件」：下载/缓存完成后列表立刻出现，不用退出重进。 */
@Composable
private fun TasksPage(activity: MainActivity) {
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val secondary = Color(activity.pageAttrColor(R.attr.client_text_secondary))
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    var task by remember { mutableStateOf(activity.pageLastTask()) }
    var transferring by remember { mutableStateOf(activity.pageTransferring()) }
    var records by remember { mutableStateOf(activity.pageTransfers()) }
    DisposableEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                task = activity.pageLastTask()
                transferring = activity.pageTransferring()
                /* Item 实现了 equals：内容没变就不赋值，避免每 400ms 白重排一次列表。 */
                val fresh = activity.pageTransfers()
                if (fresh != records) records = fresh
                handler.postDelayed(this, 400)
            }
        }
        handler.postDelayed(poll, 400)
        onDispose { handler.removeCallbacks(poll) }
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 28.dp),
    ) {
        Column(
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().pageCard(outline, card)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) { Text(task, fontSize = 14.sp, color = text, lineHeight = 19.sp) }
        PageButton(if (transferring) "取消传输" else "刷新当前目录", card, outline, accent) {
            if (transferring) activity.pageCancelTransfer()
            else { activity.pageReloadDirectory(); activity.reopenSettingsPage() }
        }
        PageHint("传输期间请保持应用在前台。目录递归传输与后台续传尚未实现。", secondary)

        /* —— 已落盘的文件：从网络位置保存 / 临时缓存下来的成品 —— */
        if (records.isNotEmpty()) {
            PageSection("最近保存 / 缓存的文件（长按可移除记录）", secondary)
            for (item in records) {
                Row(
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().pageCard(outline, card)
                        .padding(start = 14.dp, top = 10.dp, end = 2.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                            .elasticClickable(onLongClick = { activity.pageForgetTransfer(item.path) }) {
                                activity.pageOpenTransfer(item.path)
                            },
                    ) {
                        Text(
                            item.name, fontSize = 14.sp, color = text, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            item.kind + " · " + android.text.format.Formatter.formatFileSize(activity, item.size) +
                                " · " + java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                                ).format(java.util.Date(item.time)),
                            fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 2.dp),
                        )
                        if (item.source.isNotEmpty()) {
                            Text(
                                item.source, fontSize = 11.sp, color = secondary, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                    Box(
                        Modifier.width(46.dp).height(46.dp)
                            .elasticClickable { activity.pageRevealTransfer(item.path) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.client_ic_folder),
                            contentDescription = "在新标签页打开所在目录",
                            tint = secondary, modifier = Modifier.size(21.dp),
                        )
                    }
                    Box(
                        Modifier.width(46.dp).height(46.dp)
                            .elasticClickable { activity.pageOpenTransfer(item.path) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.client_ic_open_with),
                            contentDescription = "打开此文件",
                            tint = accent, modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }
            PageButton("清空记录", card, outline, accent) { activity.pageClearTransfers() }
        }
    }
}

/* ---------- 网络连接 ---------- */

@Composable
private fun NetworkPage(activity: MainActivity) {
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val secondary = Color(activity.pageAttrColor(R.attr.client_text_secondary))
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    val saved = remember { activity.pageConnections() }
    /* 副标题（协议 · 主机:端口 · 起始目录）要读连接配置、可能碰磁盘：后台算完逐个回填。 */
    val labels = remember { mutableStateMapOf<String, String>() }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 28.dp),
    ) {
        PageSection("已保存的连接", secondary)
        if (saved.isEmpty()) PageHint("还没有保存的连接，点击下方按钮添加网络位置。", secondary)
        for ((id, name) in saved) {
            Row(
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
                    .elasticClickable { activity.pageConnectFromSidebar(id) }
                    .pageCard(outline, card)
                    .padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, fontSize = 14.sp, color = text)
                    Text(
                        labels[id] ?: "正在读取…", fontSize = 12.sp, color = secondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                var morePos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                Box(
                    Modifier.width(48.dp).height(48.dp)
                        .onGloballyPositioned { morePos = it.positionInWindow() }
                        .elasticClickable { activity.pageConnectionMenu(id, name, morePos.x, morePos.y) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.client_ic_more),
                        contentDescription = "连接操作", tint = secondary,
                    )
                }
            }
            DisposableEffect(id) {
                activity.pageConnectionLabel(id) { value -> labels[id] = value }
                onDispose { }
            }
        }
        HorizontalDivider(color = outline, modifier = Modifier.padding(vertical = 12.dp))
        PageButton("＋ 添加 FTP", card, outline, accent) {
            activity.pageAddConnection("添加 FTP", com.cj.music.net.Endpoint.Protocol.FTP)
        }
        PageButton("＋ 添加 FTPS", card, outline, accent) {
            activity.pageAddConnection("添加 FTPS", com.cj.music.net.Endpoint.Protocol.FTPS_EXPLICIT)
        }
        PageButton("＋ 添加 SMB", card, outline, accent) {
            activity.pageAddConnection("添加 SMB", com.cj.music.net.Endpoint.Protocol.SMB)
        }
        PageButton("＋ 添加 WebDAV", card, outline, accent) {
            activity.pageAddConnection("添加 WebDAV", com.cj.music.net.Endpoint.Protocol.WEBDAV)
        }
        PageButton("＋ 添加 OpenList", card, outline, accent) {
            activity.pageAddConnection("添加 OpenList", com.cj.music.net.Endpoint.Protocol.OPENLIST)
        }
    }
}

/* ---------- 外观 ---------- */

/** 预览图仍是一个 Android ImageView：背景是 Drawable，转 Compose Painter 要额外依赖，
    用 AndroidView 包一层最省事。 */
@Composable
private fun AppearancePage(activity: MainActivity) {
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val secondary = Color(activity.pageAttrColor(R.attr.client_text_secondary))
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    var dim by remember { mutableIntStateOf(activity.pagePrefInt("dim", 50)) }
    var rowAlpha by remember { mutableIntStateOf(activity.pagePrefInt("row_alpha", 70)) }
    var fit by remember { mutableIntStateOf(activity.pagePrefInt("fit", 0)) }
    val fitNames = remember { activity.pageBackgroundFits() }
    val density = LocalDensity.current
    var headingPixels by remember { mutableIntStateOf(0) }
    var settingsPixels by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val previewHeight = if (settingsPixels == 0) 236.dp else
        (maxHeight - with(density) { (headingPixels + settingsPixels).toDp() } - 36.dp).coerceAtLeast(140.dp)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().onSizeChanged { headingPixels = it.height }) {
        PageHint("选中即刻生效，无需确认", secondary)
        PageSection("背景", secondary)
        }
        AndroidView(
            /* 按实际设置区高度分配剩余空间；小屏保留可滚动的最低预览高度。 */
            modifier = Modifier.fillMaxWidth().height(previewHeight).padding(top = 6.dp)
                .background(card, RoundedCornerShape(12.dp)),
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding(2, 2, 2, 2)
                    val radius = 16 * resources.displayMetrics.density
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                    }
                    clipToOutline = true
                    activity.pageWallpaper()?.let { setImageDrawable(it) }
                }
            },
            update = { image ->
                image.scaleType = activity.pageWallpaperScaleType()
                activity.pageWallpaper()?.let { image.setImageDrawable(it) }
            },
        )
        Column(Modifier.fillMaxWidth().onSizeChanged { settingsPixels = it.height }) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            PageButton("选择背景图片", card, outline, accent, Modifier.weight(1f)) { activity.pagePickBackground() }
            Spacer(Modifier.width(8.dp))
            PageButton("移除背景图片", card, outline, secondary, Modifier.weight(1f)) { activity.pageRemoveBackground() }
        }
        PageSection("背景遮罩强度", secondary)
        PageSlider(activity, "遮罩强度", dim, accent, secondary) { dim = it; activity.pageSetDim(it) }
        PageSection("文件行不透明度", secondary)
        PageSlider(activity, "文件行不透明度", rowAlpha, accent, secondary) { rowAlpha = it; activity.pageSetRowAlpha(it) }
        PageSection("图片缩放方式", secondary)
        /* 原来是个 Spinner。Compose 的 ExposedDropdownMenu 要 experimental 注解，
           而这里只有四个选项 —— 一行一行单选反而比下拉更好点。 */
        for ((index, name) in fitNames.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    .elasticClickable(role = Role.RadioButton) { fit = index; activity.pageSetFit(index) }
                    .semantics { selected = index == fit }
                    .pageCard(outline, card)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, fontSize = 14.sp, color = text, modifier = Modifier.weight(1f))
                RadioButton(
                    selected = index == fit,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = accent),
                )
            }
        }
    }
    }
    }
}

/** 带标题与当前值的横向滑杆。 */
@Composable
private fun PageSlider(activity: MainActivity, label: String, value: Int, accent: Color, secondary: Color, onChange: (Int) -> Unit) {
    val host = LocalView.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = secondary, modifier = Modifier.weight(1f))
            Text(value.toString(), fontSize = 13.sp, color = accent)
        }
        CompactSlider(
            value = value.toFloat(),
            onValueChange = { onChange(Math.round(it)) },
            valueRange = 0f..100f,
            accent = accent,
            inactiveTrack = secondary.copy(alpha = 0.3f),
            modifier = Modifier.padding(top = 2.dp).onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val screen = IntArray(2); val window = IntArray(2)
                host.getLocationOnScreen(screen); host.getLocationInWindow(window)
                activity.pageExcludeSidebarSwipe(label, android.graphics.Rect(
                    Math.round(bounds.left) + screen[0] - window[0], Math.round(bounds.top) + screen[1] - window[1],
                    Math.round(bounds.right) + screen[0] - window[0], Math.round(bounds.bottom) + screen[1] - window[1],
                ))
            },
        )
    }
}

/* ---------- 关于软件 ---------- */

/** 检查更新 / 反馈问题的目标地址。
 *  检查更新 → 仓库主页；反馈问题 → 仓库 Issues。留空时点击给「暂未开放」提示。 */
private const val ABOUT_UPDATE_URL = "https://github.com/ST-Jie-Ge/CJ-File-Manager"
private const val ABOUT_FEEDBACK_URL = "https://github.com/ST-Jie-Ge/CJ-File-Manager/issues"

private class OssLib(val name: String, val license: String, val url: String)

/** 开源组件清单：许可证均从随仓库分发的 LICENSE/NOTICE 或上游 POM 逐条核对过，
 *  别凭印象改——写错许可证比不写更糟。 */
private val OSS_LIBRARIES = listOf(
    OssLib("FFmpegKit / FFmpeg（转码）", "LGPL-3.0", "https://www.gnu.org/licenses/lgpl-3.0.html"),
    OssLib("libtorrent（BitTorrent）", "BSD-3-Clause", "https://opensource.org/licenses/BSD-3-Clause"),
    OssLib("libtorrent4j（libtorrent 的 Java 绑定）", "MIT", "https://opensource.org/licenses/MIT"),
    OssLib("jed2k（ed2k / Kademlia）", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    OssLib("SMBJ（SMB2/3）", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    OssLib("dcerpc", "BSD-3-Clause", "https://opensource.org/licenses/BSD-3-Clause"),
    OssLib("BouncyCastle（加密）", "MIT", "https://opensource.org/licenses/MIT"),
    OssLib("asn-one", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    OssLib("mbassador", "MIT", "https://opensource.org/licenses/MIT"),
    OssLib("SLF4J", "MIT", "https://opensource.org/licenses/MIT"),
    OssLib("Apache Commons Compress / Net / IO", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    OssLib("junrar（RAR 解压）", "UnRar License", "https://raw.githubusercontent.com/junrar/junrar/master/license.txt"),
    OssLib("zip4j（ZIP 创建 / 加密）", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    OssLib("Gson", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    OssLib("re2j", "Go License", "https://golang.org/LICENSE"),
    OssLib("AndroidX / Media3 / Material Components", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
)

private class AboutVersion(val name: String, val code: Long)

private fun aboutVersion(activity: MainActivity): AboutVersion {
    return try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        AboutVersion(info.versionName ?: "—", info.longVersionCode)
    } catch (e: Exception) {
        AboutVersion("—", 0L)
    }
}

/** 打开浏览器；链接还没接入时给一句提示，别静默失灵。 */
private fun openAboutUrl(activity: MainActivity, url: String) {
    if (url.isEmpty()) {
        android.widget.Toast.makeText(activity, "链接暂未开放", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(activity, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 许可证正文：全文放在 res/raw，弹窗里逐字展示，不跳浏览器。 */
private val LICENSE_BLOCKS = listOf(
    "Apache License 2.0" to R.raw.client_license_apache2,
    "MIT License" to R.raw.client_license_mit,
    "BSD 3-Clause License" to R.raw.client_license_bsd3,
    "GNU Lesser General Public License 3.0" to R.raw.client_license_lgpl3,
    "UnRAR License" to R.raw.client_license_unrar,
)

private fun readLicenseAsset(activity: MainActivity, resource: Int): String = try {
    activity.resources.openRawResource(resource).bufferedReader().use { it.readText() }
} catch (e: Exception) {
    "（许可证正文读取失败：${e.message ?: "未知错误"}）"
}

/** 弹窗正文：只有各许可证全文（无声明、无组件目录），标题行只用来分隔各协议。 */
private fun licenseDocument(activity: MainActivity): String {
    val builder = StringBuilder()
    for ((title, resource) in LICENSE_BLOCKS) {
        builder.append("■ $title\n")
        builder.append(readLicenseAsset(activity, resource)).append('\n')
    }
    return builder.toString()
}

/** 点击「开源代码许可」→ 一个可滚动的弹窗，正文全部在里面（不再有展开列表，也不跳浏览器）。 */
private fun showLicenseDialog(activity: MainActivity) {
    /* 正文是 5 个 raw 资源、约 25KB：读文件放后台，读完再弹窗，避免点下去卡一拍。 */
    Thread({
        val body = licenseDocument(activity)
        activity.runOnUiThread {
            if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
            val density = activity.resources.displayMetrics.density
            val text = android.widget.TextView(activity).apply {
                this.text = body
                textSize = 11.5f
                setLineSpacing(0f, 1.25f)
                setTextIsSelectable(true)
                setTextColor(activity.pageAttrColor(R.attr.client_text))
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
            }
            val scroll = android.widget.ScrollView(activity).apply { addView(text) }
            val container = android.widget.FrameLayout(activity)
            val height = (activity.resources.displayMetrics.heightPixels * 0.7f).toInt()
            container.addView(
                scroll,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, height,
                ),
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle("开源代码许可")
                .setView(container)
                .setPositiveButton("关闭", null)
                .show()
        }
    }, "license-dialog").start()
}

@Composable
private fun AboutPage(activity: MainActivity) {
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val secondary = Color(activity.pageAttrColor(R.attr.client_text_secondary))
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    val version = remember { aboutVersion(activity) }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 28.dp),
    ) {
        /* 软件 logo + 名称 + 版本号（name 与 code 都给：反馈问题时用户报得清）。 */
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            /* 软件 logo：用 AndroidView + ImageView 加载自适应启动图标。
               ⚠ 别用 Compose 的 painterResource(R.mipmap.client_launcher)：
               自适应图标的资源路径是 .xml（mipmap-anydpi-v21/client_launcher.xml），
               painterResource 会判定为 vector XML 走 loadVectorResource，
               但根标签是 <adaptive-icon> 不是 <vector>，直接抛
               IllegalArgumentException("Only VectorDrawables and rasterized asset types are supported...")，
               在 Compose 组合期崩溃（1.09 之前的「打开关于软件会崩溃」根因）。
               ImageView 的 setImageResource 走 Android 资源系统，能正确解出 AdaptiveIconDrawable。 */
            AndroidView(
                factory = { context ->
                    android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        setImageResource(com.cj.music.R.mipmap.client_launcher)
                    }
                },
                modifier = Modifier.size(72.dp),
            )
            Text(
                activity.getString(R.string.client_app_name),
                fontSize = 17.sp, fontWeight = FontWeight.Medium, color = text,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "版本 ${version.name}（${version.code}）",
                fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 3.dp),
            )
        }
        PageSection("支持", secondary)
        AboutRow(
            title = "检查更新",
            subtitle = if (ABOUT_UPDATE_URL.isEmpty()) "暂未开放" else "查看是否有新版本",
            text, secondary, card, outline,
        ) { openAboutUrl(activity, ABOUT_UPDATE_URL) }
        AboutRow(
            title = "反馈问题",
            subtitle = if (ABOUT_FEEDBACK_URL.isEmpty()) "暂未开放" else "提交 Bug 或建议",
            text, secondary, card, outline,
        ) { openAboutUrl(activity, ABOUT_FEEDBACK_URL) }
        AboutRow(
            title = "开源代码许可",
            subtitle = "${OSS_LIBRARIES.size} 个组件 · 点击查看全部许可正文",
            text, secondary, card, outline,
        ) { showLicenseDialog(activity) }
    }
}

/** 关于页的通用行：标题 + 副标题 + 右侧 ›。 */
@Composable
private fun AboutRow(
    title: String, subtitle: String,
    text: Color, secondary: Color, card: Color, outline: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
            .elasticClickable(onClick = onClick)
            .pageCard(outline, card)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = text)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 12.sp, color = secondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text("›", fontSize = 16.sp, color = secondary)
    }
}
