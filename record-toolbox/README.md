# 笔录工具箱（Record Toolbox）

Windows 桌面小工具，用于把微信聊天记录整理成连续的长截图 / 分页截图。
本地处理、不联网上传，面向低配电脑优化（Python + WebView2，无 Electron）。

## 已有功能

### 1. 手机截图导出（USB 调试 / 无线调试）

1. 手机上开启「USB 调试」（或者先用 USB 连一次再切换为无线）。
2. 在手机上打开微信 → 进入要导出的聊天 → 滑到你希望开始的位置。
3. 点「开始导出」：工具会自动滚动、逐屏截图（相邻截图保留少量重叠，约 **10%～25%**，随机型自动调整），
   然后自动对齐、去重、拼接成长图并切成互不重叠的分页截图。

特点：

- 截图之间保留少量重叠（约 10%～25%），输出时自动去重，页与页上下连续，不会重复一整块内容。
- 自动裁剪手机状态栏、标题栏和输入框。
- USB 调试引导内置在界面上，按步骤点就行；也支持「先 USB 再切无线」和
  「无线调试配对码」两种无线方式。
- 导出完成后可一键打包 ZIP、打开文件夹。

### 2. 录屏转截图

1. 用手机自带的录屏功能录一段「在微信里匀速向上滚动聊天记录」的视频。
2. 导入工具，自动抽帧、分析滚动位移、重建完整长图，再切成互不重叠的截图页。

特点：不需要连接手机；自动识别滚动内容区、裁掉状态栏和输入框；可导出 ZIP。

## 实测数据

| 场景 | 输入 | 结果 | 耗时 |
| --- | --- | --- | --- |
| 手机截图导出（模拟器 1080×2400） | 16 张原始截图，实测重叠 13.6% | 长图 26,108 px → 13 页，页高合计=长图高 | 38 s（含自动滚动截图） |
| 手机截图导出（合成样本 720 宽） | 6 张 10% 重叠截图 | 长图 6,580 px → 6 页 | 2 s（拼接） |
| 录屏转截图 | 720×1584 / 24 s 录屏 | 119 页连续截图 + ZIP | 27 s（含分析拼接出图） |

> 测试机为开发机（20 核）；低配电脑上录屏方案主要耗时在抽帧解码，可调低「分析帧率」或缩短录制时长。

## 运行（源码）

需要 Windows 10/11（自带 WebView2）、Python 3.10+。

```powershell
cd record-toolbox
python -m pip install pywebview
python run.py
```

首次使用「手机截图导出」需要 ADB（Android 平台工具）：

- 把 `platform-tools` 里的 `adb.exe`、`AdbWinApi.dll`、`AdbWinUsbApi.dll`
  放到 `record-toolbox\adb\` 目录（打包版已内置），或在「设置」里指定 adb.exe 路径。
- 没有装 Android SDK 也可以去 Google 官网下载 platform-tools 解压即用。

## 打包

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_exe.ps1
```

产物：`dist\笔录工具箱\笔录工具箱.exe`（整个目录可分发，含 WebView2 之外的全部依赖与 adb）。

## 开发

```powershell
# 只起本地服务（便于用 Chrome 调试）
python run.py --headless --print-port
# 前端算法单测（Node 24+）
node --test tests\stitch.test.mjs
# Python 单测
python -m unittest discover -s tests -p "test_*.py"
```

目录结构见 `docs/CONTRACTS.md`（含 HTTP API 与前端模块契约）。

## 隐私

所有截图、视频、拼接计算都在本机完成；工具不联网、不上传任何聊天内容。
