# 聊天截图导出（wechat-shot）

Android 应用「聊天截图导出」的完整工程：App 源码、官网下载页、宝塔自动发布脚本。

- 官网：<https://qipaclub.top/wechat-shots-app/>
- 最新版本信息：<https://qipaclub.top/wechat-shots-app/version.json>
- 应用包名：`com.dsh.wechatshot`，最低 Android 11（API 30）

## 功能

两种导出方案，输出都是「不重叠、不重复、顺序完整」的截图：

1. **无线 ADB 截图导出**：手机与电脑同一 Wi-Fi，开启无线调试后自动连接；App 自动翻页截屏，按内容拼接去重，输出完整长图。
2. **录屏转截图**：系统录屏聊天过程后导入录屏，App 自动抽帧、按滚动位移拼接并切分成单页截图，无需电脑。

截图、录屏、拼接全部本地处理，不上传聊天内容；无需 root。

## 目录结构

```
wechat-shots-android/   Android App 源码（Kotlin + Compose）
  app/                  主模块
  mockwechat/           模拟微信测试 App
  dist/                 构建产物 APK
  README.md             App 构建 / 使用说明
website/               官网静态站点（无外部 CDN，系统字体 + 内联 SVG）
  index.html
  styles.css
  app.js
  favicon.svg
  version.json         版本信息模板（发布时由 release_bt.py 写入服务器）
tools/
  release_bt.py         发布 APK + version.json 到宝塔服务器（分片上传 + 公网校验）
  deploy_site_bt.py     部署 website/ 静态文件到服务器
  agent_build.ps1       带全局构建锁的 Gradle 构建入口
wechat-shots/           旧网页版工具（归档，不再更新）
```

## 构建 App

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\agent_build.ps1 -Task compile
powershell -NoProfile -ExecutionPolicy Bypass -File tools\agent_build.ps1 -Task assemble
```

产物在 `wechat-shots-android/dist/`。

## 发布新版本

1. 升级 `wechat-shots-android/app/build.gradle.kts` 中的 `versionCode` / `versionName`。
2. 构建 release APK。
3. 上传 APK 并写 version.json：

```bash
python tools/release_bt.py \
  --apk wechat-shots-android/dist/wechat-shots-1.1.0-release.apk \
  --version 1.1.0 --code 3 \
  --notes "新增 xxx
修复 yyy"
```

脚本会：
- 用宝塔 API 的 `upload` 分片接口把 APK 上传到 `C:/wwwroot/qipaclub.top/wechat-shots-app/`（8MB 分片、断点续传、失败重试）；
- 写入 `version.json`（versionCode / versionName / apk / minSdk / notes / publishedAt / size / sha256）；
- 校验公网 `HEAD APK` 返回 200 且 `Content-Length` 与本地文件一致，`version.json` 返回 200 且 JSON 字段正确。

> 为什么不用 `files/UploadFile`：宝塔 Windows 面板 8.2.0 的 multipart 上传接口在 API Token
> 调用下会返回 385 字节的「入口校验失败」页面，改用面板前端的分片接口
> `POST /files?action=upload`（urlencoded + base64 分片，追加写入 `.upload.tmp` 后改名）。

## 部署官网

```bash
python tools/deploy_site_bt.py
```

按 `website/` 下的文件逐个写入服务器（`CreateFile` + `SaveFileBody`，UTF-8），
默认跳过 `version.json`（由发布脚本写），并清理历史探测遗留文件；
最后用公网请求校验首页 / CSS / JS / favicon 状态码与关键内容。

## App 内更新

`app/src/main/java/com/dsh/wechatshot/update/UpdateGate.kt` 在启动时请求
`version.json`（5 秒超时、失败静默），当远端 `versionCode` 大于当前
`BuildConfig.VERSION_CODE` 时弹窗展示更新说明，下载 APK 到
`cacheDir/update/update.apk`，再通过 FileProvider 交给系统安装器
（`application/vnd.android.package-archive`）；未授予「安装未知来源应用」时引导去设置授权。

## 服务器

- 宝塔面板：<http://43.142.178.233:8888>（API 密钥见内部文档）
- 站点根目录：`C:/wwwroot/qipaclub.top`，本站目录 `wechat-shots-app/`
- 发布脚本通过环境变量覆盖配置：`BT_PANEL_BASE` / `BT_API_KEY` / `BT_PUBLIC_BASE` / `BT_SERVER_DIR`。
