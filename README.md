# Toolbox Android 工具箱

采用 Material Design、支持深色模式与 Edge-to-Edge 的本地 Android 工具箱。当前版本 3.1.0，`compileSdk` / `targetSdk` 为 37，最低支持 Android 8.0（API 26）。

## 3.1.0 概览

- 浏览器收藏夹支持文件夹、编辑、顶部书签栏与当前分组内的长按拖动排序。
- User-Agent 切换改为明确的移动端、桌面端和自定义三种模式；切换后会刷新现有标签页。
- 内置 EasyList / EasyList China 广告规则，可在浏览器中快速切换，并可在设置中手动更新。
- 主页 Bing 壁纸仅用于展示与缓存；不再自动写入相册，保存壁纸须由用户在设置中手动触发。
- 应用使用自适应 Material 图标，兼顾主流国产 ROM 启动器的圆形与圆角方形遮罩。

## 主要功能

- 文本安全工具：AES-256-GCM、SM4-GCM、RSA-2048 OAEP、SM2、SM3、SHA-256。
- 文件加解密：AES-256-GCM、SM4-GCM、RSA-OAEP 混合加密、SM2 混合加密；支持大文件流式处理，并兼容旧版 AES 文件。
- 扫码：相机连续扫码、图库识别、手电筒、复制结果和打开网址。
- 内置浏览器：可关闭多标签页、移动端 / 桌面端 / 自定义 UA、页内查找、分享、网页信息、长按资源下载、独立历史记录。
- 网页广告拦截：默认内置 EasyList 与 EasyList China 网络规则，可在浏览器菜单一键启停，并可在设置中手动更新规则。
- 下载管理：OkHttp 前台服务、通知进度、暂停、继续、取消；保存至公共 `Download/Toolbox`，可单独删除记录或同时删除文件。
- 简易终端：应用、Root、Shizuku 三种执行模式；命令模板可增删改，内置 Scene 激活示例。
- 实用工具：Base64 / URL / Hex 编解码、JSON 格式化、压缩、时间戳、UUID、密码生成、单位换算等。
- 系统 DocumentsUI：解析不同 ROM 的系统目录选择组件并优先打开 DocumentsUI。
- FFmpeg 多媒体：本地文件或网页嗅探资源可转换为 MP4、MKV、MP3、M4A、FLAC，重点支持 m3u8 合并与重新封装。
- 网页收藏夹：支持文件夹、收藏、搜索、编辑、打开、逐条删除和清空；长按书签可在当前根目录或文件夹内上下拖动排序。
- 网络诊断：URL 结构解析、DNS 查询、HTTP 响应头检查；另有 HTTP API 调试工具，可构造请求、查看响应与状态码。
- 必应每日壁纸：主页展示、图集浏览、日期切换与手动保存。

> Base64 是编码，不是加密。SM3 和 SHA-256 是不可逆摘要，因此它们不会出现在文件“加解密”算法列表中。

## 使用说明

### 文本与文件加解密

AES 和 SM4 使用密码；RSA 和 SM2 可在页面内生成密钥对，加密粘贴公钥，解密粘贴私钥。文件非对称加密采用“RSA/SM2 封装随机会话密钥 + AES/SM4-GCM 加密正文”，避免直接非对称加密的大文件长度限制。密文包含随机盐、IV、算法标识与认证标签，密码或密钥错误、文件被篡改时会验证失败。

文件读写使用 Storage Access Framework 返回的 `content://` URI，不申请宽泛存储权限。横竖屏切换会保留算法、输入内容以及已选文件。

### 浏览器与下载

浏览器底栏支持主页、前进、后退、标签页和图标化功能菜单，并提供统一点击动画。标签页保存完整 WebView 状态，旋转屏幕时不会重新加载。User-Agent 仅提供“移动端”“桌面端”“自定义”三种明确模式：桌面端使用完整 Windows Chrome 标识，移动端使用完整 Android Chrome 标识，切换后会重新加载已打开的标签。历史记录可在独立页面展示、搜索、逐条删除或清空；软件设置也提供历史记录入口，并可控制是否保存历史、是否在搜索页显示历史。

收藏夹页面按“书签栏”和文件夹分组浏览，可新建文件夹、编辑书签标题和地址，并在当前分组中长按书签后上下拖动排序。松手即持久化，排序只影响当前分组，不会改变书签的文件夹归属。浏览器顶部书签栏可横向滚动；文件夹会弹出其中的书签以便直接打开。

