@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo 没有找到 python，请先安装 Python 3 并勾选 Add to PATH。
  pause
  exit /b 1
)

echo 正在启动本地工具，浏览器会自动打开 http://127.0.0.1:8765
echo 关掉这个窗口就会停止服务。
echo.
python app\serve.py
echo.
echo 服务已停止。
pause
