# 把 dist\笔录工具箱 打成可分发 zip。
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File tools\make_release.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "dist\笔录工具箱"
if (!(Test-Path (Join-Path $dist "笔录工具箱.exe"))) {
  throw "请先运行 tools\build_exe.ps1 生成 dist\笔录工具箱\笔录工具箱.exe"
}
$ver = (Select-String -Path (Join-Path $root "app\paths.py") -Pattern 'VERSION\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$zip = Join-Path $root ("dist\笔录工具箱-{0}-win64.zip" -f $ver)
if (Test-Path $zip) { Remove-Item $zip -Force }
Write-Host "[zip] $zip"
Compress-Archive -Path $dist -DestinationPath $zip -CompressionLevel Optimal
$size = (Get-Item $zip).Length
Write-Host ("完成：{0}（{1:N1} MB）" -f $zip, ($size / 1MB))
exit 0