网页广告拦截默认开启。应用随安装包提供 EasyList 与 EasyList China 的规则快照，对常见广告请求进行域名级拦截；浏览器功能菜单可快速开关，软件设置中的“屏蔽网页广告”可查看规则状态并手动更新。更新失败时会继续使用上一次成功的规则（或应用内置规则），不会影响正常浏览。当前实现专注于网络请求拦截，不修改网页页面元素。规则来源为 [EasyList](https://easylist.to/) 与 [EasyList China](https://easylist.to/)。

网页下载和长按图片、文件或链接均使用应用内下载逻辑。有效的 HTTP(S) 图片直链优先原样下载，并复用 WebView 成功加载该资源时的 Accept、语言、Origin、Fetch Metadata 与响应式图片请求头；需要防盗链信息时会继承当前页面的 UA、Cookie 与 Referer，若服务器返回 401/403 或 HTML 占位页则自动尝试移除 Referer、再移除页面 Cookie，兼容“必须带来源”和“禁止外部来源”两类图床。懒加载图片支持协议相对、页面相对资源地址和 CSS `background-image`；`data:` 图片会在应用内直接解码，`blob:` 图片会在原网页上下文中读取并以 256 KiB 分块传回，随后进入同一个下载记录并保存到 `Download/Toolbox`。下载管理页显示活动任务与历史记录，支持暂停、继续、取消和逐条删除。删除时勾选“同时删除文件”会删除记录与实际文件，不勾选则只删除记录。

浏览器菜单采用从底部升起的图标宫格，手机显示四列，横屏和平板自动使用六列并完整展开。资源嗅探会合并 WebView 请求、HTML 媒体标签与 Performance Resource Timing 结果；点击后还会对最近捕获的媒体地址发起带凭据、`Range: bytes=0-65535` 的小范围探测请求，以补充按播放进度动态返回的清单，不创建 MediaCodec、也不改变页面播放器的位置和播放状态。结果按播放器会话归一化 CDN token、过期时间、清晰度目录与分片序号；同一视频只保留最新 m3u8，长时间播放也不会堆积数千个分片。嗅探完成后会先展示去重后的资源，由用户确认要下载的视频，再交给应用下载管理服务调用 FFmpeg 合并并转换；默认视频格式 MP4/MKV、音频格式 MP3/M4A/FLAC 可在软件设置中选择。下载、转换、通知、暂停、重新开始、取消和记录删除均沿用同一个任务体系。受 DRM、`blob:` URL、登录 Cookie 和站点防盗链保护的资源不保证能够转换。嗅探的收集、解析与排序在后台线程执行，不阻塞界面。

### FFmpeg 音视频转换

本地媒体通过 SAF 选择，网页 m3u8 和普通媒体 URL 可由浏览器资源嗅探直接传入。远端媒体由仅绑定本机环回地址的临时代理交给 OkHttp 拉取，支持 HTTPS、Cookie、Referer、Range，并会重写 HLS 子清单、分片和密钥地址；HLS 下载采用八路有界预取，在保持原始分片顺序和 AES 解密顺序不变的前提下并行准备后续分片，单片预取上限为 16 MiB。预取连接使用 15 秒短超时，分片过大、超时或预取失败时自动回退到不受短超时限制的普通流式请求，避免一个异常并发连接长时间卡住整个任务。对于持续增长但没有 `#EXT-X-ENDLIST` 的伪直播点播清单，代理会继续刷新并合并新增分片，只有服务端明确结束或尾分片连续稳定后才冻结清单，同时强制从索引 0 开始，避免只保存首次几十秒快照或遗漏开头。FFmpeg 负责本地读取和转换。MP4/MKV 默认优先无损重新封装；MP3、M4A、FLAC 会重新编码音轨。转换在应用缓存中完成，再写入用户选择的目标 URI。

项目使用 `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`（FFmpeg 8.1 LTS 社区维护版）。分发应用时需要保留 FFmpeg、FFmpegKit 及其 LGPL 组件的许可证和署名；若以后改用 GPL 构建或加入 GPL 编码器，还需要遵守相应 GPL 条款。

### 系统 DocumentsUI 与 Android/data

“打开系统文件管理器”会查询当前 ROM 对 `ACTION_OPEN_DOCUMENT_TREE` 的实际解析结果，优先定向系统 DocumentsUI，并请求以 `Android/data` 为初始位置；不会打开厂商普通文件管理器主页。

Android 11 及以上由系统禁止第三方应用通过 `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE` 授权 `Android/data` 和 `Android/obb`。应用无法合法绕过这一平台限制；某些 ROM 的 DocumentsUI 若仍提供访问能力，则以其实际行为为准。

### 权限

相机权限仅在实时扫码前申请；Android 13+ 通知权限仅在下载通知前申请；Android 8–9 手动保存壁纸到公共目录时才申请旧式存储权限。文件与图片选择使用 SAF，不需要传统存储权限。

## 构建

- Android Gradle Plugin 9.1.1 / Gradle 9.3.1 / JDK 17
- ZXing Core 3.5.4 / Material Components 1.14.0
- Bouncy Castle 1.82 / Shizuku API 13.1.5 / OkHttp 4.12.0 / FFmpegKit maintained 8.1.7
- 正式分发包面向 `arm64-v8a`。FFmpeg maintained 8.1.7 未提供可用的
  `armeabi-v7a` 原生库，因此不生成媒体功能残缺的 32 位 APK。
- Release 默认启用 R8、资源裁剪和原生库压缩；当前未签名 APK 实测约
  16.2 MiB。Debug 包包含调试符号且不压缩代码，体积明显更大属于正常现象。

```shell
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleRelease
```

## 代码结构

- `Activities/`：页面与交互逻辑。
- `Browser/`：浏览器历史、下载模型与持久化。
- `Utils/CryptoEngine.kt`：文本算法统一入口。
- `Utils/FileCrypto.kt`：版本化、流式文件加解密与旧格式兼容。
- `Utils/ShellCommandExecutor.kt`、`shizuku/`：终端执行与 Shizuku 生命周期。
- `Utils/SystemFileManagerLauncher.kt`：DocumentsUI 解析、定向与标准入口兜底。

所有加解密均在本地完成。请自行安全备份私钥和密码；丢失后无法恢复密文。旧版图片预览仍使用历史 AES/ECB 格式，仅用于兼容，不建议用于敏感数据。
