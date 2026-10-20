# ============================================================
#  Register Windows Scheduled Task for auto-start (with watchdog)
#  Run as Administrator: powershell -ExecutionPolicy Bypass -File scripts\setup_autostart.ps1
# ============================================================
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$python = if ($env:MOXA_PYTHON) { $env:MOXA_PYTHON } else { 'D:\AI\Miniconda\python.exe' }
$supervisor = Join-Path $projectRoot 'scripts\supervisor.py'
$taskName = 'MoxaMonitor'

if (-not (Test-Path -LiteralPath $python)) { throw "Python not found: $python" }
if (-not (Test-Path -LiteralPath $supervisor)) { throw "Supervisor not found: $supervisor" }

$action = New-ScheduledTaskAction -Execute $python -Argument "-u `"$supervisor`"" -WorkingDirectory $projectRoot
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal `
    -Description 'MOXA proactive monitoring system (auto-start at boot, watchdog restart)' -Force | Out-Null

Write-Host "[OK] Scheduled task registered: $taskName (AtStartup, SYSTEM)"
Write-Host "     Start now : Start-ScheduledTask -TaskName $taskName"
Write-Host "     Status    : Get-ScheduledTask -TaskName $taskName"
