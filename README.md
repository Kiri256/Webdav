# WebDAV Manager

Android 本地与 WebDAV 文件管理器，支持图片浏览、音视频播放、文本编辑和跨存储文件操作。技术栈：Kotlin、Jetpack Compose、AndroidX ViewModel/DataStore、OkHttp、Coil、libmpv、Android Keystore；支持 Android 8.0（API 26）及以上。

## 功能与使用

- **存储**：本地首次进入 `/storage/emulated/0`，无需选择文件夹；之后恢复上次目录。Android 11+ 通过本地工具栏的 `Storage access` 授权。底部固定“本地 1、本地 2、WebDAV 1、WebDAV 2”四个标签，各自记住目录、滚动位置、搜索、排序和视图；两个 WebDAV 标签共用同一个连接。剪贴板可跨标签和存储使用。
- **浏览**：普通页面保留系统栏，工具栏始终显示；点击当前路径打开编辑弹窗，可直接跳转目录，失败保留原列表。WebDAV 输入连接根目录内的路径，本地输入文件系统路径；SAF 目录须输入授权范围内的目录 URI。搜索按钮打开悬浮弹窗，关闭保留查询，清空解除筛选。支持列表/网格、横屏、当前目录名称搜索、按名称/大小/修改时间/类型排序、目录位置记忆（最近 200 个视图）。搜索不递归，文件夹属性不递归统计大小。
- **文件操作**：工具栏提供 New folder、New text 和本地 Storage access；长按进入多选，底部操作栏提供复制、移动、删除、属性和完成；本地及 WebDAV 支持新建文件夹、文本文件和重命名。同名粘贴默认安全跳过，也可选择保留两份。流式传输按当前文件已传/总字节数显示进度，同时显示阶段和已处理项数；服务器内部 `COPY`/`MOVE` 显示等待状态，不虚构字节进度。详情分类显示完成、失败、跳过、取消和未处理项；核对两端后可在原目标目录重试失败及剩余项。传输可取消，不设人为单文件大小上限，仍受设备空间和服务器能力限制。
- **安全行为**：删除前复核版本；本地及跨存储移动先复制并校验，再删除源；WebDAV 同服务器移动使用 `MOVE`。无法安全删除的源项会保留并说明。未知格式使用 `Open with other app`，通过只读缓存副本分享。
- **图片**：支持左右切换、缩放、GIF 和可隐藏浮动栏；文件名、返回、隐藏和删除操作紧贴状态栏下方，避免重复留出系统栏间距。
- **音视频**：播放器基于 libmpv，支持进度恢复、后台暂停、浮动控件和视频全屏；画面左右滑动预览进度、松手跳转，横竖屏切换、菜单选择 0.5～2 倍速。菜单提供解码、音轨和统一字幕面板。字幕支持内嵌、本地选取和同目录匹配、自动加载开关、取消和重试、时间偏移、编码与字号；按视频来源及版本恢复偏好，本地文档恢复要求持久读取授权。字幕成功切换后才清理旧副本。远端不支持 Range 或播放失败时提供最大 1 GiB 的下载播放选项。
- **文本**：UTF-8、BOM、最大 64 KiB；冲突时保留草稿。

## 传输与缓存边界

跨存储传输逐文件流式复制、SHA-256 校验后发布临时目标；目录最多 10,000 项、64 层，并预留磁盘空间。响应丢失或中断后下次启动显示 `Needs review`，不会自动续传或删除源；重试前检查源和目标。失败清理只处理身份、版本和内容仍匹配的目标，不确定目标保留。

远端播放经本机回环代理转发，由现有 WebDAV 客户端处理认证和 Range；播放器使用约 30 秒预读，当前没有持久化磁盘分段缓存。缓存额度：缩略图 100 MiB、完整媒体下载 1 GiB、外部打开副本单文件 256 MiB/总额 512 MiB；外挂字幕副本最大 20 MiB。下载包含空间检查、取消和临时文件清理，这些额度独立于复制/移动。

libmpv 固定依赖位于 `app/libs/`，来源、版本与校验值见其中的依赖说明；第三方许可信息位于 `app/src/main/assets/third_party_licenses.txt`。

## 代码导航

主要代码位于 `app/src/main/java/com/example/webdavmanager/`：`MainActivity`/`LocalBrowserScreen`/`FileActionsUi` 负责浏览、工具栏和搜索弹窗；`BrowserViewModel` 管理状态和操作；`DavProtocol`/`WebDavClient` 负责 WebDAV；`DirectFiles`/`LocalStorage` 负责本地存储；`CrossStorageTransfer` 负责跨存储传输；`BrowserStateStore`/`FolderPositions`/`TransferJournal` 负责恢复状态；`ImageScreens`/`MpvVideoPlayerScreen`/`MpvWebDavProxy`/`MediaCache` 负责媒体界面、播放代理和缓存；`TextEditorScreen` 负责文本编辑。

开发约束、路径安全、测试要求和提交规则见 [AGENTS.md](AGENTS.md)。

