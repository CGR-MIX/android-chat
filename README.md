# 点点直连 · 原生 Android 聊天

独立 Kotlin / Jetpack Compose 客户端。不是 WebView，不依赖前端构建，也不替换现有 Capacitor 工程。

本目录是自包含的独立仓库，从 `ddzhilian` 主仓库拆分而来（原先位于其 `android-chat/` 子目录）。构建脚本、Gradle Wrapper 与包脚本都在仓库内部，克隆后可独立构建。

## 构建与安装

在本仓库根目录运行：

~~~sh
npm run android:chat:apk
# 同时运行 Android 协议单元测试、Lint 和 APK 构建
npm run android:chat:check
~~~

产物：
- `output/ddzhilian-chat-debug.apk`（可直接侧载安装的调试签名包）
- 同目录 `.sha256` 文件

工具链：JDK 17 或 21、Android SDK Platform 36、Build Tools 35.0.0 或兼容版本。Windows 构建脚本自动识别 Android Studio JBR 和默认 SDK 路径；其他位置设置 `JAVA_HOME`、`ANDROID_HOME`。也可在 Android Studio 中直接打开本目录。Gradle Wrapper 固定为 8.13，带发行文件 SHA-256 校验。首次构建需要联网下载 Maven 依赖。

包名：`com.ddzhilian.chat`，可与旧网页封装版同时安装。Android 8.0 / API 26 起可安装；Android 13 / API 33 起启用完整液态玻璃，旧系统自动使用实色界面。

这是开发调试签名，不是商店发布签名。发布前需配置自己的签名密钥、版本号及商店隐私声明；不要把私钥或密码提交到仓库。Release 配置禁止明文 HTTP，Debug 为局域网联调允许 HTTP。

## 连接服务器

默认地址与当前线上网页实际使用的聊天后端一致：`https://ws.ddzhilian.com`（网站前端为 `https://ddzhilian.com`）。设置页不再展示服务器地址输入框或修改说明；保存设备名称、外观不会改变既有连接配置。程序从相同 origin 派生：
- WebSocket：`/ws`
- 鉴权历史消息：`/api/history/text`
- 原始流附件上传：`/api/history/upload`
- 附件下载 / 撤回：`/api/history/download/:id`、`/api/history/{text,file}/:id`

**2026-10-09 已核对线上页面实际配置：后端为 `https://ws.ddzhilian.com`，`/health` 返回 200，`wss://ws.ddzhilian.com/ws` 握手成功。网站根域名只承载前端，不能把根域名 `/ws` 的 404 判定为线上故障。最终 APK 已用全新安装状态连接线上并加载公共房间。此次没有改动线上部署。**

本地调试使用独立模拟器及独立后端数据目录。调试包可通过 Android Studio Device Explorer 修改应用私有 `shared_prefs/native_chat.xml` 的 `server` 字段（停止进程后操作），模拟器地址为 `http://10.0.2.2:8789`。这只是开发联调方式，不向普通用户暴露服务器切换入口。测试结束恢复原配置；交付 APK 内默认地址仍为线上后端。与网页互通时，两端必须连接同一后端。

## 已实现

