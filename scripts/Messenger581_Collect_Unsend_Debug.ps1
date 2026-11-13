# Messenger 581 - thu thap log Debug Unsend HushMessenger
# Chi thu thap version/package/process va log tag HushMessenger; khong doc database hay noi dung chat.
$ErrorActionPreference = 'Stop'

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Host 'CHUA CO ADB: Cai Android Platform Tools va them adb vao PATH.' -ForegroundColor Red
    exit 1
}

$devices = @(& adb devices | Where-Object { $_ -match '\sdevice\s*$' })
if ($devices.Count -ne 1) {
    Write-Host "Can ket noi dung 1 dien thoai qua USB. Hien co $($devices.Count) thiet bi." -ForegroundColor Red
    & adb devices
    exit 2
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$outDir = Join-Path (Get-Location) "HushMessenger_Unsend_Debug_$stamp"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

@(
    "CollectedAt=$(Get-Date -Format o)"
    "Model=$(& adb shell getprop ro.product.model)"
    "Manufacturer=$(& adb shell getprop ro.product.manufacturer)"
    "AndroidRelease=$(& adb shell getprop ro.build.version.release)"
    "AndroidSDK=$(& adb shell getprop ro.build.version.sdk)"
    "ABIs=$(& adb shell getprop ro.product.cpu.abilist)"
) | Out-File (Join-Path $outDir 'device.txt') -Encoding UTF8

& adb shell dumpsys package com.facebook.orca |
    Select-String -Pattern 'versionName=|versionCode=|userId=|targetSdk=' |
    Out-File (Join-Path $outDir 'messenger_package.txt') -Encoding UTF8

& adb shell ps -A |
    Select-String -Pattern 'com\.facebook\.orca' |
    Out-File (Join-Path $outDir 'process_before.txt') -Encoding UTF8

Write-Host ''
Write-Host 'Da ghi thong tin thiet bi va Messenger.' -ForegroundColor Cyan
Write-Host 'Logcat se duoc lam moi; thao tac nay KHONG xoa du lieu hay lich su Messenger.'
& adb logcat -c

Write-Host ''
Write-Host 'THUC HIEN TREN DIEN THOAI:' -ForegroundColor Yellow
Write-Host '1. Mo Messenger 581 va bat Keep unsent messages.'
Write-Host '2. Mo mot hoi thoai thu nghiem, de tin nhan van ban moi hien tren man hinh.'
Write-Host '3. Nhan mot anh moi va mo anh de Messenger tai du lieu anh tren may.'
Write-Host '4. Tu tai khoan gui, thu hoi tin van ban va anh vua gui.'
Write-Host '5. Mo Lich su thu hoi, chup lai cac chi so; bam Xuat bao cao Debug de luu JSON.'
Write-Host '6. Quay lai may tinh va bam Enter de ket thuc thu thap log.'
Read-Host 'Bam Enter sau khi da thu xong'

& adb shell ps -A |
    Select-String -Pattern 'com\.facebook\.orca' |
    Out-File (Join-Path $outDir 'process_after.txt') -Encoding UTF8

& adb logcat -d -v threadtime -s HushMessengerUnsend:V HushMessenger:V AndroidRuntime:E |
    Out-File (Join-Path $outDir 'filtered_logcat.txt') -Encoding UTF8

Write-Host ''
Write-Host "Da luu log tai: $outDir" -ForegroundColor Green
Write-Host 'Gui filtered_logcat.txt va file HushMessenger-Unsend-Diagnostics.json da xuat tu ung dung.'
Write-Host 'Khong gui anh man hinh co noi dung tro chuyen; bao cao JSON da duoc gioi han de tranh luu noi dung rieng tu.'
