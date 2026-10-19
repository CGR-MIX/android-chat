# Android 聊天验证记录

日期：2026-10-09。仅使用本机隔离测试后端；未向线上房间发送测试内容、未部署服务。

> 仓库拆分说明：本文件记录 Android 客户端在主仓库 `ddzhilian/android-chat/` 时期的验证过程，其中的 `npm run ...`、`output/android-chat/`、`.tmp/android-chat-*.log` 路径均相对主仓库。拆分为本独立仓库后，客户端构建命令为 `npm run android:chat:apk` / `npm run android:chat:check`，产物路径为 `output/ddzhilian-chat-debug.apk`；涉及 `tests/`、`server/` 的用例仍属主仓库。

## 构建

- `npm run android:chat:check`：5 个 Kotlin 协议用例通过、Lint 无错误、assembleDebug 成功。
- Lint 的依赖升级 / 目标 API / KTX 建议仍保留，未通过 baseline 隐藏错误。
- `npm run test -- tests/room-registry-lan-isolation.test.ts`：2 个私聊隔离回归用例通过。
- `npm run build --prefix server`：服务端 TypeScript 编译通过。

## 运行环境

- Android 36.1 x86_64 模拟器，1080 × 2400，420 dpi，SwiftShader 图形渲染。
- 隔离复制的服务端运行于本机 8789，使用独立 data 目录，没有读取生产凭据。
- APK 指向 `http://10.0.2.2:8789`；第二、第三个客户端使用仓库相同 WebSocket / HTTP 协议。不是完整浏览器前端 E2E。

## 已通过的实际交互

1. APK 安装与启动；原生 Compose 屏幕、Backdrop 折射 / 模糊，深浅色与键盘避让截图检查。
2. 设置页填写后端根地址，WebSocket hello / welcome、公共房间及在线设备可见。
3. 网页协议客户端发中文和 emoji，Android 收到；Android 发文字，服务器历史和另一客户端可读。
4. 连续发送 rapid_1 到 rapid_4，服务器完整保存且顺序正确。
5. 从在线设备新建私聊并双向发信。修复后新上线的第三台同 LAN 设备未被自动加入；读取私聊历史返回 403，成员数保持 2。
6. 系统文件选择器上传 53 字节文本（含中文），另一客户端下载内容一致；APK 系统保存对话框保存后，读取设备文件内容一致。
7. 长按自己的文字并撤回，服务端历史中该消息消失。
8. 主动替换测试设备的 WebSocket 以触发断线重连，草稿保持；恢复后发送成功，新的历史鉴权令牌生效。
9. 冷启动后加载最近 50 条，再点击「加载更早的消息」读到之前的中文历史。测试数据总量超过一页。
10. UI 内无 FATAL EXCEPTION 记录；深色主题前景可读。
11. 修正默认地址为线上真实后端后，最终 APK 清除测试数据重新安装，成功显示「在线」及公共聊天室。未向线上发送消息。

本机截图与 APK 放在仓库忽略的 `output/android-chat/`，构建日志在 `.tmp/android-chat-*.log`。测试后关闭临时协议客户端、模拟器和隔离服务，不影响现有开发服务。

## 未验证 / 限制

- 未连接物理手机；未测试 API 26–32 真机效果、后台长驻、OEM 电池策略或商店发布。
- 未跑完整网页端 E2E 或整个仓库测试；网页源码未调整，后端仅修正 LAN 索引隔离。
- 已核对生产实际端点：`https://ws.ddzhilian.com/health` 返回 200，`wss://ws.ddzhilian.com/ws` 握手成功。完整收发使用隔离后端验证，未在公共线上房间发送测试内容。
- 新版 APK 的原生界面无需部署网页，但私聊隔离修正必须单独更新自己的后端。

## Dock 拖拽动画补充验证（2026-10-09）