- 公共房间、房间 ID 加入、设备发现、连接码、新建私聊。
- 原生消息列表、按房间草稿、文字发送、稳定消息 ID、每房间 FIFO 发送队列、失败点按重试。
- WebSocket 目录与在线状态同步，独立 HTTP 历史分页、加载更早消息、自己的消息撤回、复制。
- 使用 Android 系统文件选择器上传图片 / 文件（最大 500 MB）；接收的附件写入 `Download/点点直连`（API 29+ 走 MediaStore，无需存储或通讯录权限），点按即用系统默认应用打开。
- 指数退避重连、握手超时、过期鉴权刷新；设备身份按后端地址隔离，界面设置不再修改连接配置。
- 深浅色、键盘避让、系统返回、减少透明度开关；玻璃不透明度固定 40%，设置页不提供调节条。
- 原生 WebRTC 一对一语音 / 视频呼叫、来电接听 / 拒绝、挂断、45 秒无人接听超时、静音、免提、摄像头开关和切换；房间多人在线时选择具体通话对象。
- 通话期间 25 秒信令状态心跳、断线清理、会话过期重建；Android 14+ 麦克风 / 摄像头前台服务。
- 可选的「后台消息与来电通知」：共享单一 WebSocket，常驻通知保持连接；不在锁屏展示聊天正文；初始历史不弹通知。点击消息进入房间，来电通知可拒绝或打开应用接听。
- Dock 按住拖拽选择：玻璃胶囊跟手、轻微形变、标签预览及触觉反馈、松手吸附切换；拖出取消，支持关闭系统动画。
 - **AI 助手**：在房间输入 `@助手名称` 或后台配置的唤醒词（如 `@ai`）即可提问，规则与网页端一致（提及必须在开头，名称后跟空格、冒号或逗号）。名称、唤醒词、记忆轮数、System Prompt、供应商与助手头像全部来自主站管理员后台，App 与网页读写同一份服务端配置；回复由服务端以 `bot` 身份写入房间历史并广播，App 把回复里的 Markdown 渲染成富文本气泡（标题、粗体/斜体/删除线、行内代码、代码块、列表、引用、分隔线、可点链接；未支持的语法原样显示），并在等待时显示「AI 正在思考…」。
- **隐秘管理员模式**：设置页「版本」一行连点 7 次弹出密码验证，用主站管理员账号登录后进入面板，可改 AI 策略（名称/唤醒词/记忆轮数/System Prompt）、供应商与 API Key、历史暂存文件（附件列表、单条删除、全站清空）以及助手头像（PNG/JPEG/WebP/GIF，≤8 MB）。登录时勾选「记住账号密码」会把账号保存在本机（密码用 Android Keystore 的 AES/GCM 密钥加密，Keystore 不可用时放弃保存），之后连点版本行可直接进入；管理员会话 cookie 只保存在内存，杀进程即失效。非超级管理员账号进入后供应商与头像只读，避免清空线上密钥。

## 1.2.0 聊天体验补充

- **图片**：附件选择后显示发送预览，原图 / 压缩选项；JPEG、PNG、WebP 可缩至长边 2048 像素并转 JPEG（不保留透明通道）；原图与其他格式保留原文件。聊天中显示缩略图，多张连发时叠成一张卡片，卡片每张都显示缩略内容（后置卡片低分辨率解码），上下左右滑动切换：划走的卡片动画缩回并落回卡片堆底部，而不是飞出屏幕；点开全屏双指缩放、双击还原、原比例左右切换并保存。
- **会话管理**：长按会话置顶或消息免打扰；未读使用标记，不用不完整快照编造精确未读数。进入后标出已加载未读边界，点「定位最早未读」可继续向前加载。免打扰仅关闭消息提醒，不屏蔽来电。
- **搜索**：会话列表及聊天页搜索文字 / 文件名，点击定位。范围是本地已缓存记录，最多显示最新 100 条匹配，不是全量云端搜索。
- **语音消息**：输入栏切换语音，首次请求麦克风权限后再次按住录音；上滑取消，1–60 秒，AAC/M4A。支持播放进度拖动、听筒 / 扬声器选择（设备无听筒时提示）。离开前台、离开房间、接到电话时停止录音 / 播放。
- **离线**：历史、设备身份隔离的本地数据库、会话偏好、文字和已暂存附件待发送项；原房间结束时保留本地记录，只读展示，不自动把草稿发进其他房间。
- **上传**：显示真实字节进度，允许取消；取消上传保留待发送附件，可手动重试。取消不等于撤回服务端已经接收成功的消息。附件重试复用 historyId。
- **缓存清理**：设置页清理已下载历史和媒体，保留待发送内容、草稿和偏好。离线时无法重新读取已经清掉的历史。离线副本可能保留断线期间服务端删除的旧内容，应以重新联网后的信息为准。
- **附件接收通知**：用户点按附件（或选择打开）后启动独立 dataSync 前台服务。真实字节百分比 / 已接收及总量、未知长度不定进度、取消、完成 / 失败 / 取消状态；通知返回对应房间。接收进度最多每 500ms 更新一次通知，不为缩略图预加载打扰用户。进程被杀不支持断点续传，再次打开会提示中断，需重新接收。

