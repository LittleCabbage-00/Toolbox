# Toolbox v3.1.0

自 `v2.4.1` 以来的 19 个应用与文档提交：工程升级至 API 37，功能代码全面迁移 Kotlin；新增加解密、媒体转码、终端、网络/API 调试等工具；浏览器升级为多标签、下载、资源嗅探、收藏夹与广告拦截一体化体系，并完成 Bing 壁纸、深色模式、横竖屏状态和 Material 界面的修复与打磨。

## 主要变更

### 构建配置、资源与主题基础（[f313d05](https://github.com/LittleCabbage-00/Toolbox/commit/f313d05)）

- 升级 `compileSdk` / `targetSdk` 至 37，发布 ABI 为 `arm64-v8a`。
- 引入 Material Components、Edge-to-Edge、深色模式与响应式尺寸资源；发布包启用 R8、资源裁剪和原生库压缩。

### Java 全面迁移 Kotlin 与嗅探修复（[11187b2](https://github.com/LittleCabbage-00/Toolbox/commit/11187b2)）

- Activity、工具类和 Browser 组件完成 Kotlin 化，统一空安全和后台执行模型。
- 修复带签名参数的 m3u8 归一化：使用 `substringBefore('?')` 处理查询串，避免媒体 URL 被错误过滤。
- 修复 `evaluateJavascript` 返回值解析，兼容对象字面量与二次编码字符串；嗅探解析、聚合、排序移至后台 HandlerThread。

## 新增功能

### 文本与文件加密引擎（[93d68cf](https://github.com/LittleCabbage-00/Toolbox/commit/93d68cf)）

- 文本支持 AES-256-GCM、SM4-GCM、RSA-2048 OAEP、SM2、SM3、SHA-256；Base64 仅作为编码工具。
- 文件支持 AES / SM4 流式加解密，以及 RSA / SM2 封装会话密钥的混合加密，兼容旧版 AES 文件。

### 数据、媒体、网络与终端工具（[0429883](https://github.com/LittleCabbage-00/Toolbox/commit/0429883)、[bd1b73f](https://github.com/LittleCabbage-00/Toolbox/commit/bd1b73f)、[1abee2f](https://github.com/LittleCabbage-00/Toolbox/commit/1abee2f)、[845254c](https://github.com/LittleCabbage-00/Toolbox/commit/845254c)、[48f5fee](https://github.com/LittleCabbage-00/Toolbox/commit/48f5fee)）

- Base64、URL、Hex 编解码，JSON 格式化、时间戳、UUID、密码生成与单位换算。
- FFmpeg 支持本地和网页嗅探资源转换为 MP4、MKV、MP3、M4A、FLAC，重点支持 m3u8 合并与重新封装。
- HTTP API 调试可构造请求、查看状态码/响应，并按 Raw 或表单内容生成合适请求体；网络诊断支持 URL、DNS、响应头检查。
- 终端支持应用、Root、Shizuku 三种模式，模板可增删改，并内置可编辑的 Scene 激活示例。

### 系统 DocumentsUI 与扫码器（[c5f798a](https://github.com/LittleCabbage-00/Toolbox/commit/c5f798a)）

- 解析 ROM 的 `ACTION_OPEN_DOCUMENT_TREE` 处理组件，优先定向 DocumentsUI，并以 `Android/data` 为初始位置。
- 扫码器支持连续扫码、图库识别、手电筒和结果复制/打开网页。

## 浏览器

### 下载、媒体代理与管理界面（[45de1ef](https://github.com/LittleCabbage-00/Toolbox/commit/45de1ef)、[31b15ba](https://github.com/LittleCabbage-00/Toolbox/commit/31b15ba)）

- OkHttp 前台下载支持通知进度、暂停、继续、取消和记录；图片、文件、`data:`、`blob:` 均可进入应用下载。
- 本地媒体代理支持 HTTPS、Cookie、Referer、Range 和 HLS 子清单/分片/密钥 URL 重写；八路有界预取与伪直播清单动态合并避免只下载前几十秒。
- 多标签保存 WebView 完整状态，旋转屏幕不重新加载；历史、收藏、下载管理均有独立页面。

