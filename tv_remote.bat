@echo off
REM Android TV 遥控器模拟器 (目标 AVD: thunderbird4k)
REM 用法: 先启动模拟器并确认 adb devices 能看到设备, 然后双击本脚本
title Android TV 遥控器模拟

:loop
echo.
echo ===== Android TV 遥控器 (thunderbird4k) =====
echo  1 = 上        2 = 下        3 = 左        4 = 右
echo  5 = OK 确定   6 = 返回      7 = 主页      8 = 菜单(Menu 三横线)
echo  9 = 退出
echo =============================================
set /p c=请输入数字:
if "%c%"=="1" adb shell input keyevent 19
if "%c%"=="2" adb shell input keyevent 20
if "%c%"=="3" adb shell input keyevent 21
if "%c%"=="4" adb shell input keyevent 22
if "%c%"=="5" adb shell input keyevent 23
if "%c%"=="6" adb shell input keyevent 4
if "%c%"=="7" adb shell input keyevent 3
if "%c%"=="8" adb shell input keyevent 82
if "%c%"=="9" exit /b
goto loop
