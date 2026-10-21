package com.cj.music

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cj.music.resources.R

/** 文件管理器侧边栏的两个二级页：设置 / 下载设置（Compose 版）。
 *
 *  原来它们和另外四个页面共用 MainActivity 里一整套手搓行式 DSL
 *  （pageColumn / pageSection / pageRow / pageTexts / pageSwitch / pageButton …）。
 *  这两个页先搬过来，跑通了剩下的四个（收藏 / 任务 / 网络 / 外观）可以照同一套搬。
 *
 *  ⚠ 页面是塞进侧边栏容器（sidebarPageBody）里的，那棵视图树挂在 Activity 上，
 *  LifecycleOwner / SavedStateRegistryOwner 沿树就能找到 —— 所以这里不用像弹窗那样手动挂 owner。
 *  入口返回的是 ComposeView，而 openPage(View) 本来就吃任意 View，调用方一行都不用改。 */

internal fun settingsPageView(activity: MainActivity): View {
    val view = ComposeView(activity)
    view.setContent { FileManagerPageTheme(activity) { SettingsPage(activity) } }
    return view
}

internal fun downloadSettingsPageView(activity: MainActivity): View {
    val view = ComposeView(activity)
    view.setContent { FileManagerPageTheme(activity) { DownloadSettingsPage(activity) } }
    return view
}

/** 把应用的配色属性喂给 MaterialTheme —— 否则 M3 默认是紫色，涟漪和开关都会跑色。 */
@Composable
private fun FileManagerPageTheme(activity: MainActivity, content: @Composable () -> Unit) {
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            surface = card,
            onSurface = text,
            surfaceVariant = card,
            onSurfaceVariant = text.copy(alpha = 0.72f),
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.5f),
            secondaryContainer = accent.copy(alpha = 0.16f),
        ),
    ) { content() }
}

/* ---------- 通用零件（对应原来的 pageSection / pageHint / pageRow / pageSwitch / pageButton） ---------- */

/** 行卡片：client_card 底 + 1dp client_outline 描边 + 12dp 圆角（对应 R.drawable.client_page_row）。 */
@Composable
internal fun Modifier.pageCard(outline: Color, card: Color): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return this
        .background(card, shape)
        .border(1.dp, outline, shape)
}

@Composable
internal fun PageSection(title: String, secondary: Color) {
    Text(
        title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = secondary,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, end = 4.dp, bottom = 6.dp),
    )
}

@Composable
internal fun PageHint(text: String, secondary: Color) {
    Text(
        text, fontSize = 12.sp, color = secondary, lineHeight = 15.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp),
    )
}

@Composable
private fun PageSwitchRow(
    title: String, checked: Boolean, card: Color, outline: Color, text: Color, accent: Color,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .elasticClickable(role = Role.Switch) { onChecked(!checked) }
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .pageCard(outline, card)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, color = text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                uncheckedThumbColor = outline,
                uncheckedTrackColor = card,
                uncheckedBorderColor = outline,
            ),
        )
    }
}

@Composable
private fun PageTextRow(
    title: String, subtitle: String?, card: Color, outline: Color, text: Color, secondary: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .elasticClickable(onClick = onClick)
            .pageCard(outline, card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 14.sp, color = text)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, fontSize = 12.sp, color = secondary, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
internal fun PageButton(label: String, card: Color, outline: Color, text: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .height(46.dp)
            .elasticClickable(onClick = onClick)
            .pageCard(outline, card),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = text)
    }
}

/* ---------- 设置页 ---------- */