- Dock 使用单个可移动玻璃胶囊；按住缩放、水平跟手、跨标签高亮预览，松手弹簧吸附并提交页面切换。按下就反馈，无需等待长按定时器。
- 模拟器验证：点击选择、按住 550ms 后拖动、向右拖至设置、反向拖回消息、拖出 Dock 取消并回原项、系统动画比例为 0 时正常切换、减少透明度模式下正常拖动。
- 拖动过程中页面保持不变，释放后页面与选中标签一致。保留 Tab 选择语义及键盘 / 无障碍点击入口；TalkBack 和真机触觉强度未实测。
- `npm run android:chat:check` 通过（5 个已有协议测试、Lint、APK）；crash 日志无 FATAL EXCEPTION。
- 演示录屏：`output/android-chat/dock-drag.mp4`；截图：`dock-idle.png`、`dock-mid-drag.png`。

## 原生通话与后台通知续作（2026-10-10）

版本 `1.1.0` / versionCode `2`。本轮继续已有 Android 工程，不修改线上部署，不覆盖工作区原有的网页、服务端及其他未提交修改。

### 实现

- 设置页移除服务器输入框及其说明；保存设置仅更新名称和透明效果，不更改服务器或设备身份。
- 保留已完成的首页 / 聊天室文案清理、胶囊 Dock 和向外突出的拖拽玻璃选择框。
- 原生 WebRTC 音视频：主动拨号、接听 / 拒绝、挂断、静音、免提、摄像头开关 / 切换、无人接听及连接超时。
- Application 持有单一聊天连接，Activity 与前台服务共用；按需申请麦克风、摄像头和通知权限。
- 后台在线消息 / 来电通知、通知点击进入房间、通知栏拒接和打开接听；后台开关默认关闭。
- 修复会话闲置清理后仍复用旧 ID 的问题：拨号前重新确保会话，同房间同设备的新 ID 替换旧 ID；通话中每 25 秒发送状态心跳，防止服务端 120 秒闲置清理。
- 挂断取消准备流程、媒体、心跳、通知和音频焦点；取消后的过时服务启动请求不能重新拉起媒体前台状态。

### 本轮验证

- `npm run android:chat:check`：9 个 Kotlin 协议测试通过，Lint 0 errors / 20 warnings，APK 构建成功。未通过 baseline 忽略错误；warning 主要为已有依赖版本和 API 建议。
- Android API 36.1 x86_64 模拟器 + Chrome 虚拟音视频设备 + 本机隔离后端 8789。浏览器使用兼容现有网页信令的测试页，**不是完整 Next.js 网页 UI E2E**。
- Chrome 发起视频 → Android 接听：双向音频和双向视频轨道均有实际 RTP 数据；模拟器显示远端画面和本地预览。
- 视频退至后台 130 秒后仍 `connected`，浏览器收到音频 272119 bytes、视频 3151214 bytes / 1597 decoded frames；发出音频 432989 bytes、视频 7853637 bytes / 2829 encoded frames，服务端会话保持 connected。
- Android 主动语音 / 视频 → Chrome 自动接听：SDP offer / answer、ICE、真实媒体收发通过。主动视频最终复测浏览器收到视频 81325 bytes / 77 frames，并发出视频 419586 bytes / 144 frames。
- 静音 / 取消静音、摄像头前后切换、关闭 / 打开及挂断均实操。关闭摄像头及挂断后等待释放，dumpsys media.camera 的 Active Camera Clients 均为空。对端收到 call-hangup 后释放 PeerConnection。
- 后台来电通知显示「拒绝 / 打开接听」；通知拒接后对端收到 call-reject；点击打开接听进入原生通话并成功收发语音。
- 后台新消息显示不含聊天正文的通知；点击进入对应房间，读取 `native_background_notification_qa` 测试消息。
- 设置页只保留设备名称、透明效果、后台通知等设置，没有服务器输入框；主界面标题和聊天页提示删除状态保持。
- 模拟器 crash buffer 无 FATAL EXCEPTION。浏览器测试页曾在 APK 重装暂时离线期间触发 peer undefined，以及在早期会话失败时读取空 PC；已明确区分这些测试脚本错误与最终通过的媒体结果。

