# 客户端安装测试版 0.1

- 包名：com.cj.music.preview，与 FTP 服务端独立安装。
- Android 10+，minSdk 29 / targetSdk 34，debug 签名。
- 可用：SAF 目录授权、多标签、排序、搜索、长按多选、系统打开方式/单文件与多文件分享、新建/重命名/删除、文件复制/移动、侧滑抽屉、皮肤与图片背景。
- 尚未实现：FTP/FTPS 引擎、远程目录与上传下载、目录递归复制/移动、TF 容量查询、后台任务持久恢复。
- 本地操作使用真实文件；同名复制拒绝覆盖；删除前二次确认。
- 背景图片采用系统文档选择器与持久读授权，采样解码至最长边 1600px；图片删除或授权撤销后需重新选择。

构建：`:client-app:assembleDebug --offline`。
验证：Android 14 模拟器成功安装和启动；实际授权专用 ClientSmoke 目录、列举 sample.txt、长按选择、启动系统 Sharesheet；侧滑抽屉与皮肤设置页面通过运行验证。没有声称已经验证实机速度或所有文件提供者。
完整 app lint 未完成：现有 Gradle/AGP 离线模型解析在 generateDebugLintReportModel 抛出 `ModuleVersionResolveException.getMessage` 空指针。Java 编译、Android 资源链接、APK 打包与签名验证已通过。
