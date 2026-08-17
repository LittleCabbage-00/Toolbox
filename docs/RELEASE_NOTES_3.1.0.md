# Toolbox v3.1.0

自 3.0.0 以来的 5 个应用与发版提交：修复 Bing 每日壁纸首次加载和竖屏比例问题，增加网页广告拦截、收藏夹分组排序、明确的 User-Agent 切换，并完成 3.1.0 版本与 Material 自适应图标收尾。

## 主要变更

### Bing 壁纸首次加载修复（[5772dbf](https://github.com/LittleCabbage-00/Toolbox/commit/5772dbf)）

- 修复首次启动时网络尚未就绪导致主页每日壁纸加载失败的问题。
- 增加缓存壁纸首帧展示和有限次数重试，避免首次进入长期停留在空白占位。

### Bing 壁纸竖屏显示与缓存重建（[5446069](https://github.com/LittleCabbage-00/Toolbox/commit/5446069)、[ec4f0d1](https://github.com/LittleCabbage-00/Toolbox/commit/ec4f0d1)）

- 修复竖屏设备使用横向缓存或旧比例缓存时主页图片显示不全的问题。
- `ensurePortrait` 不再因旧缓存提前短路；检测到比例不匹配时会重新生成竖屏版本。
- 缓存存在时优先快速展示缩略首帧，再在后台升级为适配屏幕的清晰图片，减少闪白和跳变。

## 浏览器

### 广告拦截、收藏夹与 User-Agent 改进（[b6fc5c1](https://github.com/LittleCabbage-00/Toolbox/commit/b6fc5c1)）

- 内置 EasyList 与 EasyList China 规则快照；浏览器菜单可快速开关，设置页支持手动更新规则。
- 广告拦截只处理子资源，不拦截主文档，避免首次导航时 CSS、脚本或页面主体异常。
- 收藏夹支持文件夹、标题和地址编辑、顶部书签栏、横向滚动；根目录或文件夹内可长按书签上下拖动排序，松手后自动保存且不改变所属文件夹。
- User-Agent 提供明确的移动端、桌面端与自定义模式。桌面端使用完整 Windows Chrome 标识，移动端使用 Android Chrome 标识；切换后刷新现有标签。
- 优化浏览器底栏、菜单图标、历史/收藏页面层级、深色模式与横竖屏状态保留。
- API 调试工具根据 Raw 或表单输入自动选择请求体，去除重复发送入口。

## 界面与行为调整

### 3.1.0 发布收尾（[d6557cc](https://github.com/LittleCabbage-00/Toolbox/commit/d6557cc)）

- 版本更新为 `3.1.0 (3010000)`，`compileSdk` / `targetSdk` 保持 37。
- 应用图标改为自适应 Material 图标，适配圆形和圆角方形启动器遮罩。
- Bing 每日壁纸不再自动写入相册；用户可在“软件设置 → 壁纸保存”中手动保存当天壁纸。
- README 同步当前浏览器、下载、收藏夹、广告拦截和权限说明。

## 提交记录

```text
5772dbf fix: 首次启动 Bing 壁纸加载失败
5446069 fix: Bing 壁纸首次加载与竖屏比例显示
ec4f0d1 fix: ensurePortrait 短路导致旧比例缓存未重建
b6fc5c1 release: prepare version 3.1.0
d6557cc release: finalize Toolbox 3.1.0
```

## 已知说明

- DRM、登录态、Cookie、防盗链或 `blob:` 地址保护的网页媒体，可能无法下载或转换。
- Android 11 及以上对 `Android/data` 与 `Android/obb` 的访问由 DocumentsUI 和 ROM 策略决定，应用不能绕过平台限制。
- SM3、SHA-256 为不可逆摘要；Base64 是编码而不是加密。
