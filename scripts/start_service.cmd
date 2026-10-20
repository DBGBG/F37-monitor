@echo off
rem ============================================================
rem  MOXA Proactive Monitoring - start service with watchdog
rem  Usage: scripts\start_service.cmd
rem ============================================================
setlocal
cd /d "%~dp0.."
if not defined MOXA_PYTHON set "MOXA_PYTHON=D:\AI\Miniconda\python.exe"
if not exist "%MOXA_PYTHON%" (
  echo [ERROR] Python not found: %MOXA_PYTHON%
  echo Set MOXA_PYTHON to your python.exe path.
  exit /b 1
)
echo Starting MOXA monitor (supervisor) using %MOXA_PYTHON%
"%MOXA_PYTHON%" -u scripts\supervisor.py
