# ============================================================
#  Remove the auto-start Scheduled Task
#  Run as Administrator: powershell -ExecutionPolicy Bypass -File scripts\remove_autostart.ps1
# ============================================================
$ErrorActionPreference = 'Stop'
$taskName = 'MoxaMonitor'

if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Host "[OK] Scheduled task removed: $taskName"
} else {
    Write-Host "[INFO] Scheduled task not found: $taskName"
}