### 广告拦截、收藏夹排序与明确 UA（[b6fc5c1](https://github.com/LittleCabbage-00/Toolbox/commit/b6fc5c1)）

- 内置 EasyList 与 EasyList China 规则，菜单可快速开关、设置页可更新，且不拦截主文档请求。
- 收藏夹支持文件夹、编辑、顶部书签栏与横向滚动；根目录或文件夹内长按书签可拖动排序，顺序自动保存且不改变归属。
- User-Agent 提供明确的移动端、桌面端与自定义模式；桌面端使用完整 Windows Chrome 标识，切换会刷新已打开标签。

## 界面、壁纸与兼容性

### Material、深色模式与响应式（[2e086ff](https://github.com/LittleCabbage-00/Toolbox/commit/2e086ff)）

- 页面统一 Material Design，支持深色模式、透明沉浸式状态栏、Edge-to-Edge 和统一卡片/点击反馈。
- 手机使用四列图标菜单，横屏与平板使用六列布局。

### Bing 壁纸修复（[5772dbf](https://github.com/LittleCabbage-00/Toolbox/commit/5772dbf)、[5446069](https://github.com/LittleCabbage-00/Toolbox/commit/5446069)、[ec4f0d1](https://github.com/LittleCabbage-00/Toolbox/commit/ec4f0d1)）

- 修复首次启动网络未就绪导致壁纸无法加载，增加缓存首帧、有限重试和后台清晰图升级。
- 修复竖屏比例与旧缓存复用；旧比例缓存会重建，避免主页图片显示不全。

### 3.1.0 收尾（[d6557cc](https://github.com/LittleCabbage-00/Toolbox/commit/d6557cc)）

- Bing 壁纸不再自动写入相册，保存当天壁纸改为用户在设置页手动触发。
- 应用图标改为自适应 Material 图标，兼容圆形和圆角方形启动器遮罩；README 同步 3.1.0 使用说明。

## 文档

### README 同步（[1733429](https://github.com/LittleCabbage-00/Toolbox/commit/1733429)、[a760cf1](https://github.com/LittleCabbage-00/Toolbox/commit/a760cf1)）

- 补充工具清单、加密边界、资源嗅探和下载策略、FFmpeg 许可证、DocumentsUI 平台限制与构建说明。

## 提交记录

```text
f313d05 feat: 升级构建配置、资源与主题基础
93d68cf feat: 文本与文件加密引擎（AES/SM4/RSA/SM2）
0429883 feat: 数据工具与单位换算
45de1ef feat: 浏览器下载服务、历史/收藏存储与本地媒体代理
31b15ba feat: 浏览器多标签、历史、收藏与下载管理界面
bd1b73f feat: FFmpeg 媒体转码工具
1abee2f feat: HTTP API 调试工具
845254c feat: 网络诊断工具
48f5fee feat: 终端增强与 Shizuku/Root 命令执行
c5f798a feat: 系统文件管理器与扫码器改进
2e086ff feat: 主题模式、响应式与导航重构
1733429 docs: 更新 README 说明
11187b2 refactor: Java 全面迁移 Kotlin
a760cf1 docs: 同步 README 至当前功能
5772dbf fix: 首次启动 Bing 壁纸加载失败
5446069 fix: Bing 壁纸首次加载与竖屏比例显示
ec4f0d1 fix: ensurePortrait 短路导致旧比例缓存未重建
b6fc5c1 release: prepare version 3.1.0
d6557cc release: finalize Toolbox 3.1.0
```

## 已知说明

- DRM、登录态、Cookie、防盗链或 `blob:` 地址保护的网页媒体可能无法下载或转换。
- Android 11 及以上对 `Android/data` 与 `Android/obb` 的访问由 DocumentsUI 和 ROM 策略决定，应用不能绕过平台限制。
- SM3、SHA-256 为不可逆摘要；Base64 是编码而不是加密。
