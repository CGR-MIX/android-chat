# 微信小程序液态玻璃质感研究与实现 Demo (WeChat MiniProgram Liquid Glass)

本工程是对齐原生 Android Jetpack Compose (`AndroidLiquidGlass` / AGSL 着色器) 与 LGGC 规范在**微信小程序**中的技术研究与实现 Demo。

---

## 本次更新亮点：去掉磨砂与双向联动调节

### 1. 为什么“去掉磨砂”仍然有强烈的玻璃质感？
在传统设计中，很多开发者认为玻璃质感仅仅等于 `backdrop-filter: blur(...)`（毛玻璃）。但当把 `blur` 降为 0 时，往往会发现元素只剩下平淡的透明背景，毫无材质感。  
**原生 Liquid Glass 的精髓在于物理透镜折射厚度（Lens Thickness）与菲涅尔切线高光（Fresnel Glare）：**
- **去掉磨砂（Blur = 0）时**：背景内容与文字 100% 锐利穿透，没有丝毫发灰乳化的阻碍感；
- **保留双重各向异性内阴影**（上方/左侧锐利菲涅尔折射微光 + 底部次高光）以及斜切向的漫反射层；
- 此时呈现出的是晶莹剔透的**纯净水晶玻璃 / 净透亚克力**质感，极为通透且边缘立体分明。

### 2. 磨砂 (Blur) 与透明度 (Opacity) 双向联动算法
在 Android 原版 Compose 代码中（参见 `ChatUi.kt` 与 `.pi/plan` 记录）：
> *"Frosting has to fall off with the veil. A fixed blur radius smears whatever is behind the surface into flat grey, so the material keeps reading as milky even at the lowest setting..."*

如果透明度降低而模糊度固定，背景就会被抹成一片扁平的死灰色，任何背后的文字都不可读。因此：
$$\text{frosted} = p^2 \quad (p \in [0, 1])$$
$$\text{blur} = \text{MAX\_BLUR} \times \text{frosted}$$
$$\text{bgAlpha} = \text{MIN\_ALPHA} + (\text{MAX\_ALPHA} - \text{MIN\_ALPHA}) \times p$$

- 当用户通过滑块拉到最低端（0%）或点击【去掉磨砂】时，`blur` 瞬间归零，底色透明度降到最低，背景 100% 纯透穿透；
- 当滑块往上调节时，磨砂雾化程度以平方曲线平滑上升，自然过渡至温润浓郁的磨砂乳化玻璃状态。

---

## 目录结构

```text
wechat-glass-demo/
├── project.config.json           # 微信开发者工具配置文件
├── preview.html                  # 交互式即时预览页面 (包含双向联动滑块与手势拉伸)
├── README.md                     # 研究说明与技术解析
└── miniprogram/                  # 微信小程序工程源码
    ├── app.json
    ├── app.js
    ├── app.wxss
    └── pages/
        └── index/
            ├── index.json
            ├── index.wxml        # 包含联动滑块、WXS零延迟手势直驱、长按菜单
            ├── index.wxss        # 动态 CSS 变量 (--glass-blur/--glass-bg) 与各向异性内高光
            └── index.js          # 物理联动参数计算、状态更新与触觉反馈
```

---

## 运行与体验方式

### 方式 1：微信开发者工具导入（真机体验最佳）
1. 打开**微信开发者工具**。
2. 选择 **“导入项目”**。
3. 项目目录选择：`C:\Users\12467\Documents\707\android-chat\wechat-glass-demo`。
4. AppID 选择“测试号”或留空，点击确定。
5. **体验亮点**：
   - 拖动顶部的**双向联动控制条**：观察卡片中 `Blur`（磨砂半径）与 `Alpha`（底色透明度）的实时动态联动；
   - 点击右上角或面板内的**【去掉磨砂】**按钮：瞬间切换为纯透水晶玻璃模式，观察底层对照水印文字的锐利穿透；
   - **长按**玻璃卡片：弹出浮层长按玻璃菜单（仿 Android `GlassMenuOverlay`）；
   - 按住底部的**液态胶囊按钮**并拖动：感受 WXS 驱动的 60/120fps 弹性形变与阻尼回弹。

### 方式 2：电脑浏览器即时体验
- 直接双击打开 `wechat-glass-demo/preview.html` 即可在电脑端模拟手机屏幕，用鼠标拖动滑块体验磨砂/透明度无级渐变，按住拖拽胶囊按钮感受物理拉伸。
