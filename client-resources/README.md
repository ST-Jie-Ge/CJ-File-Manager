# Android FTP/FTPS 客户端资源包

此模块是原生 Android 资源库，最低 Android 10（API 29）。不含可启动 Activity、传输引擎或演示数据；不接入现有服务端 app。`settings.gradle` 注册此库以便独立编译验证，服务端没有新增依赖。

## 已准备

- 浏览器式可滚动标签栏：自定义目录标签、关闭按钮、新增按钮、所有标签入口；每个标签可指向本机、TF 或远程目录。
- 无顶部应用标题、宣传语、侧边栏按钮或常驻多选按钮；搜索位于右上角，搜索输入框初始隐藏。
- 文件列表、长按选择状态、排序面板（名称/时间/大小/类型、升降序、文件夹优先）。
- 侧边栏资源：本机/TF 容量、默认目录、网络连接、任务、设置、个性化。
- 文件操作菜单：其他应用打开、分享、复制、移动、重命名、删除、全选与取消。
- 固定海蓝配色，浅色/深色颜色资源；纯色与渐变默认背景。
- 背景图片层、遮罩层与个性化设置布局；图片选择、移除、裁剪方式和遮罩强度入口。
- 中英文文案、原创 24dp 矢量图标、独立导出缓存 FileProvider 路径。

## 接入约定

宿主使用固定海蓝配色的 `Client_Theme.FtpClient`。切换浅色/深色主题后重建界面。图标继承语义色，不用位图染色副本。

TabLayout 为每个标签 inflate `client_tab`，维护稳定 ID、后端标识、目录 URI/远程路径、排序、搜索和滚动位置。新增标签不限制目录类型或重复位置。关闭当前标签选择相邻标签；最后一个标签保留。服务器连接池独立于标签寿命。

`client_browser_shell` 提供 DrawerLayout，但没有打开按钮。宿主实现文件列表区域向右滑动打开：达到 touchSlop 后确认水平意图，再拦截；排除系统手势 insets 和底部手势区域，不占用全屏系统返回边缘。标签横向滚动、文件纵向滚动、长按选择和输入框不得被侧滑抢走。抽屉内左滑、点遮罩和系统返回关闭。必须给文件列表增加无障碍“打开位置侧边栏”自定义操作，并支持键盘，不能只依赖触摸手势。

文件行 longClickable 只声明入口，宿主必须注册长按监听进入 ActionMode；后续单击切换选中状态，返回退出。用 stable item ID 保存选择，不能用排序后的 position。普通单击文件交给默认查看器，文件菜单和选择菜单都提供“用其他应用打开”。目录禁用外部打开/分享；多选禁用单文件打开和重命名。

## 其他应用打开与分享

用 Android `ACTION_VIEW` + `Intent.createChooser` 实现“其他方式打开”。分享单文件用 `ACTION_SEND`，多文件用 `ACTION_SEND_MULTIPLE` + 系统 Sharesheet。仅传 `content://` URI；设置精确 MIME，混合类型聚合为 `image/*` 等共同类型，否则 `*/*`。每个 URI 同时写入 ClipData，并加 `FLAG_GRANT_READ_URI_PERMISSION`；默认不授写权限。捕获无处理应用、撤销授权、文件删除等失败。

远程文件先完成下载到私有 `cacheDir/client-exports/<任务ID>/`，原子提交后才打开 chooser；下载失败/取消不得分享半文件。多选分享等所有选中文件准备成功再启动；先检查空间，显示可取消的准备进度。不要在回调或分享面板关闭时立即删缓存；接收应用可能稍后读取。清理策略需基于过期时间和活跃租约。

宿主声明非导出的 FileProvider，authority 使用 `${applicationId}.client.exports`，`grantUriPermissions=true`，paths 引用 `@xml/client_export_paths`。此模块不自动合并 provider，避免尚无调用方时增加暴露面。现有 SAF content URI 可按原授权直接转授；无法转授或来自裸 File 的文件先复制到导出缓存。不暴露 root-path 或整个外置存储。

## 图片背景与主题

Android 10+ 统一使用 `ACTION_OPEN_DOCUMENT`、CATEGORY_OPENABLE、`image/*`，无需为了背景申请完整存储或照片库权限。接收系统返回的临时读权限后，在 IO 线程采样解码并规范化到应用私有目录，成功后原子替换，旧背景直到替换成功才删除；也可采用持久 URI 权限方案，但须处理提供者移动/删除和授权撤销。

解码前读尺寸、限制输入字节/像素、按显示尺寸采样、处理 EXIF 方向；拒绝不可解码图片并保留旧背景，不在主线程解码，不把原图常驻内存。处理动画图片时以静态帧为背景。只保存私有路径/缩放模式/遮罩参数，不把 Bitmap 放进状态。

背景仅在文件内容区域；抽屉、操作菜单、对话框保持不透明。遮罩强度表示不透明度 0–100，默认 85%；设置图像后允许调整，但文字需保持可读。浅深主题采用不同遮罩语义色，不能反复对已含 alpha 的颜色叠乘导致设置失真。主题模式支持跟随系统、浅色和深色，配色固定为海蓝。

## Android 10+ 与存储

minSdk=29。此准备阶段复用工作区 compileSdk=34 与已缓存依赖，未声称满足未来商店发布 target 要求；正式客户端需独立确定 target/发布要求并升级工具链。通过 SAF 使用用户授权目录，Android 11+ 根目录/Download/Android/data 等树授权限制需在产品中明确处理，不能承诺任意根目录可写。TF 容量拿不到时显示未知，不显示虚构 0；卷移除取消相关 IO 并保留可恢复标签。图片背景不依赖 MANAGE_EXTERNAL_STORAGE。

## 验证

资源库可用 `:client-resources:assembleDebug :client-resources:lintDebug` 编译、链接和静态检查；产物是 AAR，不导出 APK。资源就绪不表示点击事件、手势、权限流程、真实网络传输已经完成。后续需接入宿主，并在 Android 10/11/14+ 测试系统返回手势、第三方查看器/多文件分享、日夜主题切换与大图内存。

官方接入依据：
- https://developer.android.com/training/secure-file-sharing
- https://developer.android.com/training/sharing/send
- https://developer.android.com/training/data-storage/shared/documents-files
