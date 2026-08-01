@echo off
chcp 65001 >nul
echo ===== Building T9IME... =====
call gradlew.bat assembleDebug
if %errorlevel% equ 0 (
    echo ===== Build Success! =====
) else (
    echo ===== Build Failed! =====
)
pause