证据截图：`output/android-chat/native-settings-final.png`、`native-video-final.png`、`native-video-background-resume.png`、`native-outgoing-video.png`、`native-audio-final.png`、`native-incoming-notification.png`、`native-message-notification.png`。构建日志：`.tmp/android-chat-native-check.log`。

### 尚未完成 / 未验证

- **FCM / 手机厂商离线推送未接入**：没有项目配置、凭据或目标厂商信息。本轮完成的是前台服务维持连接时的通知，不保证应用被杀、强制停止、离线或 OEM 省电限制下的送达。
- 未验证物理手机麦克风 / 扬声器音质、蓝牙音频路由、OEM 后台限制、API 26–32 或跨公网 TURN；不能由模拟器 RTP 统计推断真实设备音质或所有网络可用。
- 未重跑整个网页仓库测试，未升级或部署服务器；原有服务端 LAN 私聊隔离修改保持原样。

最终产物 SHA-256：`6bf094bec266296e953e5d4f1c46f77e827d4913148bc7d3d22a97c373da2f0b`。

- 最终 APK 的 apksigner verify 通过（v2 签名），zipalign -c -P 16 4 通过；SHA-256 与旁边校验文件一致。


## v1.2.0 图片、语音、离线与附件实时进度（2026-10-10）

本轮按用户要求：**不运行测试、不启动模拟器、不做真机交互验证**。执行的仅是 `npm run android:chat:apk` 编译打包；新增功能不能引用上面 v1.1.0 的实测结果作为证明。

已接入代码：
- 图片缩略图、发送前预览及原图/压缩选择、全屏缩放/左右切换/保存。
- 会话置顶、消息免打扰、未读标记、加载并定位未读、本地缓存消息搜索。
- 按住录制 AAC 语音、上滑取消、播放进度拖动、听筒/扬声器选择。
- 按 origin 与设备身份隔离的 SQLite 历史、待发送文字与附件持久化、队列重启恢复；旧房间结束保留只读本地历史；缓存清理保留待发送内容。
- 上传进度与取消；附件保存使用独立 dataSync 前台服务，真实字节进度、取消、完成/失败通知及返回房间。
- API 36+ 使用 ProgressStyle + 标准 promoted ongoing 请求；较早 Android 使用标准常驻进度条。通知权限被拒不阻止保存，应用内仍显示进度。

兼容性限制：
- 三星 Now Bar、小米超级岛、OPPO 流体云目前仅接入通用 Android API 和标准回退，以及按品牌的设置提示；**没有完成厂商私有接口集成或机型认证**，不能保证在专属位置显示。具体位置展示由 OEM 系统版本、设置及准入规则决定。
- 通知 promotion 规则以 https://developer.android.com/develop/ui/views/notifications/live-update 为依据；此接口只用于用户主动开始的附件接收，不把聊天新消息伪装成实时活动。
- 未做 UI、录音、SQLite 重启恢复、弱网取消、通知或厂商真机运行验证。没有新增云端离线推送、AI 或工具功能，也没有部署服务端。
- 当前是单个附件接收任务；中断重新保存，不支持跨进程断点续传。搜索仅限本地缓存，未读使用标记而非不可靠的精确总数。

构建日志：`.tmp/android-chat-v12-build.log`。产物：`output/android-chat/ddzhilian-chat-debug.apk`，versionName 1.2.0 / versionCode 3。


## v1.4.x 附件撤回同步 / 默认下载 / 打开已存附件 / 图片堆叠展开

本轮仅执行编译打包与协议级静态检查，未运行模拟器或真机交互验证。

### 实现

