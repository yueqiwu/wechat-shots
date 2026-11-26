# 多代理共享构建锁：同一时刻只允许一个 agent 跑 Gradle，避免 build 目录互相踩踏。
# 用法（在 pwsh 里，本机 pwsh 实际是 Windows PowerShell 5.1，没有 pwsh.exe）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File D:\code\wechat-shot\tools\agent_build.ps1 -Task compile
#   -Task 可选 compile / assemble / test / install；install 时可加 -Serial emulator-5554
param(
    [ValidateSet('compile', 'assemble', 'test', 'install')]
    [string]$Task = 'compile',
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
$root = 'D:\code\wechat-shot\wechat-shots-android'
$lock = 'D:\code\wechat-shot\.gradle-agent.lock'
$deadline = (Get-Date).AddMinutes(40)
while ($true) {
    try {
        $fs = [System.IO.File]::Open($lock, 'CreateNew', 'Write', 'None')
        $fs.Close()
        break
    } catch {
        if (Test-Path $lock) {
            $age = (Get-Date) - (Get-Item $lock).LastWriteTime
            if ($age.TotalMinutes -gt 15) { Remove-Item $lock -Force -ErrorAction SilentlyContinue; continue }
        }
        Start-Sleep -Seconds 3
        if ((Get-Date) -gt $deadline) { Write-Error '等待 Gradle 构建锁超时（另一个 agent 正在构建？）'; exit 1 }
    }
}
try {
    $env:JAVA_HOME = 'E:\androidStudio\jbr'
    Set-Location $root
    switch ($Task) {
        'compile' { & .\gradlew.bat ':app:compileDebugKotlin' '--console=plain' }
        'assemble' { & .\gradlew.bat ':app:assembleDebug' '--console=plain' }
        'test' { & .\gradlew.bat ':app:testDebugUnitTest' '--console=plain' }
        'install' {
            & .\gradlew.bat ':app:assembleDebug' '--console=plain'
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
            $apk = "$root\app\build\outputs\apk\debug\app-debug.apk"
            if ($Serial) { & $adb -s $Serial install -r $apk } else { & $adb install -r $apk }
        }
    }
    exit $LASTEXITCODE
} finally {
    Remove-Item $lock -Force -ErrorAction SilentlyContinue
}
