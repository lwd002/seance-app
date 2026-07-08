# Séance Android App — 开发文档

> 目的：就算所有聊天记录都没了，看这份文档也能明白这个 APP 的结构、每个设计决定的原因、以及改动该动哪里。配合网页项目的 `SEANCE_DEV_NOTES.md`（在 `~/seance/` 和交付 zip 里）一起看。
> 最后更新：2026-07-08（v1.2）

---

## 1. 这是什么

Séance 网页版（`ace-step-studio-mobile.html`）的安卓壳。**它不是重写，是容器**：一个 Activity + 一个 WebView + 一个原生桥，网页代码 100% 复用。存在的唯一理由：手机 Chrome 对"网页访问局域网设备"的安全拦截（Local Network Access / HTTPS 优先 / 私有网络预检）越收越紧，用户隔三差五就被挡在门外；自家 APP 里这些浏览器政策全部可控。

- 仓库：github.com/lwd002/seance-app（私有），本地克隆 `~/seance-app`
- 构建：GitHub Actions 云端出 APK（本机不需要任何安卓工具链）
- 签名：debug 自签名，仅自用侧载，不上架

## 2. 文件结构

```
.github/workflows/build.yml    # push 到 main 自动构建 APK（artifact 名 seance-apk）
app/build.gradle               # versionCode/versionName 在这里改
app/src/main/
  AndroidManifest.xml          # INTERNET 权限 + usesCleartextTraffic
  java/com/seance/acestep/MainActivity.java   # 全部原生代码都在这一个文件里
  assets/ace-step-studio-mobile.html          # 网页本体（从 ~/seance 拷贝）
  assets/ace-step-studio.html                 # 桌面版（顺带打包，APP 未使用）
  res/                         # 图标（纯 XML 矢量，无 PNG）+ 应用名
```

## 3. 核心机制（每一条都是踩坑后的决定，别轻易改）

### 3.1 页面加载：WebViewAssetLoader 伪 https 源

页面不是用 `file://` 加载的，而是 `https://appassets.androidplatform.net/assets/...`（`WebViewAssetLoader` 在 `shouldInterceptRequest` 里把这个虚拟域映射到 APK assets）。**不要改回 file://**——file 源下 fetch/CORS/localStorage 行为都不可靠。

### 3.2 API 请求：全部走原生桥，不走 fetch（v1.1 的教训）

v1.0 直接让页面 fetch LAN 的 http API，结果：预检请求能到服务器（服务器日志有记录），正式响应被 WebView 客户端掐掉（新版 Chromium 对"安全源→私网地址"要求服务器响应 `Access-Control-Allow-Private-Network`，ACE-Step 没有），表现为红点连不上、无任何报错。混合内容放行（`MIXED_CONTENT_ALWAYS_ALLOW`）解决不了这一层。

**方案：Java 层发请求，浏览器政策整个绕开。** 协议如下：

- JS 调 `AndroidBridge.httpRequest(id, method, url, headersJson, bodyBase64)`（void，立即返回）
- Java 在线程池里用 `HttpURLConnection` 执行，完成后 `evaluateJavascript("window.__nativeHttpDone(id, status, base64Body)")` 回调
- JS 侧用 `{id → {resolve,reject}}` 的 pending 表把回调接回 Promise
- **status 为 0 表示原生层异常**（网络不通/地址错误），JS 侧转成 reject

网页侧的适配代码在 HTML 的 "Native HTTP adapter" 段：`NATIVE` 常量（检测 `window.AndroidBridge` 存在）决定 `apiRequest()` 走桥还是走原 fetch。**浏览器里 NATIVE=false，一切照旧——这段代码在 `~/seance/` 的源文件里维护，两个 HTML 都有，改动要保持同步（网页项目的双文件纪律照旧适用）。**

FormData（翻唱模式上传音频）的序列化技巧：`new Response(formData)` 会生成完整 multipart 编码，`r.headers.get('content-type')` 拿到含 boundary 的头，`r.arrayBuffer()` 拿到字节 → base64 交给桥。不需要手拼 multipart。

### 3.3 音频回传：本地缓存中转，不走 base64

生成的歌可能几 MB 到几十 MB（WAV），用 evaluateJavascript 传 base64 会爆。方案：JS 调 `AndroidBridge.downloadToCache(id, url)` → Java 下载到 `filesDir/audio/<uuid>.<ext>` → 回调返回虚拟地址 `https://appassets.androidplatform.net/audio/<file>`（AssetLoader 加了第二个 PathHandler：`InternalStoragePathHandler` 指向该目录）→ JS 对这个**同源**地址正常 fetch 出 blob，后续播放/入库逻辑不变。缓存目录每次启动清空（页面自己在 IndexedDB 里有持久副本）。

### 3.4 文件上传选择器（v1.2 的教训）

`<input type="file">` 在 WebView 里默认是**死的**（点了没反应）——必须实现 `WebChromeClient.onShowFileChooser` → `startActivityForResult(params.createIntent())` → `onActivityResult` 里 `parseResult` 回传。v1.1 漏了这个，翻唱页两个上传框点不动。

### 3.5 歌曲下载：saveFile 桥

WebView 里 blob 的 `<a download>` 也是死的。网页的 `triggerDownload()` 里有 APP 分支：FileReader 转 base64 → `AndroidBridge.saveFile(name, base64, mime)` → Java 用 MediaStore 写入系统「下载」目录（API 29+ 免存储权限，这也是 minSdk 29 的原因）。

### 3.6 其他

- 返回键：`onBackPressed` 先 `webView.goBack()`
- `usesCleartextTraffic="true"`：Java 层的 http 明文请求也受系统网络安全策略管，这个 manifest 开关对原生请求同样必要
- 图标：纯 XML 矢量（adaptive icon），项目里没有任何二进制资源，全文本可 git

## 4. 版本史

| 版本 | 内容 |
|---|---|
| 1.0 | 首版壳（fetch 直连）——❌ 被 WebView 私网拦截，红点 |
| 1.1 | API/音频全部原生化（3.2/3.3） |
| 1.2 | 修上传框（3.4 onShowFileChooser） |

## 5. 日常操作

**更新网页**：在 `~/seance/` 改好并验证（node --check 等，见网页项目笔记）→ `cp ~/seance/ace-step-studio*.html ~/seance-app/app/src/main/assets/` → 改 `app/build.gradle` 的 versionCode/versionName → commit + push → Actions 自动构建。

**拿 APK**：
```bash
cd ~/seance-app
RUNID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
gh run download $RUNID -n seance-apk -D ~/Downloads/seance-apk
```

**只改文档不想触发构建**：commit message 里加 `[skip ci]`。

**手机侧**：覆盖安装即可（versionCode 必须递增）；首次装要允许"未知来源"。

## 6. 已知限制 / 未来可做

- debug 签名：换手机重装没问题，但 versionCode 回退会装不上
- 大文件上传（翻唱源音频）走 base64 过桥，几十 MB 的源音频会慢/占内存——真遇到再优化（可改成 content:// URI 直读）
- 没有做设置项的原生备份，清 APP 数据 = 曲库和设置全丢（和网页版清浏览器数据同理）
- 如果以后 ACE-Step 服务端加了 `Access-Control-Allow-Private-Network` 响应头，理论上可以退回纯 fetch——但原生桥更稳，没必要退