- **服务端（`ddzhilian` 主仓库）**：新增撤回墓碑 `server/data/history/recalled.json`（`recordRecall` / `listRecalledForRoom`），`deleteText`/`deleteFile` 成功后写入并持久化；新增 `GET /api/history/recalled?roomId=…`，复用 `authenticateHistoryRequest` + `authorizeRoomMember` 鉴权。需单独构建并重启线上后端。
- **客户端撤回同步**：`syncRecalled(roomId)` 拉取该房间已撤回 ID，逐条 `recalled.add` + `local.remove(id, true)` + 从 cache 剔除；在 welcome/snapshot、openRoom、refresh 后调用；并把 snapshot 的 `historyFiles` 作为权威集合删除本地已发送但服务器已不存在的文件消息。
- **默认下载路径**：`AttachmentFiles.defaultDownloadDestination`（API 29+ 用 `MediaStore.Downloads` + `RELATIVE_PATH=Download/点点直连`，API 26–28 用 `Environment` 返回 `file://`），同名自动编号；`WRITE_EXTERNAL_STORAGE maxSdkVersion=28`。
- **已存附件再点打开**：`savedUriFor` / `markSaved`（prefs 记录，不随缓存清理删除）；接收完成 `finishPending` + `markSaved`；UI 点击附件未保存则自动保存、已保存则 `ACTION_VIEW` 打开；菜单/全屏查看按钮按状态显示「打开/保存」。
- **图片堆叠/展开**：重写 `ImageStackCard`，堆叠态固定 4:3、左右扑克牌 peek（首张右侧 2、末张左侧 2、中间各 1），仅横向 `detectHorizontalDragGestures`、不循环、越界回弹、划出后回到底部动画；展开态竖排按原图比例显示，带展开/收起切换与动画。

### 验证

- `npm run android:chat:check`：BUILD SUCCESSFUL（Kotlin 协议测试、Lint、assembleDebug）。APK：`output/ddzhilian-chat-debug.apk`，SHA-256 `a3d57c1104545123bb925d73706e8dd11a7363481f3be0c5cc9a0419bdbe103a`。
- `cd C:/Users/12467/Documents/707/ddzhilian && npm run build --prefix server`：通过。
- 未运行模拟器/真机：上述 4 项行为验收未做实际交互验证，且需求 1 依赖更新并重启线上后端。

本轮校验文件：`a3d57c1104545123bb925d73706e8dd11a7363481f3be0c5cc9a0419bdbe103a  ddzhilian-chat-debug.apk`。

## v1.4.0 玻璃固定 40% / git 版本更新通道

需求：内置一个版本更新通道并用 git 做通道；玻璃透明度固定 40% 且去掉设置页的调节条。

### 实现

- **玻璃固定 40%**：`GLASS_OPACITY = .40f` 取代原先用户可调的不透明度；`ChatViewModel` 不再从 `native_chat` 偏好读取 `glassOpacity`，写入方法 `glassOpacity(value)` 删除，设置页的「玻璃透明度」标题、百分比与 `LiquidSlider` 拉杆一并移除（`LiquidSlider.kt` 文件保留在代码库，暂无界面入口）。渲染层仍按 `MIN_GLASS_OPACITY`→1 的插值衰减模糊半径，所以 40% 时玻璃照样通透可见背后内容；「减少透明效果」行为不变。
- **git 更新通道**（`UpdateProtocol.kt` / `UpdateChannel.kt` / `UpdateUi.kt`）：设置页「版本更新」用 git smart HTTP 读 `https://github.com/CGR-MIX/android-chat.git/info/refs?service=git-upload-pack`，从 `refs/tags` 取语义版本最高的 tag 与 `BuildConfig.VERSION_NAME` 比较；安装包先取 `releases/download/<tag>/ddzhilian-chat-debug.apk`，缺失时回退 `raw.githubusercontent.com/.../<tag>/output/ddzhilian-chat-debug.apk`；下载后校验文件头是 zip，再经 `FileProvider` 交给系统安装器（新增 `REQUEST_INSTALL_PACKAGES` 与 `res/xml/update_paths.xml`，只暴露 `cacheDir/updates`）。预发布 tag（含 `-`/`+`）、非版本 tag、peeled tag（`^{}`）都不进通道；仓库没有版本 tag 时明确提示而不是假装最新。
- 设置页版本号文案改为读 `BuildConfig.VERSION_NAME`，不再硬编码 1.4.0。

