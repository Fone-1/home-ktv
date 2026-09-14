@echo off
chcp 65001 >nul
title Home KTV - UVR-MDX-Net AI 人声分离微服务
cd /d "%~dp0"
echo ====================================================
echo   Home KTV - UVR-MDX-Net 伴奏提取 AI 微服务 (方案B)
echo ====================================================
echo.

rem 探测便携环境 Python 或系统 Python
set PYTHON_EXE=
if exist "%~dp0..\..\..\Users\fhj\Downloads\KTV伴奏提取制作人声分离\UVR-MDX-NET-Inst_HQ\runtime\python.exe" (
    set "PYTHON_EXE=%~dp0..\..\..\Users\fhj\Downloads\KTV伴奏提取制作人声分离\UVR-MDX-NET-Inst_HQ\runtime\python.exe"
) else (
    where python >nul 2>nul
    if %errorlevel% equ 0 (
        set PYTHON_EXE=python
    )
)

if "%PYTHON_EXE%"=="" (
    echo [错误] 未检测到有效的 Python 运行环境。
    pause
    exit /b 1
)

echo 当前 Python 解释器: %PYTHON_EXE%
echo 正在启动 AI 分离服务 (端口 8900)...
echo 后端服务对接地址: http://127.0.0.1:8900/api/separate
echo Web 管理控制台:   http://127.0.0.1:8900/
echo ====================================================
echo.

"%PYTHON_EXE%" "%~dp0server.py"
pause
