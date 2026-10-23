# ============================================================
#  Register Windows Scheduled Task for auto-start (watchdog, 3-tier)
#  Task name: ProactiveOpsMonitor  (系统对外名称: 主动运维系统)
#  Run as Administrator:
#    powershell -ExecutionPolicy Bypass -File ops-collector\scripts\setup_autostart.ps1
#
#  The task launches scripts\start_all.py, which pre-checks MySQL and then
#  supervises BOTH processes: python collector (run.py) + Spring Boot (jar).
# ============================================================
$ErrorActionPreference = 'Stop'

$monitorRoot = Split-Path -Parent $PSScriptRoot          # ...\ops-collector
$python = if ($env:OPS_PYTHON) { $env:OPS_PYTHON } else { 'D:\AI\Miniconda\python.exe' }
$pythonw = Join-Path (Split-Path -Parent $python) 'pythonw.exe'
$launcher = if (Test-Path -LiteralPath $pythonw) { $pythonw } else { $python }
$starter = Join-Path $monitorRoot 'scripts\start_all.py'
$taskName = 'ProactiveOpsMonitor'
# 旧任务名 (只体现单一厂商) 已废弃; 若机器上还存在就顺手删除, 避免两个看门狗同时拉起两层
$legacyTaskNames = @('OpsMonitor')

if (-not (Test-Path -LiteralPath $python)) { throw "Python not found: $python" }
if (-not (Test-Path -LiteralPath $starter)) { throw "start_all.py not found: $starter" }

# Locate a JVM for the business layer: OPS_JAVA > machine JAVA_HOME > PATH.
# The task runs as SYSTEM, so user-level JAVA_HOME is not visible at boot.
$java = $env:OPS_JAVA
if (-not $java) {
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if ($javaHome -and (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
        $java = Join-Path $javaHome 'bin\java.exe'
    }
}
if (-not $java) {
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { $java = $cmd.Source }
}

$argList = "-u `"$starter`""
if ($java) {
    $argList += " --java=`"$java`""
    Write-Host "[OK] JVM for business layer: $java"
} else {
    Write-Host "[WARN] No java.exe found (OPS_JAVA / machine JAVA_HOME / PATH are all empty)." -ForegroundColor Yellow
    Write-Host "       The collector will still start; install JDK 17 then re-run this script." -ForegroundColor Yellow
}

$action = New-ScheduledTaskAction -Execute $launcher -Argument $argList -WorkingDirectory $monitorRoot
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal `
    -Description 'Proactive Operations & Maintenance System (collector + Spring Boot, auto-start at boot, watchdog restart)' -Force | Out-Null

foreach ($legacy in $legacyTaskNames) {
    if ($legacy -ne $taskName -and (Get-ScheduledTask -TaskName $legacy -ErrorAction SilentlyContinue)) {
        Unregister-ScheduledTask -TaskName $legacy -Confirm:$false
        Write-Host "[OK] Removed legacy task: $legacy"
    }
}

Write-Host "[OK] Scheduled task registered: $taskName (AtStartup, SYSTEM)"
Write-Host "     launcher  : $launcher"
Write-Host "     start now : Start-ScheduledTask -TaskName $taskName"
Write-Host "     status    : Get-ScheduledTask -TaskName $taskName | Get-ScheduledTaskInfo"
Write-Host "     web ui    : http://127.0.0.1:8080"