### 验证

- `npm run android:chat:check`：BUILD SUCCESSFUL。单测 21 个全部通过，其中新增 `UpdateProtocolTest` 6 个（refs 广告解析、tag 过滤、版本比较、更新判定、下载地址回退）；Lint **0 error** / 30 warning（全部是既有的依赖版本与 KTX 建议，新增文件 0 条）。APK：`output/ddzhilian-chat-debug.apk`，SHA-256 `b09d884631fbca3fdf0c19ee1abd879ad2062a146c6e2dd84784fd5d0420cc31`。
- 通道端点实测：`GET https://github.com/CGR-MIX/android-chat.git/info/refs?service=git-upload-pack` 当前返回 **401**；对照公开仓库 `Kyant0/AndroidLiquidGlass` 与 `torvalds/linux` 同一请求返回 200，`raw.githubusercontent.com/CGR-MIX/android-chat/main/README.md` 返回 404；`git ls-remote origin` 用本机凭据能读到 `refs/heads/main`。结论：`CGR-MIX/android-chat` 是**私有仓库**，匿名客户端读不到版本信息，因此通道要真正可用必须把版本仓库设为公开（或另建一个公开的发布仓库），并至少推一个版本 tag。在此之前设置页会显示「更新通道不可匿名访问（HTTP 401）：需要把版本仓库设为公开，检查更新才能免登录工作。」
- 未运行模拟器/真机：40% 玻璃的实际观感、更新区块的状态机、下载与「安装未知应用」授权流程均未做真机交互验证。

## v1.4.0 点击文件直接用系统默认应用打开

需求：点击文件（附件）时用系统默认方式打开；图片与语音的交互保持不变。

### 实现

- **ViewModel**：新增 `expectOpenAfterDownload(message)` 记下「接收完就打开」的意图，以及一次性状态 `ChatState.openRequest`（`AttachmentOpen(uri, mimeType)`）。`markSaved` 落盘后若命中的是这次点开的消息，就发出打开请求；`downloadChanged(null)`（接收失败 / 取消）与下载未能启动时丢弃意图，避免以后误开；重复点同一条正在接收的附件仍会保留意图，接收完成后照常打开。已在本机的附件（`savedUriFor` 命中）不再重复下载，直接发请求。
- **ChatUi**：`openAttachment` 改为「已在本机 → 直接打开；未下载 → 记下意图并走原来的接收流程（含 API 26–28 的存储权限）」；新增 `viewAttachment(uri, mimeType)` 统一拉起系统默认应用（`ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`，失败提示「无法打开附件」）；`LaunchedEffect(s.openRequest)` 消费一次性请求。气泡提示由「点击保存」改为「点击打开」，长按菜单的「保存附件」入口合并为「打开附件」（文件依然会落到 `Downloads/点点直连`）。
- 图片仍走应用内图库 / 全屏查看，语音仍在应用内播放，均未改动。文档同步：README 的附件条目与「附件接收通知」描述、房间信息里的操作说明。

### 验证

- `npm run android:chat:check`：BUILD SUCCESSFUL；21 个单测全部通过；Lint **0 error** / 30 warning（无新增）。APK：`output/ddzhilian-chat-debug.apk`，SHA-256 `f133ea7442d13f8ec34763c1de8c6134648e7fe95d9f360e9175ae0c4dd3437c`。
- 未运行模拟器/真机：未实测「点按未下载附件 → 应用内进度 → 自动拉起系统默认应用」的完整链路，也未验证不同 MIME 类型下系统是否存在可响应的默认应用。

## 提交与推送：git push 作为更新通道

