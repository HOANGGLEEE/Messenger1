# Messenger 581 / HushMessenger Unsend diagnostics collector.
# Run in Windows PowerShell with Android Platform Tools (adb) installed.
# The JSON report and filtered logcat contain only allow-listed diagnostics tokens.
$ErrorActionPreference = 'Stop'
$package = 'com.facebook.orca'
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Host 'ADB not found. Install Android Platform Tools and add adb to PATH.' -ForegroundColor Red
    exit 1
}
$connected = @(& adb devices | Where-Object { $_ -match '\sdevice\s*$' })
if ($connected.Count -ne 1) {
    Write-Host "Connect exactly one authorized Android device. Found $($connected.Count)." -ForegroundColor Red
    & adb devices
    exit 2
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$dir = Join-Path (Get-Location) "Messenger581_Unsend_Debug_$stamp"
New-Item -ItemType Directory -Path $dir -Force | Out-Null

@(& adb shell dumpsys package $package | Select-String -Pattern 'versionName=|versionCode=|targetSdk=|hush\.feature\.keep_unsent|hush\.unsent\.') |
    Out-File -FilePath (Join-Path $dir 'package_and_hook_metadata.txt') -Encoding UTF8
@(& adb shell ps -A | Select-String -Pattern 'com\.facebook\.orca') |
    Out-File -FilePath (Join-Path $dir 'processes_before.txt') -Encoding UTF8

Write-Host 'Messenger package information:' -ForegroundColor Cyan
Get-Content (Join-Path $dir 'package_and_hook_metadata.txt')
Write-Host ''
Write-Host 'On the phone, perform this exact test sequence:' -ForegroundColor Yellow
Write-Host '1. Open the patched Messenger and enable Keep unsent messages.'
Write-Host '2. Open one test conversation and wait until it finishes loading.'
Write-Host '3. Receive one new text message.'
Write-Host '4. Receive two new images; open each image and wait until it is fully loaded.'
Write-Host '5. From the sender test account, unsend the text and both images.'
Write-Host '6. Open Unsent History > Diagnostics, then tap Export debug report.'
Write-Host '7. Save the JSON as HushMessenger-Unsend-Diagnostics.json in Downloads.'
Write-Host ''
Write-Host 'Clearing old logcat entries. This does not clear Messenger data.'
& adb logcat -c
Read-Host 'After completing the test sequence and exporting JSON, press Enter here'

@(& adb shell ps -A | Select-String -Pattern 'com\.facebook\.orca') |
    Out-File -FilePath (Join-Path $dir 'processes_after.txt') -Encoding UTF8
& adb logcat -d -v threadtime -s HushMessengerUnsend:V HushMessenger:V AndroidRuntime:E |
    Out-File -FilePath (Join-Path $dir 'filtered_logcat.txt') -Encoding UTF8

$remoteJson = '/sdcard/Download/HushMessenger-Unsend-Diagnostics.json'
& adb pull $remoteJson (Join-Path $dir 'HushMessenger-Unsend-Diagnostics.json') 2>&1 |
    Out-File -FilePath (Join-Path $dir 'json_pull.txt') -Encoding UTF8

$archive = "$dir.zip"
Compress-Archive -Path (Join-Path $dir '*') -DestinationPath $archive -Force
Write-Host ''
Write-Host "Diagnostics collected: $archive" -ForegroundColor Green
Write-Host 'Review the JSON and logcat before sharing. Do not share message screenshots or chat contents.'