按用户要求，1.2.0 仅执行 APK 编译打包，没有运行单元测试、模拟器或真机交互验证。1.1.0 的实测结果不能当成这一版本新增功能的验证。

### 三星、小米、OPPO 实时通知兼容边界

- Android 16 / API 36+ 使用公开的 ProgressStyle、NotificationCompat.setRequestPromotedOngoing 和短进度文案，并声明 POST_PROMOTED_NOTIFICATIONS；系统没有提升权限或不认可本次活动时保留普通进度通知。
- Android 8–15 回退为标准的常驻进度条。尊重用户关闭通知、通知渠道和实时活动的选择；没有绕过权限。
- 设置页按品牌显示三星 / Now Bar、小米 / 超级岛、OPPO 系 / 流体云的提示，并提供标准通知渠道和系统实时活动设置入口。
- **这不是三个厂商专属接口都已接入或实机认证。** Now Bar、超级岛和流体云是否展示该任务，由对应系统版本和厂商第三方准入规则决定。没有使用未公开的私有 extras、伪装外卖／导航类别、无障碍服务或悬浮窗强行模拟。若必须指定这些位置，需要确定目标机型 / 系统版本，拿到其开放接口资料及可能要求的开发者准入，再做厂商专项集成；当前只能承诺已接入标准 API 与回退代码，不能承诺特定位置展示。
- 这属于本地任务进度通知，**未接入云端或杀进程后的离线推送**。

Android 官方接口与规则：https://developer.android.com/develop/ui/views/notifications/live-update

## 界面技术

真实依赖 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.6)：

~~~kotlin
implementation("io.github.kyant0:backdrop:1.0.6")
~~~

固定采用 Android 专用稳定版本 1.0.6，而非目前面向 Compose Multiplatform 的 2.x 工具链。滚动内容通过 `rememberLayerBackdrop / layerBackdrop` 录制；顶部栏、底部导航和输入栏通过 `drawBackdrop` 的 `vibrancy + blur + lens` 绘制真实模糊和折射，不是 CSS 模拟。玻璃填充色的不透明度固定为 40%（`GLASS_OPACITY`），设置页不再提供调节条、也不再读取历史保存值；模糊半径仍随该值下降、在 `MIN_GLASS_OPACITY` 处归零，所以 40% 时玻璃确实通透可见背后内容。启用「减少透明效果」会停止这些效果并使用实色。
设置页另外移植了上游的 `LiquidSlider`、`LiquidToggle` 与 `LiquidButton` 三个液态玻璃控件（`app/src/main/java/com/ddzhilian/chat/liquid/`）。它们在上游仓库中只以示例 app 的源码形式存在、未发布到 Maven，因此按同一 1.0.6 标签的源码移植并仅改包名，配套补上 `io.github.kyant0:shapes:1.2.0`。视觉与绘制逻辑保持上游原样，但**输入行为有两处修正**：滑块手势区覆盖整个滑杆、高度 44dp（上游只在 40×24dp 的胶囊上接收拖动，而可视轨道仅 6dp 高，手机上几乎拖不动）；三者都改用 Compose 标准的 `detectHorizontalDragGestures` / `detectTapGestures`（上游示例自带的 `inspectDragGestures` 只响应点按、拖动无效）。这些控件还必须绘制在**它们所采样的层之外**：若把玻璃控件放进它自己采样的录制层内，`RenderNode` 会自我递归并以原生栈溢出（SIGSEGV）崩溃，因此设置页用 `rememberCanvasBackdrop` 现画一份壁纸作为采样源，而不是复用页面自身那一层。控件都带 `reduce` 参数：开启「减少透明效果」时**几何完全不变**，只把玻璃表面换成实色（`surface` + `outlineVariant` 发丝描边），尺寸与按压形变照旧——实测开启前后开关轨道 64.0×28.0dp、旋钮 40.0×24.0dp、滑块滑钮 40.0×24.0dp、保存按钮胶囊高 48.0dp 均逐像素一致；早期版本在这里换回过 Material3 的 `Switch` / `Slider` / `Button`，会在拨动的瞬间改变控件形状和大小，已移除。**不透明度固定 40% 后 `LiquidSlider` 已从设置页下架**（文件保留在代码库，暂无界面入口），设置页现在用的是 `LiquidToggle`（减少透明效果）与 `LiquidButton`（保存设置、版本更新）。库 Apache-2.0 许可随 APK 收录于 assets/licenses。
## 版本更新通道