- 工作树里的全部改动已提交为一个提交 `7cd8ec8 feat: fixed glass opacity, git update channel, system opener for files`，并 `git push origin main`（`9c38a41..7cd8ec8`）。远端 `refs/heads/main` = `7cd8ec8388a86465ec3d5ec3fe4c0138c36dce7e`，本地与远端一致。
- 提交范围：`app/` 的源码与资源、`README.md`、`QA.md`、`.gitignore`、`wechat-glass-demo/`。**未提交**：`s3.xml`（uiautomator 界面 dump，调试垃圾）与 `.pi/`（agent 规划目录），两者已加入 `.gitignore`；`output/`、`local.properties` 保持忽略。
- 分发口径确认：安装包**不进仓库历史**（`.gitignore` 保留 `*.apk` 与 `output/`），更新包继续走该 tag 的 Release 附件；App 里的 raw 回退只在安装包真被提交进 tag 时才会命中。
- 推送后复查匿名通道端点：`GET https://github.com/CGR-MIX/android-chat.git/info/refs?service=git-upload-pack` 当时仍返回 **401**，仓库当时还是私有的（见下一节：已改为公开）。

### 仓库改为公开后的通道复查

- 匿名端点：`info/refs?service=git-upload-pack` 返回 **200**（`application/x-git-upload-pack-advertisement`），`raw.githubusercontent.com/CGR-MIX/android-chat/main/README.md` 与 `…/app/src/main/java/com/ddzhilian/chat/UpdateProtocol.kt` 均返回 200。此前 401 / 404 的前置条件已解除。
- 用与 `UpdateProtocol.kt` 完全相同的算法（pkt-line 解析、`refs/tags` 过滤、语义版本比较）解析真实响应：HTTP 200、service 头正确、解析出 2 条 ref（`HEAD`、`refs/heads/main`）、**没有版本 tag**，因此 `latestRelease` 为 null —— App 的「检查更新」当前会显示「仓库里还没有版本 tag」。这也说明解析器对真实 GitHub 响应（含能力列表行）有效，此前只用合成数据覆盖过。
- 仍未验证：真实 tag + Release 附件下的下载与安装链路（仓库尚无 tag，也没有 Release 附件）。

## v1.5.0 发版：启用 git 更新通道

- 版本：`versionCode` 5 → 6，`versionName` 1.4.0 → 1.5.0。仓库已改为公开，匿名 refs 端点在发版前实测返回 200，因此这次发版同时让更新通道真正可用。
- `npm run android:chat:check`：BUILD SUCCESSFUL；21 个单测全部通过；Lint 0 error。APK：`output/ddzhilian-chat-debug.apk`，66,913,408 字节，SHA-256 `76a4661922278decb797b718401cf72d831b0369c7c0cade0fbd0a8cfec53fc0`。
- 发布动作：`git tag v1.5.0 && git push origin v1.5.0`，再把上面的 APK 作为该 tag 的 Release 附件上传（安装包不进仓库历史）。只推 tag 不上传附件时，App 能发现新版本但下载会失败。

## Markdown 富文本显示适配

