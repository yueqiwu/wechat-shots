# 微信聊天记录「截图导出」工具调研

调研时间：2026-11-27。星数为当日 GitHub 快照，仅供量级参考。

## 0. 结论

现成工具可以分成三条完全不同的技术路线，搜索时最容易混淆的就是它们：

| 路线 | 做法 | 拿到的产物 | 代表 |
| --- | --- | --- | --- |
| A. 数据导出 | 解密本地数据库，解析消息 | HTML / PDF / TXT / CSV / Word | Moect、WechatMsg、WechatExporter |
| B. 界面滚动截图 | 驱动真实界面滚动，边滚边截再拼 | 一张超长图 / PDF | PixPin、FastStone、白云截屏、wechat-longshot |
| C. 录屏/截图后处理 | 录屏或已有截图，抽帧、匹配、拼接 | 截图序列 / 长图 / TXT | ChatScreenExporter、wechat_screenshot_tool、Picsew |

关键判断：

- **专门为"微信聊天记录 → 截图"做的成熟产品几乎不存在。** PC 端靠通用滚动截图软件就能覆盖，手机端靠系统滚动截屏 + 通用拼接 App 覆盖。
- **数据导出赛道非常卷**（上千星的 Windows 工具就有 10 个以上），但产物都是 HTML/PDF/文本，不是截图；它们与截图路线是互补关系。
- **形态最接近本项目的开源项目只有两个**：macOS 的 [wechat-longshot](https://github.com/EtheXReal/wechat-longshot)（自动滚动 + 模板匹配拼接 + PDF）和 Android 的 [白云截屏](https://github.com/touensan/whiteyun-screenshot)（自动/手动滚屏 + 多图拼接）。两者输出都是"一张巨长图"，不做分页。
- 搜索时大量结果是**伪造聊天截图生成器**（微信对话生成器、朋友圈截图生成器），那是"造图"不是"导出"，与真实导出需求无关，注意排除。

---

## 1. PC 端

### 1.1 通用滚动截图（日常最实用）

微信 PC 版窗口本身可滚动，所以不需要微信专用工具，通用长截图软件直接可用：

| 工具 | 平台 | 价格 | 微信支持情况 | 备注 |
| --- | --- | --- | --- | --- |
| [PixPin](https://pixpin.cn/features/long-screenshot/) | Windows / macOS | 免费 | 官方文档明确写支持微信、QQ、飞书聊天记录；框选后按 `L` 再滚动鼠标 | 国内产品，中文文档最全，有自动滚动模式 |
| [FastStone Capture](https://www.faststonecapture.cn/article/2050.html) | Windows | 商业（有试用） | 「捕捉滚动窗口」对微信/QQ 对话框有效 | 老牌工具，稳定但界面老旧 |
| PicPick | Windows | 个人免费 | 滚动捕获，可用 | 附带编辑器 |
| Snipaste | Windows / macOS | 免费 | **不支持滚动截图**，只有单屏 | 常被误推荐，实际做不了长图 |

### 1.2 微信数据导出（导出 HTML/PDF 后再打印或截图）

| 项目 | 平台 | 星 | 微信版本 | 输出 | 备注 |
| --- | --- | --- | --- | --- | --- |
| [Moect](https://github.com/mofan6/Moect) | Windows | ~935 | 4.x | HTML/JSON/JSONL/TXT/CSV/Markdown/Excel | GPL-3.0，有一键安装包，支持朋友圈、收藏、表情包；README 明确提到防腾讯 DMCA 失联 |
| [WechatBakTool](https://github.com/SuxueCode/WechatBakTool) | Windows | ~3.7k | PC 版 | 备份 + 解密 + 导出 | C#，图形界面 |
| [WeChatMsg（留痕）](https://github.com/LC044/WeChatMsg) | Windows | 老牌大项目 | 3.x 为主 | HTML/Word/Excel/CSV/TXT | 最出名的方案，4.x 适配以社区分支为主 |
| [WeChatDataAnalysis](https://github.com/LifeArchiveProject/WeChatDataAnalysis) | Windows | ~4.4k | 4.x | 聊天记录 + 朋友圈 + 年报 | Python |
| [echotrace](https://github.com/ycccccccy/echotrace) | Windows | ~3.9k | 4.x | 导出 + 分析 + 年度报告 | 本地运行 |
| [wechat-local-viewer](https://github.com/ops120/wechat-local-viewer) | Windows | ~402 | 4.x | 本地浏览/全文搜索/多格式导出 | 零外部依赖 |
| [WechatExporter](https://github.com/BlueMatthew/WechatExporter) | Windows / macOS | ~8.6k | 读 iTunes 备份 | **Text / HTML / PDF** | C++，手机端数据导出的关键工具，见 2.3；最后测试到微信 8.0.9 / iOS 15，新版需自行验证 |
| [wechat-export-macos](https://github.com/ydotdog/wechat-export-macos) | macOS | ~116 | macOS 版 | 解密导出 | 一键脚本 |
| [wx4py](https://github.com/claw-codes/wx4py) | Windows | ~817 | 4.x | 自动化 + 聊天记录导出 | 偏机器人/群发 |

**注意**：这条路线的产物没有一张是"截图"。想要图，得自己把 HTML 用浏览器打印成 PDF，或整页截图。

### 1.3 专门做微信长截图的（都很小众）

| 项目 | 平台 | 做法 | 成熟度 |
| --- | --- | --- | --- |
| [wechat-longshot](https://github.com/EtheXReal/wechat-longshot) | macOS 13+，微信 4.x | 找到窗口 → 识别消息区 → 自动滚动截图 → 模板匹配对齐 → 长图 + PDF | 0 星但实现完整；需屏幕录制 + 辅助功能权限；Python 3.12 + uv |
| [inventor6/WeChat-long-screenshot](https://github.com/inventor6/WeChat-long-screenshot) | — | Python 拼已有截图 | 玩具级 |
| [T-K-233/WeChat-Long-Image-Helper](https://github.com/T-K-233/WeChat-Long-Image-Helper) | — | 拼接多张微信截图 | 玩具级 |
| [龙班截屏](https://github.com/zengfw/LongScreenShot) | Android | 老牌 Android 长截屏 | 已不适配高版本 Android，是白云截屏的前身 |

`wechat-longshot` 的 README 里有一句很关键的观察：**微信 4.x 在 macOS 上是自绘界面，消息列表没有任何可访问性（Accessibility）节点，本地数据库也加密**。所以它只能走"驱动真实界面截图"的路子——这正是本项目在 Android 上遇到同一类约束时的参照。

### 1.4 取证 / OCR 路线（产物是文字，不是图）

| 项目 | 做法 | 产物 |
| --- | --- | --- |
| [ChatScreenExporter](https://github.com/suyuwithoutPainkillers/ChatScreenExporter) | Windows 框选聊天区 → 自动滚动 → Tesseract OCR 或 GPT-4.1 vision 识别 | TXT（时间 + 发送人 + 内容） |
| [wechat_screenshot_tool](https://github.com/tukihibiki/wechat_screenshot_tool) | 对聊天录屏抽帧，强制相邻截图文字相似度 < 25% | 截图序列（取证用） |
| 权利卫士（商业 App） | 录屏 + 可信时间戳 | 证据视频 |

取证类工具的共同点：**要的是"过程可验证"，不是"图好看"**，所以普遍不做无缝拼接。

### 1.5 数据 → 图片渲染

| 项目 | 做法 |
| --- | --- |
| [wechat-group-daily-report](https://github.com/kbwzy/wechat-group-daily-report) | 群聊某天 → PNG 长图 + HTML + Markdown；Playwright 按 430 CSS 像素宽、3 倍缩放分段截图再拼接 |

这是"先有结构化数据、再渲染成图"的路线，和直接截真实界面的结果在字体、气泡样式上会有差异，但可控性最高。

---

## 2. 手机端

### 2.1 系统自带能力

| 平台 | 能力 | 实际体验 |
| --- | --- | --- |
| Android（MIUI/HyperOS、ColorOS、OriginOS、One UI、EMUI） | 系统滚动截屏，微信里可直接用 | 短对话够用；聊天列表是自绘 + 复用，超长记录容易截断、重复或直接失败 |
| iOS | **没有系统级滚动截屏** | 只能逐屏截图再拼接 |
| 微信自身 | **没有"导出为长图"功能** | 只有「聊天记录迁移与备份」，且备份不能直接变成图片 |

### 2.2 通用长截图 App

| 工具 | 平台 | 说明 |
| --- | --- | --- |
| [白云截屏 WhiteYun](https://github.com/touensan/whiteyun-screenshot) | Android，开源（Java，~15 星） | 自动滚屏 / 手动滚屏 / 速度模式，多图拼接、网页长图、后台任务队列、历史草稿；拼接算法单独开源在 [whiteyun-long-screenshot-algorithms](https://github.com/touensan/whiteyun-long-screenshot-algorithms)（重叠搜索 + 分块一致性 + 重复内容消歧 + 安全接缝） |
| 小鱼截图拼接生成器 | Windows/Android | 悬浮窗截图、滚动长截图、自动识别重叠边缘 |
| 滚动截屏 / AI 长图截屏 等 | Android | 应用市场里的通用滚动截屏，质量参差 |
| [Picsew](https://apps.apple.com/us/app/picsew/id1208145167) | iOS | 最主流的滚动截图 + 长图拼接，支持录屏/多图导入 |
| 长截图 / Tailor | iOS | 同类拼接工具 |
| 快捷指令「拼接图像」 | iOS | 系统自带，能垂直拼接但**不做重叠检测**，接缝靠人眼 |

### 2.3 手机端数据导出（不产生截图）

| 平台 | 方案 | 门槛 |
| --- | --- | --- |
| iOS | [WechatExporter](https://github.com/BlueMatthew/WechatExporter)：iTunes 本地备份 → 解析 → Text/HTML/PDF | 免越狱，需要电脑 + iTunes/Finder 备份；对新版 iOS/微信兼容性需实测 |
| iOS | WX Backup：读 iTunes 备份导出 HTML，图片视频分目录 | 免越狱，老工具，导出常报错但文件基本齐全 |
| iOS / Android / 鸿蒙 | 楼月微信聊天记录导出恢复助手（商业软件） | 免 root/越狱（官方宣称），付费，Windows 端运行 |
| Android | 免 root 基本没有可靠方案；root 后用 wechat-dump 类工具直接读库 | 需 root，风险高 |
| 通用 | 微信「聊天记录迁移与备份」把手机记录迁到电脑微信，再用 PC 端工具处理 | 官方路径，最稳，但只解决"数据到电脑"，不解决"变成图" |

Android 还有一个变通：把记录迁移到 iPad/iPhone，再走 iTunes 备份 + WechatExporter，绕开 root。

---

## 3. 与本项目（wechat-shot）的位置对比

| 维度 | 系统滚动截屏 | 白云截屏 | PixPin / FastStone | 数据导出类 | wechat-shot |
| --- | --- | --- | --- | --- | --- |
| 输出形态 | 一张巨长图 | 一张巨长图 | 一张长图 | HTML/PDF/TXT | **分页、页间零重叠的截图** |
| 自动翻页 | 系统代劳，长列表易失败 | 自动滚屏 | PC 上自动滚动 | — | ADB 自动翻页 / 录屏抽帧 |
| 去重与对齐 | 一般 | 重叠搜索 + 重复消歧 | 算法拼接 | — | 按内容拼接 + 页级 seam 质量门 |
| 需要 root / 解密 | 否 | 否 | 否 | 多数需要 | 否 |
| 几万像素的超长聊天 | 容易截断 | 可以（但图不可分享） | 可以 | 可以 | 切页后可读、可分享、可打印 |
| 平台 | 手机 | Android | PC | PC / iOS 备份 | Android |

**空档很清楚**：通用滚动截屏给的是"一张几万像素、发不出去、打印会劈字"的巨图；数据导出给的是"不是截图"的 HTML。中间那块——**手机端、自动翻页、分页零重叠、带质量校验、无需 root**——目前没有成熟产品占住。

PC 端如果以后要扩，`PixPin` 已经牢牢占住"一张长图"的位置，机会同样只在"分页 + 自动翻页 + 质量门"。

---

## 4. 风险与合规提示

- **DMCA / 下架风险**：Moect 的 README 专门写了"为防止腾讯潜在 DMCA 投诉导致项目失联"而建备用群。数据解密类项目风险最高。
- **账号风险**：读取/解密本地数据库的工具可能触发风控；屏幕采集类不碰数据，风险显著更低。
- **门槛**：无线 ADB 在 Android 11+ 每次重启都要重新配对，是截图路线最大的使用门槛；录屏路线无门槛但要求录屏期间持续滚动。
- **证据力**：纯截图在取证场景证据力弱（可被质疑拼接），录屏 + 时间戳才具备较强的证明力；如果目标是取证，应该向"录屏 + 过程可验证"靠，而不是追求图好看。

---

## 5. 一句话选型建议

- 只是想存下来：Android 用系统滚动截屏，iOS 用 Picsew，PC 用 PixPin。
- 想要可检索、可分析：走数据导出（Windows 选 Moect / WechatMsg 系，iOS 备份选 WechatExporter）。
- 想要"能发出去、能打印"的整齐截图：目前没有现成产品，这正是本项目的位置。