更新源就是本仓库自身（`CGR-MIX/android-chat`），没有自建更新服务器，也没有第三方更新 SDK：

- 设置页「版本更新」用 git smart HTTP 读仓库的 refs 广告（`info/refs?service=git-upload-pack`），从 `refs/tags` 里取语义版本最高的 tag，再与 APK 的 `versionName` 比较，因此不消耗 GitHub API 配额。
- 发布一个版本 = `git tag v1.5.0 && git push origin v1.5.0`，并把 `output/ddzhilian-chat-debug.apk` 作为该 tag 的 Release 附件上传。这是当前唯一的安装包分发方式：`.gitignore` 保留 `*.apk` 与 `output/`，安装包不进仓库历史，因此代码里的 raw 回退只在有人真的把安装包提交进 tag 时才会命中。
- 下载完成后先校验文件头是 zip（APK），再通过 `FileProvider` 交给系统安装器。Android 8 起需要用户先允许本应用「安装未知应用」，未授权时跳到对应系统设置页，授权后回到设置页再点一次即可。
- 仓库里还没有版本 tag、或 tag 里出现预发布版本（含 `-` / `+`）时不提示更新，并给出明确文案而不是假装已是最新。
- 检查与下载失败只更新该区块的状态文案，不影响聊天与后台连接；整个过程不上传设备信息。

## 音视频与通知

使用 `io.github.webrtc-sdk:android:144.7559.15`，复用现有服务端 `call-*`、`signal`（`purpose: media`）和 `rtcConfig.iceServers`，兼容网页端信令。跨网络直连失败时依赖后端提供有效 TURN；本轮仅验证本地隔离网络，不保证未测试网络下的穿透能力。

只在点击拨号 / 接听时申请麦克风和摄像头权限；收到来电时不会自动打开录音或摄像头。接通期间可后台持续通话，挂断释放采集和音频焦点。拒绝通知权限不影响应用内聊天 / 通话。

**当前后台通知是长连接通知，不是 FCM / 厂商离线推送。** 开关默认关闭；开启会显示系统常驻通知并增加耗电。进程被系统杀死时会尝试恢复前台服务，但不保证送达；强制停止、离线、OEM 省电限制下不会可靠唤醒。应用内文字也明确注明这一边界。

要实现杀进程后的离线推送，还需要选择并配置推送平台（Google Play 设备可用 FCM；国内无 GMS 设备需对应厂商或聚合通道）、提供 Android 应用配置与服务端凭据，新增经过身份鉴权的设备推送 token 注册与离线消息投递，然后单独部署后端。本轮没有假装这些配置已存在，也未改动线上部署。

## 边界与隐私