- 背景：服务端保存 AI 回复时只做 HTML 转义（`server/src/index.ts` 的 `buildAiBotReplyText` / `plainTextToRichText`），Markdown 标记原样留在正文里；App 此前只用 `stripChatMarkup` 去标签，因此气泡里会直接看到 `**粗体**`、`### 标题`、围栏代码块这些源文本。
- 实现：新增 `ChatMarkdown.kt`（纯 Kotlin 解析：标题、段落、有序/无序列表与懒续行、引用、围栏代码块、分隔线，行内粗体/斜体/粗斜体/删除线/行内代码/`[文字](链接)`/裸链接/反斜杠转义；未支持的表格等语法原样保留）与 `ChatMarkdownUi.kt`（Compose 渲染：标题分级字号、列表符号与序号、引用底色、等宽代码块横向滚动、分隔线、链接下划线且可点）。
- 接入点：聊天气泡（`ChatUi.kt` 的 `MessageBubble`）、会话列表预览、会话内搜索结果预览、长按「复制」（都经 `plainChatText` 降级为纯文本，避免露出标签和 Markdown 标记）。
- 验证：`./gradlew :app:testDebugUnitTest` 通过（43 个用例，含新增 16 个 `MarkdownTest` 用例：行内样式、标点不被误吃、转义、列表续行、代码块与未闭合围栏、服务端富文本回复、纯文本降级）；`:app:lintDebug` 0 error（32 warning 全部为此前既有项，新文件 0 命中）；`:app:assembleDebug` 成功。
- 未验证：**没有在模拟器/真机上做视觉确认**。本机 `adb` daemon 反复掉线，headless 模拟器启动后 adb 连接数秒内丢失，未能安装 APK 截图；因此气泡内代码块横向滚动、行内链接点击、深色模式与「减少透明效果」下的实际观感，以及长句换行后的气泡宽度，均未在设备上核对。
- 未改动 `ddzhilian` 主仓库、线上服务与部署；本轮只改本仓库的 Kotlin 源码、测试与文档。

## v1.6.2 发版：Markdown 富文本上线

- 版本：`versionCode` 8 → 9，`versionName` 1.6.1 → 1.6.2。`npm run android:chat:check` BUILD SUCCESSFUL（43 个单测通过、Lint 0 error、独立 APK 生成）。
- APK：`output/ddzhilian-chat-debug.apk`，67,044,480 字节，SHA-256 `6093c94cfabbae93e5a7a1e3762ae00c3c8cdd11db8eece5574f8d2b165d01ec`。`aapt2 dump badging` 核对包内为 `versionCode='9' versionName='1.6.2'`。
- 发布：提交 `11b43fe chore(release): v1.6.2 (versionCode 9)` 与 annotated tag `v1.6.2` 均已推送；Release 附件上传为 `ddzhilian-chat-debug.apk`，页面 <https://github.com/CGR-MIX/android-chat/releases/tag/v1.6.2>（安装包仍不进仓库历史）。
- 通道复查：匿名 `info/refs?service=git-upload-pack` 返回 200 且含 `refs/tags/v1.6.2`（更新通道可发现）；附件地址返回 200，落到 `release-assets.githubusercontent.com`，`Content-Length` 67,044,480 与本地一致，前 4 字节为 `PK` + `03 04`，整包 SHA-256 与本地完全相同。
- 仍未做设备端视觉验证，限制同上节。建议在 1.6.1 上点一次「检查更新」验证下载安装链路，并顺手确认气泡内代码块与链接的实际表现。

## 长按助手头像 @ 助手

- 需求确认：服务端后台已把助手名/唤醒词配成「鲸鱼娘」，因此 App 端只需跟随 `/api/ai/branding` 返回值，不另起一套名字；长按头像插入的文本也取服务端返回的助手名，避免插入了服务端不认的提及。
- 实现（协议层）：`appendAiMention(draft, name)`（`ChatProtocol.kt`）把 `@名称 ` 补到草稿末尾 —— 空草稿直接写提及、普通草稿先补一个空格、已经以同一个名字开头时只补分隔空格不重复叠 `@`、名称为空时原样返回。
- 实现（状态层）：`ChatViewModel` 抽出 `applyBranding`，新增 `aiMentionName()`（没拉到过配置就先等 4 秒取一次，超时或失败回退当前值）与 `mentionAi()`（改当前房间草稿并返回名称）。
- 实现（界面）：助手消息旁的头像触摸区放大到 32dp（头像本身仍是 18dp，视觉位置基本不变），长按补 `@名称 ` 并聚焦输入框、弹出键盘；语音模式下先停止录音并切回打字，避免插入内容看不见；输入框加了 `focusRequester`。
- 验证：`npm run android:chat:check` BUILD SUCCESSFUL；44 个单测全部通过（`AiMentionTest` 新增 `appendAiMention` 用例：空草稿、已有文字、已有尾随空格、已提及同一助手、提到别的设备、名称首尾空格、空名称）；Lint 0 error。
- 未验证：长按手势与键盘弹出没有在设备上确认（本机 adb 限制同前）；插入的名称依赖 `/api/ai/branding` 可达，离线冷启动时会回退到内置默认名（此时插入的不是「鲸鱼娘」）。
- 版本：`versionCode` 9 → 10，`versionName` 1.6.2 → 1.6.3；`npm run android:chat:check` BUILD SUCCESSFUL，产物 67,060,864 字节，SHA-256 `66be002d5e0b3b273342d9d436b462ca9a757267d556e019f62fe5f789603832`（`aapt2` 核对包内为 `versionCode='10' versionName='1.6.3'`），发版细节见下节。

