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

rem 并发与清理策略（可按机器性能调整）
rem UVR_WORKERS: worker 数量，留空/0 表示自动（GPU=1，CPU=核数一半且最多4）
rem UVR_QUEUE_SIZE: 异步任务队列容量，超出返回 503
rem UVR_RETENTION_HOURS: 上传与分离产物保留小时数，到期自动清理
if not defined UVR_QUEUE_SIZE set UVR_QUEUE_SIZE=8
if not defined UVR_RETENTION_HOURS set UVR_RETENTION_HOURS=6
if not defined UVR_CLEANUP_INTERVAL_SEC set UVR_CLEANUP_INTERVAL_SEC=1800
echo 队列容量=%UVR_QUEUE_SIZE%  产物保留=%UVR_RETENTION_HOURS%小时
echo.

"%PYTHON_EXE%" "%~dp0server.py"
pause
