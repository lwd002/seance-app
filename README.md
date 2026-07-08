# Séance — ACE-Step Android Client

**English**: An Android WebView client for [ACE-Step 1.5](https://github.com/ace-step/ACE-Step-1.5), the open-source music generation model. Connects to your self-hosted ACE-Step API server over LAN. Key feature: all API traffic is routed through a native HTTP bridge, bypassing Android WebView's private-network request blocking that breaks plain web pages talking to LAN AI servers. APKs are built automatically via GitHub Actions — no local Android toolchain needed.


ACE-Step 音乐工作台（Séance）的安卓壳应用：一个极简 WebView 容器，内嵌 `ace-step-studio-mobile.html`，直连局域网内的 ACE-Step API。

## 为什么要做成 APP

手机 Chrome 对"网页访问局域网设备"有越来越严的安全拦截（Local Network Access / HTTPS 优先），经常今天能用明天报安全错误。自家 APP 的 WebView 不受这些浏览器政策限制，一次配置永久稳定，还有桌面图标。

## 构建（GitHub Actions 云端自动构建）

1. 推送本仓库到 GitHub（main 分支）
2. 每次 push 会自动构建；也可以在 Actions 页面手动点 "Run workflow"
3. 构建完成后到 **Actions → 最新一次运行 → Artifacts** 下载 `seance-apk`
4. 解压得到 `seance.apk`，传到手机安装（需允许"安装未知来源应用"）

## 更新网页版本

把新的 `ace-step-studio-mobile.html`（和可选的桌面版）覆盖到 `app/src/main/assets/`，提交并 push，等 Actions 构建出新 APK。

## 技术要点（改代码前先读）

- 页面通过 `WebViewAssetLoader` 以 `https://appassets.androidplatform.net/assets/...` 伪 https 源加载 —— **不要**改回 `file://`（file 源下 fetch/CORS 行为不可靠）
- API 是局域网 http，页面源是 https → 属于混合内容，靠 `MIXED_CONTENT_ALWAYS_ALLOW` + manifest 的 `usesCleartextTraffic="true"` 放行，两处都不能少
- 网页里的下载按钮在 WebView 里走 `window.AndroidBridge.saveFile(name, base64, mime)` 原生桥（HTML 里的 `triggerDownload` 已适配，普通浏览器里该分支自动跳过），文件写入系统「下载」目录（MediaStore，无需存储权限）
- minSdk 29（Android 10+），debug 签名 APK，仅自用不上架