@Composable
private fun SettingsPage(activity: MainActivity) {
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val secondary = Color(activity.pageAttrColor(R.attr.client_text_secondary))
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))

    var hideHidden by remember { mutableStateOf(activity.pagePrefs().getBoolean("hide_hidden", true)) }
    var tabletMode by remember { mutableStateOf(activity.pagePrefs().getBoolean("tablet_mode", false)) }
    var thumbText by remember { mutableStateOf("正在统计…") }
    var netText by remember { mutableStateOf("正在统计…") }
    var defaultText by remember {
        val dest = activity.pagePrefs().getString("default_uri", "")
        val name = activity.pagePrefs().getString("default_name", "")
        mutableStateOf(
            if (dest.isNullOrEmpty()) "尚未设置默认接收目录"
            else if (name.isNullOrEmpty()) "当前：$dest"
            else "当前：$name"
        )
    }
    /* 缩略图缓存占用要遍历目录，后台算完再回填 —— 别在首帧卡住。
       线程 + Handler，不引协程（本项目没引 kotlinx-coroutines）。 */
    androidx.compose.runtime.DisposableEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val worker = Thread {
            val bytes = try { activity.pageThumbCache() } catch (e: Exception) { -1L }
            /* 网络缓存：远程文件预览/打开时下载的临时副本，和缩略图分开统计、分开清。 */
            val netBytes = try { activity.pageNetworkCache() } catch (e: Exception) { -1L }
            handler.post {
                thumbText = if (bytes > 0) android.text.format.Formatter.formatFileSize(activity, bytes) + " · 点击清除"
                else "当前没有缓存"
                netText = if (netBytes > 0) android.text.format.Formatter.formatFileSize(activity, netBytes) + " · 点击清除"
                else "当前没有缓存"
            }
        }
        worker.start()
        onDispose { }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 28.dp),
    ) {
        PageSwitchRow("不显示隐藏文件", hideHidden, card, outline, text, accent) {
            hideHidden = it
            activity.pageSetHideHidden(it)
        }
        PageSwitchRow("平板模式", tabletMode, card, outline, text, accent) {
            tabletMode = it
            activity.pageSetTabletMode(it)
        }
        PageSection("存储", secondary)
        PageTextRow("清除缩略图缓存", thumbText, card, outline, text, secondary) { activity.pageClearThumbs() }
        PageTextRow("清除网络缓存", netText, card, outline, text, secondary) {
            activity.pageClearNetworkCache()
            /* 清完重新统计一次：副标题要从占用数字变回「当前没有缓存」。 */
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            Thread {
                val bytes = try { activity.pageNetworkCache() } catch (e: Exception) { -1L }
                handler.post {
                    netText = if (bytes > 0) android.text.format.Formatter.formatFileSize(activity, bytes) + " · 点击清除"
                    else "当前没有缓存"
                }
            }.start()
        }
        PageTextRow("清除默认目录", defaultText, card, outline, text, secondary) {
            activity.pageClearDefaultFolder()
            /* 重新进一次页面：默认目录那一栏要跟着变成"尚未设置"。 */
            activity.reopenSettingsPage()
        }
        PageSection("权限", secondary)
        PageTextRow(
            "允许修改系统设置",
            if (android.provider.Settings.System.canWrite(activity)) "已允许 · 点击管理" else "未允许 · 点击授权",
            card, outline, text, secondary,
        ) { SystemBrightnessPermission.openSettings(activity) }
        PageTextRow(
            "完整存储访问权限",
            if (activity.pageFullStorageGranted()) "已允许" else "未允许 · 点击前往系统设置",
            card, outline, text, secondary,
        ) { activity.pageRequestFullAccess() }
    }
}

/* ---------- 下载设置页 ---------- */

@Composable
private fun DownloadSettingsPage(activity: MainActivity) {
    val text = Color(activity.pageAttrColor(R.attr.client_text))
    val secondary = Color(activity.pageAttrColor(R.attr.client_text_secondary))
    val accent = Color(activity.pageAttrColor(R.attr.client_accent))
    val card = Color(activity.pageAttrColor(R.attr.client_card))
    val outline = Color(activity.pageAttrColor(R.attr.client_outline))
    val downloads = activity.getSharedPreferences("downloads", 0)

    var proxyEnabled by remember { mutableStateOf(downloads.getBoolean("proxy_enabled", false)) }
    var proxyText by remember {
        mutableStateOf(
            downloads.getString("proxy_host", "127.0.0.1") + ":" + downloads.getInt("proxy_port", 1080)
        )
    }
    var trackerText by remember {
        val count = LinkDownloadEngine.readTrackers(activity).size
        mutableStateOf(if (count == 0) "未添加，仅用 DHT 寻找资源" else "已添加 $count 个，用于磁力任务")
    }
    var directoryText by remember {
        val fallback = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "FileManager"
        ).absolutePath
        mutableStateOf(downloads.getString("directory", fallback))
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 28.dp),
    ) {
        PageSection("代理（仅磁力／BT）", secondary)
        PageHint("通过本机代理下载磁力任务，ed2k 任务始终直连。", secondary)
        PageSwitchRow("使用 SOCKS5 代理", proxyEnabled, card, outline, text, accent) {
            proxyEnabled = it
            downloads.edit().putBoolean("proxy_enabled", it).apply()
            activity.pageApplyDownloadSettings()
        }
        PageTextRow("代理地址", proxyText, card, outline, text, secondary) {
            activity.pageEditProxy()
            activity.reopenSettingsPage()
        }
        PageSection("Tracker", secondary)
        PageTextRow("Tracker 列表", trackerText, card, outline, text, secondary) {
            activity.pageEditTrackers()
            activity.reopenSettingsPage()
        }
        PageSection("下载", secondary)
        PageTextRow("本地下载目录", directoryText, card, outline, text, secondary) {
            /* LocalDownloadDirectory.choose 内部要拿到 Activity，且选完由我们负责存盘 + 刷新页面。 */
            LocalDownloadDirectory.choose(activity) { selected ->
                activity.pageSetDownloadDirectory(selected)
                activity.reopenSettingsPage()
            }
        }
        PageTextRow("ed2k 服务器", "自定义服务器（选填，主机:端口）", card, outline, text, secondary) {
            activity.pageEditEd2kServer()
            activity.reopenSettingsPage()
        }
        PageHint("修改代理或 tracker 后，正在下载的磁力任务立即生效。", secondary)
    }
}
