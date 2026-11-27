# 打包「笔录工具箱」为 Windows 可分发目录（PyInstaller onedir）。
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_exe.ps1
# 产物： dist\笔录工具箱\笔录工具箱.exe （目录整体可分发）
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "[1/3] 生成图标 ..."
python tools\make_icon.py | Out-Null

Write-Host "[2/3] PyInstaller 打包 ..."
if (Test-Path build) { Remove-Item build -Recurse -Force }
if (Test-Path dist) { Remove-Item dist -Recurse -Force }

$args = @(
  "-m", "PyInstaller",
  "--noconfirm", "--clean",
  "--windowed", "--onedir",
  "--name", "笔录工具箱",
  "--icon", "assets\icon.ico",
  "--add-data", "web;web",
  "--add-data", "assets;assets",
  "--add-data", "adb;adb",
  "--collect-all", "webview",
  "--hidden-import", "clr",
  "--hidden-import", "webview.platforms.winforms",
  "--hidden-import", "webview.platforms.edgechromium",
  "run.py"
)
& python @args
if ($LASTEXITCODE -ne 0) { throw "PyInstaller 失败，退出码 $LASTEXITCODE" }

$exe = Join-Path $root "dist\笔录工具箱\笔录工具箱.exe"
if (!(Test-Path $exe)) { throw "未找到产物：$exe" }

Write-Host "[3/3] 校验产物 ..."
$size = (Get-ChildItem (Join-Path $root "dist\笔录工具箱") -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host ("完成：{0}（目录共 {1:N1} MB）" -f $exe, ($size / 1MB))
Get-ChildItem (Join-Path $root "dist\笔录工具箱") | Select-Object Name | Format-Table -AutoSize
exit 0
