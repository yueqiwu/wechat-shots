# 笔录工具箱 · 开发契约（v1）

> 目标：Windows 桌面端「笔录工具箱」，首个功能「微信聊天记录导出」，两种方案：
> **A. 手机截图导出**（USB 调试 / 无线 ADB，自动滚屏截图，相邻截图保留少量重叠（约 10%～25%，自动调整），输出无重叠分页）；
> **B. 录屏转截图**（导入手机录屏，自动抽帧、拼接、分页）。
> 低配电脑优先：内存占用小、CPU 单线程可跑、无外部 CDN、无 npm 依赖。

## 技术栈（已定，勿更换）

- 后端：Python 3.13 + 标准库（http.server / subprocess / ctypes），pywebview 6（WebView2）做窗口。
- 前端：原生 HTML/CSS/ES Module JS（无框架、无构建、无 CDN）。
- 视频解码：WebView2 内 `<video>` + Canvas（不要引入 ffmpeg）。
- ADB：随附 platform-tools 的 `adb.exe`，Python 以子进程调用（`app/adb.py`）。
- 打包：PyInstaller（onedir），产出 `dist/笔录工具箱/笔录工具箱.exe`。

## 目录与写作用域

```
record-toolbox/
  run.py, README.md
  app/           后端（lead 负责：main.py server.py api.py paths.py settings.py；adb.py 见下）
  web/           前端（ui 负责：index.html css/ js/）
    js/stitch.js       （stitch 负责：纯算法，无 DOM）
    js/video.js        （stitch 负责：录屏帧源 + 分析编排）
    js/adbcap.js       （stitch 负责：截图帧源 + 静态拼接）
    js/app.js api.js ui.js screens/*.js  （ui 负责）
  assets/        图标与插画（lead 生成 icon.ico/logo.png，ui 可加 svg）
  tools/         构建/图标/测试脚本
  tests/         单元测试（node --test / python -m unittest）
  docs/          契约与说明
```

**每个 agent 只改自己写作用域内的文件**；跨文件需求在消息里说明。中文 UI 文案、注释可用中文。

## 运行与测试

```powershell
cd D:\code\wechat-shot\record-toolbox
# 源码运行（窗口）
python run.py
# 只起服务（自动化测试/Chrome 调试），会打印 PORT
python run.py --headless --print-port
# 纯前端算法单测（Node 24 内置 test runner）
node --test tests\stitch.test.mjs
# Python 单元测试
python -m unittest discover -s tests -p "test_*.py"
```

## HTTP API（前端只通过它和后端交互）

- 统一：`POST /api/<action>`，JSON body，返回 `{ok:true,...}` 或 `{ok:false,error:"中文错误"}`。
- 静态：`GET /`、`GET /css/...`、`GET /js/...`、`GET /assets/...`。
- 媒体：`GET /media/video/<sid>`（支持 Range，供 `<video>` 用）；`GET /media/shot/<sid>/<shotId>.png`。

| action | 入参 | 返回 |
|---|---|---|
| `state` | – | `{app:{name,version,frozen,dataDir,platform}, settings:{...}, adb:{adbPath,adbFound,adbVersion,devices:[{serial,state,model,online,wireless}],error}}` |
| `settings.save` | `{settings:{...}}` | `{ok,settings}`；可保存键见 `app/settings.py` 的 DEFAULTS |
| `pick_video` | – | `{ok,sid,video:{name,size,path}}` 或 `{ok:false,canceled:true}` |
| `pick_file` | `{title?,label?,exts?:["*.exe"]}` | `{ok,path}` 或 `{ok:false,canceled:true}`（通用选文件，如设置里选 adb.exe） |
| `pick_folder` | `{title?}` | `{ok,path}` |
| `open_folder` | `{path}` | `{ok}` |
| `open_url` | `{url}` | `{ok}` |
| `session.create` | `{kind}` | `{ok,sid}` |
| `session.drop` | `{sid,keepFiles?}` | `{ok}` |
| `export.save` | `{sid?,dir?,zip?,files:[{name,data:"data:image/png;base64,..."}],meta:{contact,method,range,pageCount,folderName,stats}}` | `{ok,dir,zip,pageCount,message}` 或 `{ok:false,canceled:true}`；`dir` 为空时弹系统选择文件夹 |
| `devices` | `{adbPath?}` | 同 `state.adb` |
| `info` | `{serial?}` | `{ok,info:{width,height,density,android,topActivity}}` |
| `wake` / `stayoff` | `{serial?}` | `{ok}` |
| `screencap` | `{serial?,sid?,kind?}` | `{ok,sid,shot:{id,width,height,elapsedMs}}`；PNG 落盘，前端用 `/media/shot/<sid>/<id>.png` 取图 |
| `swipe` | `{serial?,x1,y1,x2,y2,duration?}` | `{ok}` |
| `tap` | `{serial?,x,y}` | `{ok}` |
| `keyevent` | `{serial?,code}` | `{ok}` |
| `wlan_ip` | `{serial?}` | `{ok,ip}` |
| `enable_tcpip` | `{serial?,port?}` | `{ok,message,ip}`（USB 引导无线：执行 `adb tcpip 5555`） |
| `connect` | `{host,port?}` | `{ok,message}` |
| `disconnect` | `{host,port?}` | `{ok}` |
| `pair` | `{host,port,code}` | `{ok,message}`（无线调试配对码） |
| `mdns` | – | `{ok,services:[{name,type:"pairing"|"connect",host,port}]}` |
| `log.write` | `{message}` | `{ok}` |
| `app.quit` | – | `{ok}` |

