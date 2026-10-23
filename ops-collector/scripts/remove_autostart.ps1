# ============================================================
#  Remove the auto-start Scheduled Task (主动运维系统)
#  Run as Administrator: powershell -ExecutionPolicy Bypass -File scripts\remove_autostart.ps1
# ============================================================
$ErrorActionPreference = 'Stop'
# 新任务名 + 已废弃的旧任务名 (旧名只体现单一厂商)
$taskNames = @('ProactiveOpsMonitor', 'OpsMonitor')

foreach ($taskName in $taskNames) {
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Host "[OK] Scheduled task removed: $taskName"
    } else {
        Write-Host "[INFO] Scheduled task not found: $taskName"
    }
}