- AI 助手只使用主站后台配置的供应商与提示词；App 端不提供模型切换，管理员会话不落盘。暂不含文件 WebRTC 点对点传输、账户登录、群组音视频或 FCM / 厂商离线推送。
- 图片支持缩略图、发送预览和全屏缩放／切换／保存；超长图预览会采样解码，保存仍是服务器原文件。图片卡片堆叠时每张都显示自己的缩略内容，划走的那张会动画缩回堆叠底部。GIF 当前显示静态预览。
- 聊天使用服务器转发和历史存储，不是端到端加密。公共 / 局域网房间不要发送敏感信息；沿用现有服务端设备身份模型，不应当作强账户认证。
- 草稿存于应用私有存储；鉴权 bearer token 仅存内存。已发消息由服务器保存。文字与已完成本地暂存的附件队列、失败项保存到应用私有 SQLite / 文件目录，进程重启后恢复；待发送项联网后按房间顺序发送，明确失败项需点按重试。卸载或清除应用数据仍会删除这些内容。
- 卸载清除设备身份；服务器重启可能结束私聊房间，客户端会提示重新加入。历史保留期限由后端配置决定。
- APK 关闭云备份 / 设备迁移备份，避免复制设备身份或草稿。

## 服务端兼容性修正

联调发现：显式新建同 LAN 私聊时，原来会覆盖 `RoomRegistry.byLanKey`，导致下一台设备自动加入私聊。此次仅将局域网索引写入限制为 `reason === 'lan-discovery'`，并添加两个聚焦回归用例。使用此修正需要更新并重启自己的后端；**APK 无法替尚未更新的线上后端修复该问题**。

测试：`npm run test -- tests/room-registry-lan-isolation.test.ts`（该用例与 `server/` 代码属于主仓库 `ddzhilian`，不在本仓库内）。

## 文件索引

- `app/src/main/java/com/ddzhilian/chat/ChatProtocol.kt`：协议模型、URL 验证、消息去重。
- `ChatViewModel.kt`：连接、鉴权、队列、历史、附件和草稿。
- `ChatUi.kt`：Compose 界面和 Backdrop 效果。
- `ChatMediaUi.kt` / `AttachmentFiles.kt`：图片预览、图库、附件与进度 UI。
- `liquid/LiquidSlider.kt` / `liquid/LiquidToggle.kt` / `liquid/LiquidButton.kt` / `liquid/LiquidInteraction.kt`：从 AndroidLiquidGlass 1.0.6 移植的液态玻璃滑块、开关、按钮及拖动/按压动画工具类。三者都支持 `reduce` 实色外观：几何与按压形变不变，只替换表面绘制。
- `VoiceController.kt`：语音消息采集、播放与音频路由。
- `LocalChatStore.kt`：身份隔离的本地 SQLite、偏好与附件暂存。
- `AttachmentTransferService.kt`：附件接收前台服务及标准实时进度通知。
 - `ChatMarkdown.kt` / `ChatMarkdownUi.kt`：聊天消息的 Markdown 子集解析（纯 Kotlin，可单测）与 Compose 富文本渲染；`plainChatText` 供会话预览、搜索结果和复制降级为纯文本。
 - `app/src/test/java/com/ddzhilian/chat/MarkdownTest.kt`：16 个 Markdown 解析用例（行内样式、列表与续行、代码块、未闭合围栏、AI 富文本回复、纯文本降级与不吞字）。
- `app/src/test/java/com/ddzhilian/chat/ChatProtocolTest.kt`：5 个协议测试。
- `UpdateProtocol.kt` / `UpdateChannel.kt` / `UpdateUi.kt`：git 版本更新通道的协议解析、检查下载与设置页区块。
- `app/src/test/java/com/ddzhilian/chat/UpdateProtocolTest.kt`：6 个更新通道测试（refs 广告、版本解析与比较、下载地址回退）。
- `scripts/build-android-chat.mjs`：可复现构建及产物校验。

实测详情见 [QA.md](QA.md)。

