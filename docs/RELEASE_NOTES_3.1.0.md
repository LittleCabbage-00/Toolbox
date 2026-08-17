# Toolbox v3.1.0

Toolbox 3.1.0 聚焦浏览器体验、收藏夹管理、广告拦截和界面一致性。本版本面向 Android 8.0（API 26）及以上设备，`targetSdk` 为 37。

## 新增与改进

- 收藏夹支持文件夹、标题与地址编辑、顶部书签栏和横向滚动。
- 在根目录或任一文件夹中，长按书签即可上下拖动排序；排序会自动保存，且不会改变书签所在文件夹。
- 浏览器 User-Agent 提供明确的移动端、桌面端和自定义三种模式；桌面端采用完整 Windows Chrome 标识，避免部分网站仍将页面识别为移动端。
- 浏览器内置 EasyList 与 EasyList China 广告规则，支持菜单快速开关和设置页手动更新。
- 优化浏览器菜单、收藏夹页面、深色模式、横竖屏状态保留与触控反馈。
- 应用图标升级为自适应 Material 图标，适配圆形和圆角方形启动器遮罩。

## 行为调整

- Bing 每日壁纸不再自动保存到相册；需要保存时，请在“软件设置 → 壁纸保存”中手动执行。
- 下载、媒体嗅探、文件处理和相机等权限仍遵循按需申请原则。

## 已知说明

- 受 DRM、登录态、Cookie、防盗链或 `blob:` 地址保护的网页媒体，可能无法下载或转换。
- Android 11 及以上对 `Android/data` 与 `Android/obb` 的访问由系统 DocumentsUI 和 ROM 策略决定，应用不能绕过平台限制。
- SM3、SHA-256 为不可逆摘要；Base64 是编码而非加密。

## 构建信息

- 最低系统：Android 8.0（API 26）
- 目标系统：Android 15 / API 37
- 发布 ABI：`arm64-v8a`
- 核心组件：Material Components、ZXing 3.5.4、OkHttp 4.12.0、Bouncy Castle 1.82、FFmpegKit maintained 8.1.7

感谢使用 Toolbox。反馈问题时请附上设备型号、Android/ROM 版本、复现步骤和相关日志，以便定位。
