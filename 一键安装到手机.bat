@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"

set "APK_FILE=%CD%\app\build\outputs\apk\debug\app-debug.apk"
set "PACKAGE_NAME=top.xjunz.automator"
set "ADB_EXE="
set "DEVICE_SERIAL="
set /a DEVICE_COUNT=0

echo ========================================
echo AutoSkip 一键安装到手机
echo ========================================

if not exist "%APK_FILE%" (
    echo [错误] 未找到 Debug APK：
    echo %APK_FILE%
    echo 请先双击“一键构建.bat”。
    goto :failed
)

call :find_adb
if not defined ADB_EXE (
    echo [错误] 未找到 adb.exe。
    echo 请在 Android Studio 中安装 Android SDK Platform-Tools，或将 adb 加入 PATH。
    goto :failed
)

if /I not "%ADB_EXE%"=="adb.exe" (
    for %%I in ("%ADB_EXE%") do set "PATH=%%~dpI;!PATH!"
)

echo [1/3] 正在检查 ADB 和已连接设备...
adb.exe start-server >nul 2>&1
for /f "skip=1 tokens=1,2" %%A in ('adb.exe devices 2^>nul') do (
    if "%%B"=="device" (
        set /a DEVICE_COUNT+=1
        set "DEVICE_SERIAL=%%A"
    )
)

if !DEVICE_COUNT! EQU 0 (
    echo [错误] 没有检测到已授权的 Android 设备。
    echo 请连接手机、开启 USB 调试，并在手机上允许这台电脑进行调试。
    echo.
    adb.exe devices -l
    goto :failed
)

if !DEVICE_COUNT! GTR 1 (
    echo [错误] 检测到多个 Android 设备。为防止安装到错误设备，请只保留一台设备连接。
    echo.
    adb.exe devices -l
    goto :failed
)

echo 已连接设备：!DEVICE_SERIAL!
if /I "%~1"=="--check" (
    echo [检查通过] APK、ADB 和目标设备均已准备就绪，未执行安装。
    goto :success
)

echo [2/3] 正在永久覆盖安装 Debug APK...
adb.exe -s "!DEVICE_SERIAL!" install -r "%APK_FILE%"
if errorlevel 1 (
    echo.
    echo [失败] APK 安装失败。脚本不会自动卸载旧版本，以免删除应用数据。
    goto :failed
)

echo [3/3] 正在确认安装结果...
set "INSTALLED_PATH="
for /f "delims=" %%P in ('adb.exe -s "!DEVICE_SERIAL!" shell pm path "%PACKAGE_NAME%" 2^>nul') do (
    set "INSTALLED_PATH=%%P"
)

if not defined INSTALLED_PATH (
    echo [警告] ADB 返回安装成功，但未查询到软件包路径，请在手机上手动确认。
    goto :success
)

echo.
echo [成功] AutoSkip 已永久安装到设备 !DEVICE_SERIAL!。
echo !INSTALLED_PATH!
goto :success

:find_adb
where adb.exe >nul 2>&1
if not errorlevel 1 (
    set "ADB_EXE=adb.exe"
    exit /b 0
)
if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" (
    set "ADB_EXE=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
    exit /b 0
)
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
    exit /b 0
)
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    exit /b 0
)
exit /b 0

:failed
echo.
if not defined AUTOSKIP_NO_PAUSE pause
exit /b 1

:success
echo.
if not defined AUTOSKIP_NO_PAUSE pause
exit /b 0
