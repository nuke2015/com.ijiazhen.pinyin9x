@echo off
chcp 65001 >nul
echo ===== APK Install Tool =====
set ADB=D:\advance\android\SDK\platform-tools\adb.exe
set PACKAGE_NAME=com.ijiazhen.pinyin9x
set APK_DIR=app\build\outputs\apk\debug

%ADB% version >nul 2>&1
if %errorlevel% neq 0 (
    echo adb not found
    pause
    exit /b
)

echo Connected devices:
%ADB% devices

echo.
echo Searching APK in %APK_DIR% ...
set APK=
for /f "delims=" %%f in ('dir /b /o-d "%APK_DIR%\*.apk"') do (
    set APK=%APK_DIR%\%%f
    goto :install
)

echo APK not found. Run debug_build.bat first.
pause
exit /b

:install
echo Found: %APK%
echo Installing...
%ADB% install -r "%APK%"
if %errorlevel% equ 0 (
    echo Install success. Launching app...
    %ADB% shell monkey -p %PACKAGE_NAME% -c android.intent.category.LAUNCHER 1
) else (
    echo Install failed.
)
pause
