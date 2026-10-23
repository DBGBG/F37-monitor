@echo off
rem ============================================================
rem  Proactive Operations & Maintenance System - start collector + business layer
rem  Usage: ops-collector\scripts\start_service.cmd
rem           (add  "--java=C:\path\to\java.exe"  if java is not on PATH)
rem ============================================================
setlocal
cd /d "%~dp0.."
if not defined OPS_PYTHON set "OPS_PYTHON=D:\AI\Miniconda\python.exe"
if not exist "%OPS_PYTHON%" (
  echo [ERROR] Python not found: %OPS_PYTHON%
  echo Set OPS_PYTHON to your python.exe path.
  exit /b 1
)
echo Starting Proactive Ops System (start_all + supervisor) using %OPS_PYTHON%
"%OPS_PYTHON%" -u scripts\start_all.py %*
endlocal