图片数据一律 `data:image/png;base64,...`；页面命名 `001.png`、`002.png`…（3 位补零）。

## JS 模块契约

### js/stitch.js —— 纯算法（无 DOM，可在 Node 中测试）

约定（与 Android 版 `wechatshot/stitch/StitchCore.kt` 完全一致，可直接对照移植）：

- 灰度值 `0..255`，`Uint8Array`，行优先 `gray[y*width+x]`。
- 分析坐标使用缩略帧（默认分析宽 `ANALYSIS_WIDTH = 144`，高度按比例取整）。
- `band = {top, bot}`（`[top, bot)` 行区间），所有 `sig` 只覆盖 band。
- `timeUs` 只是排序用的位置标记：视频用帧时间（微秒或秒均可，模块内部保持一致）；截图用帧序号。
- 约定「后一帧相对前一帧内容向上滚动」时位移为正；截图的帧数组必须**从旧到新**（最早的消息在前）。

导出 API（ES module，必须是这些名字/形态，UI 直接调用）：

```js
export const BINS = 8;
export const ANALYSIS_WIDTH = 144;

export function grayFromRGBA(rgba, width, height)            // Uint8ClampedArray -> Uint8Array
export function downscaleGray(gray, width, height, targetWidth) // -> {gray, width, height}
export function cropGray(gray, width, top, bottom)           // -> {gray, width, height}
export function rowActivity(grays, width, height)            // Array<Uint8Array> -> Float64Array
export function contentBand(activity, height)                // -> {top, bot}
export function rowSharpness(gray, width, top, bottom)       // -> Float64Array
export function rowSig(gray, width, height, bins = BINS)     // -> Uint8Array(height*bins)
export function sigActivity(sig, height, bins = BINS)        // -> Float32Array
export function alignAt(sigA, sigB, height, bins, shift, minOverlap) // -> {cost, ncc, rows}
export function scanShift(sigA, sigB, height, bins, lo, hi, minOverlap, hint) // -> {shift, ncc, cost}
export function matchPair(sigA, sigB, height, bins, center, win, maxShift, minOverlap=0.10) // -> {shift, ncc, cost, sat}
export function trackOffsets(frames, height, options)        // frames: [{timeUs, sig, sharp}] -> Track
export function buildRowMap(sharp, pos, cTop, cBot)          // -> {map:Int32Array, height, posMin, missing}
export function mapToOps(rowMap, pos, cTop, posMin)          // -> [{y0,y1,frame,src}]
export function cutPoints(act, total, tileH, win, minRatio=0.75, maxRatio=1.15) // -> Int32Array
export function median(arr)

// 分析编排（视频 + 截图共用）
// provider 接口：
//   provider.frameCount            // 帧数（视频=采样点的计算依据由 analyze 内部决定；截图=张数）
//   provider.kind                  // "video" | "static"
//   async provider.sample(times)   // times: number[]（视频=秒；截图=帧序号），返回 [{timeUs, gray, width, height}]
//                                   //   gray 是「整帧」灰度（宽=ANALYSIS_WIDTH），height 按比例
//   provider.frameWidth()/frameHeight()  // 原始帧像素尺寸
// analyze(provider, options, onProgress) -> AnalysisResult
//   options: {fps=10, refineRounds=2, maxFrames=2600, analysisWidth=144}
//   AnalysisResult: {aw, ah, band, frames, track, frameWidth, frameHeight, missingCount}
// planMaster(analysis, cropTopPx, cropBotPx, tileH) -> Plan
//   Plan: {topPx, botPx, cropHpx, masterH, ops, cuts:Int32Array, pageCount, missingPx, bandTop, bandH}
// renderPages(plan, renderer, onPage, onProgress) —— renderer 契约见下
//   renderer: { width, height, async pageImage(pageIndex, plan) -> canvas }  // 由 UI/模式提供
```

