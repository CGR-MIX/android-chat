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

本次构建校验文件：`b38924c18e26d1beec9a24e3f4bc88ad9089d2b70e7f3acf9fd10f8ca661cdbe  ddzhilian-chat-debug.apk`。
