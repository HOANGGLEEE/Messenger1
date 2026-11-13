# Messenger 581 / HushMessenger Unsend diagnostics collector.
# Run in Windows PowerShell with Android Platform Tools (adb) installed.
# Captures allow-listed HushMessenger diagnostics and the Messenger process timeline.
$ErrorActionPreference = 'Stop'
$package = 'com.facebook.orca'
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Host 'ADB not found. Install Android Platform Tools and add adb to PATH.' -ForegroundColor Red
    exit 1
}
$adbPath = $adb.Source
$connected = @(& $adbPath devices | Where-Object { $_ -match '\sdevice\s*$' })
if ($connected.Count -ne 1) {
    Write-Host "Connect exactly one authorized Android device. Found $($connected.Count)." -ForegroundColor Red
    & $adbPath devices
    exit 2
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$dir = Join-Path (Get-Location) "Messenger581_Unsend_Debug_$stamp"
New-Item -ItemType Directory -Path $dir -Force | Out-Null

$metadataFile = Join-Path $dir 'package_and_hook_metadata.txt'
$beforeFile = Join-Path $dir 'processes_before.txt'
$afterFile = Join-Path $dir 'processes_after.txt'
$timelineFile = Join-Path $dir 'process_timeline.txt'
$logcatFile = Join-Path $dir 'filtered_logcat.txt'
$logcatErrorFile = Join-Path $dir 'logcat_stderr.txt'
$stopFile = Join-Path $dir '.stop_process_monitor'

@(& $adbPath shell dumpsys package $package |
    Select-String -Pattern 'versionName=|versionCode=|targetSdk=|hush\.feature\.keep_unsent|hush\.unsent\.') |
    Out-File -FilePath $metadataFile -Encoding UTF8

Write-Host 'Messenger package information:' -ForegroundColor Cyan
Get-Content $metadataFile

Write-Host ''
Write-Host 'Stopping Messenger to reset in-memory diagnostic counters (no app data is deleted).' -ForegroundColor Yellow
& $adbPath shell am force-stop $package
Start-Sleep -Milliseconds 700
@(& $adbPath shell ps -A | Select-String -Pattern 'com\.facebook\.orca') |
    Out-File -FilePath $beforeFile -Encoding UTF8

# Remove only the previous exported diagnostics file so ADB cannot pull a stale report.
& $adbPath shell rm -f /sdcard/Download/HushMessenger-Unsend-Diagnostics.json
Write-Host 'Clearing old logcat entries. This does not clear Messenger data.'
& $adbPath logcat -c

$logcatProcess = Start-Process -FilePath $adbPath -ArgumentList 'logcat -v threadtime -s HushMessengerUnsend:V HushMessenger:V AndroidRuntime:E ActivityManager:I ActivityTaskManager:I' -RedirectStandardOutput $logcatFile -RedirectStandardError $logcatErrorFile -PassThru -WindowStyle Hidden

# Poll ps while the user performs the test so short-lived :background_e2ee processes are not missed.
$monitor = Start-Job -ArgumentList $adbPath, $stopFile, $timelineFile -ScriptBlock {
    param($adbExe, $stopPath, $outputPath)
    while (-not (Test-Path -LiteralPath $stopPath)) {
        $now = Get-Date -Format 'yyyy-MM-ddTHH:mm:ss.fffK'
        $rows = @(& $adbExe shell ps -A 2>$null | Select-String -Pattern 'com\.facebook\.orca')
        if ($rows.Count -eq 0) {
            Add-Content -Path $outputPath -Value ($now + [char]9 + '<no Messenger process>')
        } else {
            foreach ($row in $rows) {
                Add-Content -Path $outputPath -Value ($now + [char]9 + $row.Line)
            }
        }
        Start-Sleep -Milliseconds 800
    }
}

Write-Host ''
Write-Host 'A fresh diagnostic session is ready. Reopen the patched Messenger now.' -ForegroundColor Green
Write-Host 'Perform this exact sequence:' -ForegroundColor Yellow
Write-Host '1. Open the patched Messenger and confirm Keep unsent messages is enabled.'
Write-Host '2. Use a ONE-TO-ONE conversation that Messenger explicitly marks end-to-end encrypted; avoid groups for this test.'
Write-Host '3. Wait until that conversation finishes loading.'
Write-Host '4. Receive one NEW text message.'
Write-Host '5. Receive two NEW images; open each and wait until fully loaded.'
Write-Host '6. From the sender test account, unsend the text and both images.'
Write-Host '7. Open Unsent History > Diagnostics and tap Export debug report.'
Write-Host '8. Save it as HushMessenger-Unsend-Diagnostics.json in Downloads.'
Write-Host ''
Read-Host 'After exporting the JSON, press Enter here'

@(& $adbPath shell ps -A | Select-String -Pattern 'com\.facebook\.orca') |
    Out-File -FilePath $afterFile -Encoding UTF8

if ($logcatProcess -and -not $logcatProcess.HasExited) {
    Stop-Process -Id $logcatProcess.Id -Force
}
New-Item -ItemType File -Path $stopFile -Force | Out-Null
Wait-Job $monitor -Timeout 5 | Out-Null
Stop-Job $monitor -ErrorAction SilentlyContinue
Receive-Job $monitor -ErrorAction SilentlyContinue | Out-Null
Remove-Job $monitor -Force -ErrorAction SilentlyContinue
Remove-Item $stopFile -Force -ErrorAction SilentlyContinue

$remoteJson = '/sdcard/Download/HushMessenger-Unsend-Diagnostics.json'
$localJson = Join-Path $dir 'HushMessenger-Unsend-Diagnostics.json'
$pullOutput = & $adbPath pull $remoteJson $localJson 2>&1
$pullExitCode = $LASTEXITCODE
$pullOutput | Out-File -FilePath (Join-Path $dir 'json_pull.txt') -Encoding UTF8
if ($pullExitCode -ne 0 -or -not (Test-Path -LiteralPath $localJson)) {
    Write-Host 'Could not pull the JSON. Check that it was exported to Downloads.' -ForegroundColor Red
    throw "ADB pull failed with exit code $pullExitCode"
}

@(
    "started=$stamp"
    "finished=$(Get-Date -Format 'yyyyMMdd_HHmmss')"
    "package=$package"
) | Out-File -FilePath (Join-Path $dir 'session_times.txt') -Encoding UTF8

$archive = "$dir.zip"
Compress-Archive -Path (Join-Path $dir '*') -DestinationPath $archive -Force
Write-Host ''
Write-Host "Diagnostics collected: $archive" -ForegroundColor Green
Write-Host 'Review the JSON and logs before sharing. No message text, IDs, attachment URLs or media bytes are collected.'
