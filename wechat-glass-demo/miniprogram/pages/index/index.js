const MIN_OPACITY = 0.03;
const MAX_OPACITY = 0.25;
const MAX_BLUR = 24;

function calculateGlassParams(percent) {
  const p = Math.max(0, Math.min(100, percent)) / 100; // 0 ~ 1
  // 联动算法：采用平方曲线 frosted^2，让低数值下清透细腻，高数值下柔和雾化
  // 当 p = 0 时，blur = 0，即完全去掉磨砂
  const frosted = p * p;
  const blur = Math.round(MAX_BLUR * frosted * 10) / 10;
  const bgAlpha = (MIN_OPACITY + (MAX_OPACITY - MIN_OPACITY) * p).toFixed(3);
  const saturate = Math.round(100 + 80 * p);
  return {
    blur,
    bgAlpha,
    saturate
  };
}

Page({
  data: {
    statusText: '轻触或按压按钮感受物理形变',
    sliderValue: 60, // 0 ~ 100
    blur: 8.6,
    bgAlpha: 0.162,
    saturate: 148,
    dispersion: true,
    noFrostMode: false, // 是否单选强制去掉磨砂
    showGlassMenu: false, // 模拟长按玻璃菜单
  },

  onLoad() {
    this.updateGlassStyle(this.data.sliderValue);
  },

  updateGlassStyle(percent, forceNoFrost = false) {
    if (forceNoFrost) {
      this.setData({
        sliderValue: 0,
        blur: 0,
        bgAlpha: MIN_OPACITY.toFixed(3),
        saturate: 100,
        noFrostMode: true,
        statusText: '磨砂已完全去除（纯透水晶玻璃模式）'
      });
      return;
    }

    const { blur, bgAlpha, saturate } = calculateGlassParams(percent);
    this.setData({
      sliderValue: percent,
      blur,
      bgAlpha,
      saturate,
      noFrostMode: blur === 0,
      statusText: blur === 0 
        ? '已滑动到最低端：磨砂彻底去除，呈现极透纯净玻璃' 
        : `联动调节中: 磨砂=${blur}px, 不透明度=${Math.round(bgAlpha * 100)}%`
    });
  },

  // 滑块拖动中（实时联动）
  onSliderChanging(e) {
    const val = e.detail.value;
    const { blur, bgAlpha, saturate } = calculateGlassParams(val);
    this.setData({
      sliderValue: val,
      blur,
      bgAlpha,
      saturate,
      noFrostMode: val === 0,
      statusText: val === 0 
        ? '磨砂已归零（纯透玻璃状态）' 
        : `实时联动: 模糊 ${blur}px / 底色 Alpha ${bgAlpha}`
    });
  },

  // 滑块松手
  onSliderChange(e) {
    this.updateGlassStyle(e.detail.value);
    if (wx.vibrateShort) wx.vibrateShort({ type: 'light' });
  },

  // 一键切换去掉磨砂
  toggleNoFrost() {
    if (this.data.noFrostMode) {
      // 恢复磨砂到 60%
      this.updateGlassStyle(60, false);
    } else {
      // 彻底去掉磨砂
      this.updateGlassStyle(0, true);
    }
    if (wx.vibrateShort) wx.vibrateShort({ type: 'medium' });
  },

  // 切换色散
  toggleDispersion() {
    this.setData({
      dispersion: !this.data.dispersion
    });
    if (wx.vibrateShort) wx.vibrateShort({ type: 'light' });
  },

  // 点击液态按钮
  onButtonClick() {
    this.setData({
      statusText: '玻璃按钮触发！' + new Date().toLocaleTimeString()
    });
    if (wx.vibrateShort) wx.vibrateShort({ type: 'heavy' });
  },

  // 长按卡片弹出玻璃菜单
  onLongPressCard() {
    this.setData({ showGlassMenu: true });
    if (wx.vibrateShort) wx.vibrateShort({ type: 'heavy' });
  },

  closeGlassMenu() {
    this.setData({ showGlassMenu: false });
  }
});
