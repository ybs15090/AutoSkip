@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

set "GRADLEW=%CD%\gradlew.bat"
set "CONSTANTS_FILE=%CD%\app\src\main\java\top\xjunz\automator\Constants.kt"
set "CUSTOM_FILE=%CD%\custom.properties"
set "CUSTOM_BACKUP="
set "APK_FILE=%CD%\app\build\outputs\apk\debug\app-debug.apk"

echo ========================================
echo AutoSkip 一键构建
echo ========================================

if not exist "%GRADLEW%" (
    echo [错误] 未找到 gradlew.bat，请将脚本放在项目根目录。
    goto :failed
)

if not exist "%CONSTANTS_FILE%" (
    echo [错误] 未找到本地配置文件：
    echo %CONSTANTS_FILE%
    echo 请先按照 README 创建 Constants.kt。
    goto :failed
)

if not exist "%CUSTOM_FILE%" goto :run_build
set "CUSTOM_BACKUP=%TEMP%\AutoSkip_custom_%RANDOM%_%RANDOM%.properties"
copy /y "%CUSTOM_FILE%" "%CUSTOM_BACKUP%" >nul
if errorlevel 1 (
    echo [错误] 无法备份 custom.properties。
    set "CUSTOM_BACKUP="
    goto :failed
)

:run_build
echo [1/2] 正在构建 Debug APK...
call "%GRADLEW%" assembleDebug
set "BUILD_EXIT=%ERRORLEVEL%"

call :restore_custom_properties

if not "%BUILD_EXIT%"=="0" (
    echo.
    echo [失败] Gradle 构建失败，退出代码：%BUILD_EXIT%
    goto :failed
)

if not exist "%APK_FILE%" (
    echo.
    echo [失败] Gradle 已结束，但未找到 Debug APK：
    echo %APK_FILE%
    goto :failed
)

echo [2/2] 已还原 custom.properties。
echo.
echo [成功] Debug APK 已生成：
echo %APK_FILE%
for %%F in ("%APK_FILE%") do echo 文件大小：%%~zF 字节
goto :success

:restore_custom_properties
if not defined CUSTOM_BACKUP exit /b 0
if not exist "%CUSTOM_BACKUP%" exit /b 0
copy /y "%CUSTOM_BACKUP%" "%CUSTOM_FILE%" >nul
del /q "%CUSTOM_BACKUP%" >nul 2>&1
set "CUSTOM_BACKUP="
exit /b 0

:failed
call :restore_custom_properties
echo.
if not defined AUTOSKIP_NO_PAUSE pause
exit /b 1

:success
echo.
if not defined AUTOSKIP_NO_PAUSE pause
exit /b 0
