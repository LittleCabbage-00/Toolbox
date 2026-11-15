# Toolbox v3.1.1

自 3.1.0 以来的修复版本，重点解决二维码扫描器首次相机授权后预览黑屏的问题，并接入自动化构建发布。

## 修复扫码器首次授权黑屏

### 相机权限与 Surface 生命周期同步（本次修复）

- 首次打开扫码页时，`SurfaceView` 可能在用户授权相机前就已创建；原逻辑在授权后仅重新注册回调，可能错过已发生的 `surfaceCreated`，导致页面保持黑屏。
- 授权回调现在会在下一帧检查现有 Surface 是否有效；只要页面已恢复、权限已授予且 Surface 已创建，就立即打开相机预览。
- 从系统设置返回后会重新读取相机权限状态，避免此前拒绝权限再手动开启后无法恢复预览。
- 相机打开前统一等待“页面已恢复 + 权限已授予 + Surface 已有效”三个条件，避免重复打开或后台状态启动预览。

## 工程与发布

### 移除 Claude 本地配置

- 删除项目中的 `.claude` 本地权限配置，并加入忽略规则，仓库不再包含 Claude 的贡献者或本地工具信息。

### GitHub Actions 自动发布

- 新增 `.github/workflows/release.yml`。
- 推送 `v*` 标签时，Actions 会使用 JDK 17 构建 `arm64-v8a` Release APK。
- 工作流会生成临时签名、验证 APK、上传 Actions Artifact，并自动创建 GitHub Release 和附件。

## 版本信息

- 版本：`3.1.1 (3010100)`
- 最低系统：Android 8.0（API 26）
- 目标系统：Android 15 / API 37
- 发布 ABI：`arm64-v8a`

> CI 使用临时 debug keystore 生成可安装 APK。若要用于应用商店，请在仓库 Secrets 中配置正式签名证书并替换工作流签名步骤。