`Track` 结构：`{pos:Float64Array, step:Float64Array, ncc:Float64Array, cost:Float64Array, agree:Float64Array, bad:[{i,reason,ncc,cost}], maxShift, tol}`。

**静态截图模式**：`js/adbcap.js` 用 `analyze` 的 static 分支（不做补采）；若帧数 < 3 或 track.bad 超过 30%，抛 `StitchError("截图重叠不足或内容重复，无法拼接…")`，UI 显示「重试」引导。
**重叠要求**：截图采集时相邻截图内容位移约为可视聊天区高度的 75%～90%（即保留约 10%～25% 重叠，具体随机型/标题栏高度自动调整）；拼接算法本身支持 5%~50% 重叠，UI 需把实测重叠率展示出来。

### js/video.js —— 录屏模式

```js
export class VideoProvider {
  constructor(videoUrl)             // "/media/video/<sid>"
  async open()                      // 等 metadata，返回 {width,height,duration,name}
  kind = "video"
  async sample(times)               // 内部 seek + canvas 缩放，串行，带 2×rAF 等待
  frameWidth() / frameHeight()
}
export async function analyzeVideo(provider, options, onProgress) // 内部调 stitch.analyze
export async function renderVideoPages(plan, provider, {cropTopPx, cropBotPx, tileH}, onPage, onProgress) // 返回页数
```

### js/adbcap.js —— 截图模式

```js
export class ShotProvider {
  constructor(sid, shots)           // shots 按截图顺序（最新→最旧），内部反转成旧→新
  kind = "static"
  async sample(indices)
  frameWidth() / frameHeight()
}
export function detectCropFromShots(grays)   // 用 2~3 张截图找固定顶栏/底栏，返回 {topPx, botPx}（找不到返回 null）
export async function analyzeShots(sid, shots, {cropStatusInput=true}, onProgress) // -> AnalysisResult
export async function renderShotPages(plan, sid, shots, tileH, onPage, onProgress)
```

### js/api.js —— 前端请求封装（ui 负责）

```js
export async function api(action, payload = {})   // POST /api/<action>，自动 toast 错误
export async function loadImage(url)              // -> HTMLImageElement
export function shotUrl(sid, shotId)              // /media/shot/...
```

## 设计系统（iOS 风，ui 负责实现到 app.css）

- 背景 `#F2F2F7`，深色 `#000`；卡片 `#FFF` / `#1C1C1E`；分离线 `rgba(60,60,67,.12)`。
- 主色 iOS 蓝 `#0A84FF`，青色 `#22D3EE`（渐变 #3D8BFF→#22D3EE），成功 `#34C759`，警示 `#FF9500`，危险 `#FF3B30`。
- 文字：主 `#111114`，次 `#8E8E93`；标题 700；大标题 32~34px；正文 14~15px。
- 圆角：卡片 16~20px，按钮 14px，胶囊 999px；阴影极轻 `0 1px 2px rgba(0,0,0,.06)`。
- 字体：`"Segoe UI Variable Text","Segoe UI","Microsoft YaHei UI",system-ui`。
- 布局：左侧留白 24px；卡片内边距 18~20px；块间距 16px。窗口最小 1000×700。
- 顶栏：品牌 logo（圆角方块渐变 + 白色气泡图标）+「笔录工具箱」+ 右侧状态胶囊（已连接/未连接）。
- 底部 tab：首页 / 记录 / 设置（iOS tab bar 风格）。
- 首页两张方案卡：A 蓝色渐变（手机图标）「手机截图导出」；B 橙色渐变（胶片图标）「录屏转截图」。
- 所有交互有 hover/active 微动效（≤150ms），不用第三方动画库。

## 文案要求（重要）

- 全程中文；面向普通用户，不出现 adb/kotlin 等术语（可用「USB 调试」「无线调试」）。
- 提到重叠时：**「截图之间保留少量重叠（约 10%～25%），输出时自动去重」**。不得再出现 50% 重叠的说法。
- USB 调试引导要具体：设置 → 关于手机 → 连点版本号 7 次 → 开发者选项 → USB 调试 → 数据线连接 → 手机点「允许」。
- 提醒：导出期间手机不要锁屏；「录屏时尽量匀速滚动、每屏稍停」。

## 协作规则

- 只在自己的写作用域改文件；新增文件先确认不与他人冲突。
- 每完成一个模块：自测（node/python 单测或手动跑通）→ 汇报 lead（`send_message`）。
- 不执行 `git commit/push`（lead 统一做）；不删除他人文件。
- 后端接口如确需改动，先改 `docs/CONTRACTS.md` 并通知 lead 与 ui。
- 时间预算：核心模块 30-40 分钟；先跑通主流程，再打磨细节。