## 助手头像不显示：根因与修复

- 现象：房间里的助手消息只有「AI」文字圆标，后台配置的头像从没出现（网页端正常）。
- 线上实测（2026-10-19）：`GET /api/ai/branding` 返回 `aiName:"鲸鱼娘"`、`wakeWords:["大肥鱼","大鲸鱼"]`、`avatarUrl:"/api/ai/avatar?v=b3537b39"` —— 头像是**相对路径**（网页端靠同域解析）；`GET /api/ai/avatar?v=b3537b39` 返回 200 `image/gif`（3,325,320 字节），说明服务端数据正常。
- 根因：`AiAvatarStore.download()` 直接把 `/api/ai/avatar?v=…` 交给 `Request.Builder().url(...)`，OkHttp 只接受绝对地址，会当场抛 `IllegalArgumentException`，被 `runCatching` 吞掉后返回 null，界面就一直回退文字头像。
- 修复：`AiAvatarStore` 增加服务端根地址（传 lambda，跟随当前 `address`，不存快照），下载前用新增的 `resolveAvatarUrl()` 补全相对路径；已带 http/https 的原样使用；磁盘缓存文件名仍按原始 `avatarUrl` 计算，所以后台换头像（`?v=` 变化）会自然重新下载。
- 验证：新增 `AvatarUrlTest`（4 个用例：相对路径补全、绝对地址原样、首尾空格、无根地址/空路径返回 null）；48 个单测全部通过，Lint 0 error，`assembleDebug` 成功。
- 未验证：设备端是否真正显示头像（本机 adb 限制同前），以及 3.3 MB GIF 在慢网络下的下载耗时。
- 状态：本地 `output/ddzhilian-chat-debug.apk` 已含该修复（连同下面的光标修复：67,703,528 字节，SHA-256 `1173814d4d58621b99da9f6210b7376d8b1d3b0bea157d0c2f6048668276d645`，包内版本仍是 `10 / 1.6.3`），与已发布的 v1.6.3 附件（`66be002d…`）不是同一个包，尚未发版。

## 长按插入 @ 后光标停在最前面

- 现象：长按助手头像插入 `@鲸鱼娘 ` 后，光标停在 `@` 之前而不是末尾，得手动点到末尾才能接着打字。
- 根因：输入框用的是 `BasicTextField(String, onValueChange)` 重载，它内部保留上一份 `selection`。空草稿时 selection 是 0，外部把 value 改成 `@鲸鱼娘 ` 后文本换了、selection 仍被钳在 0，光标就落在最前面。
- 修复：草稿改用受控的 `TextFieldValue`（`draftField`）。`onValueChange` 同时更新 field 与 `vm.draft`；`LaunchedEffect(s.activeRoomId, s.draft)` 只在「field 文本与草稿不一致」时重建 field 并把 selection 放到末尾，所以自己打字（含中文 IME 组合输入）不会被打断，只有长按插入、发送后清空这类外部改动才移动光标；切换房间时也直接落在草稿末尾。
- 验证：`npm run android:chat:check` BUILD SUCCESSFUL；48 个单测全部通过，Lint 0 error。
- 未验证：设备端光标位置与中文输入法组合输入未实测（本机 adb 限制同前）。
