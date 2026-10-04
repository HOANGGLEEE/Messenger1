$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$secretFile = Join-Path $root ".secrets\github.env"

# ============================================================
# 1. DOC GITHUB TOKEN
# ============================================================

if (-not (Test-Path $secretFile)) {
    Write-Host "Chua co file .secrets\github.env"
    Write-Host ""
    Write-Host "Hay tao file:"
    Write-Host ".secrets\github.env"
    Write-Host ""
    Write-Host "Noi dung:"
    Write-Host "GITHUB_ACTOR=HOANGGLEEE"
    Write-Host "GITHUB_TOKEN=TOKEN_CUA_BAN"
    exit 1
}

$config = @{}

Get-Content $secretFile | ForEach-Object {
    $line = $_.Trim()

    if (
        $line.Length -gt 0 -and
        -not $line.StartsWith("#")
    ) {
        $parts = $line.Split("=", 2)

        if ($parts.Count -eq 2) {
            $config[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
}

$actor = $config["GITHUB_ACTOR"]
$token = $config["GITHUB_TOKEN"]

if (-not $actor) {
    $actor = "HOANGGLEEE"
}

if (
    -not $token -or
    $token -eq "PASTE_GITHUB_PAT_HERE" -or
    $token -eq "TOKEN_CUA_BAN"
) {
    Write-Host "GITHUB_TOKEN chua duoc dien trong:"
    Write-Host ".secrets\github.env"
    exit 1
}

$env:GITHUB_ACTOR = $actor
$env:GITHUB_TOKEN = $token

try {

    # ============================================================
    # 2. TIM JDK 21
    # ============================================================

    $jdk21 = Get-ChildItem `
        "C:\Program Files\Eclipse Adoptium" `
        -Directory `
        -Filter "jdk-21*" `
        -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1

    if (-not $jdk21) {
        Write-Host ""
        Write-Host "Khong tim thay JDK 21."
        Write-Host ""
        Write-Host "Hay cai bang lenh:"
        Write-Host ""
        Write-Host "winget install --id EclipseAdoptium.Temurin.21.JDK -e"
        exit 1
    }

    $env:JAVA_HOME = $jdk21.FullName
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"

    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"

    if (-not (Test-Path $javaExe)) {
        throw "Khong tim thay java.exe tai $javaExe"
    }

    # Khong goi java -version truc tiep trong PowerShell
    # vi Windows PowerShell co the coi stderr cua Java la loi.
    $javaOutput = & cmd.exe /d /c "`"$javaExe`" -version 2>&1"

    if (-not $javaOutput) {
        throw "Khong doc duoc phien ban Java."
    }

    $javaVersion = ($javaOutput | Select-Object -First 1).ToString().Trim()

    if ($javaVersion -notmatch '"21\.') {
        Write-Host ""
        Write-Host "Sai phien ban Java:"
        Write-Host $javaVersion
        Write-Host ""
        Write-Host "Project nay can JDK 21."
        exit 1
    }

    Write-Host ""
    Write-Host "========================================"
    Write-Host "MOI TRUONG BUILD"
    Write-Host "========================================"
    Write-Host "GitHub Actor : $env:GITHUB_ACTOR"
    Write-Host "GitHub Token : loaded"
    Write-Host "JAVA_HOME    : $env:JAVA_HOME"
    Write-Host "Java         : $javaVersion"
    Write-Host "========================================"

    # ============================================================
    # 3. KIEM TRA GITHUB PACKAGES
    # ============================================================

    Write-Host ""
    Write-Host "Dang kiem tra GitHub Packages..."

    $url = "https://maven.pkg.github.com/MorpheApp/registry/app/morphe/patches/app.morphe.patches.gradle.plugin/1.3.4/app.morphe.patches.gradle.plugin-1.3.4.pom"

    $pair = "$($env:GITHUB_ACTOR):$($env:GITHUB_TOKEN)"

    $basic = [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes($pair)
    )

    try {
        $resp = Invoke-WebRequest `
            -Uri $url `
            -Method Head `
            -Headers @{
                Authorization = "Basic $basic"
            } `
            -UseBasicParsing

        if ($resp.StatusCode -ne 200) {
            throw "GitHub Packages HTTP $($resp.StatusCode)"
        }

        Write-Host "GitHub Packages: OK (HTTP 200)"
    }
    catch {
        $status = $null

        try {
            $status = [int]$_.Exception.Response.StatusCode
        }
        catch {
        }

        Write-Host ""

        if ($status -eq 401) {
            Write-Host "Token GitHub khong hop le. HTTP 401."
        }
        elseif ($status -eq 403) {
            Write-Host "Token GitHub thieu quyen read:packages. HTTP 403."
        }
        elseif ($status) {
            Write-Host "GitHub Packages loi HTTP $status."
        }
        else {
            Write-Host "Khong ket noi duoc GitHub Packages."
            Write-Host $_.Exception.Message
        }

        exit 1
    }

    # ============================================================
    # 4. HAM CHAY GRADLE
    # ============================================================

    function Run-GradleStep {
        param(
            [string]$Name,
            [string[]]$GradleArgs
        )

        Write-Host ""
        Write-Host "========================================"
        Write-Host $Name
        Write-Host "========================================"

        & "$root\gradlew.bat" @GradleArgs

        $code = $LASTEXITCODE

        if ($code -ne 0) {
            throw "$Name that bai. Exit code: $code"
        }

        Write-Host ""
        Write-Host "$Name : OK"
    }

    # ============================================================
    # 5. DUNG GRADLE DAEMON CU
    # ============================================================

    Write-Host ""
    Write-Host "Dang dung Gradle daemon cu..."

    & "$root\gradlew.bat" --stop

    # Không kiểm tra exit code ở --stop vì không có daemon
    # cũng không phải lỗi build.

    # ============================================================
    # 6. UNIT TEST MESSENGER
    # ============================================================

    Run-GradleStep `
        -Name "Messenger unit tests" `
        -GradleArgs @(
            ":extensions:messenger:testDebugUnitTest",
            "--no-daemon"
        )

    # ============================================================
    # 7. PATCH TEST
    # ============================================================

    Run-GradleStep `
        -Name "Patch tests" `
        -GradleArgs @(
            ":patches:test",
            "--no-daemon"
        )

    # ============================================================
    # 8. PATCH CHECK
    # ============================================================

    Run-GradleStep `
        -Name "Patch checks" `
        -GradleArgs @(
            ":patches:check",
            "--no-daemon"
        )

    # ============================================================
    # 9. BUILD MPP
    # ============================================================

    Run-GradleStep `
        -Name "Build MPP" `
        -GradleArgs @(
            ":patches:buildAndroid",
            "--no-daemon"
        )

    # ============================================================
    # 10. TIM FILE MPP
    # ============================================================

    $mpp = Get-ChildItem `
        "$root\patches\build\libs\*.mpp" `
        -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $mpp) {
        throw "Build thanh cong nhung khong tim thay file .mpp"
    }

    $sizeKB = [Math]::Round($mpp.Length / 1KB, 2)

    Write-Host ""
    Write-Host "========================================"
    Write-Host "BUILD THANH CONG"
    Write-Host "========================================"
    Write-Host ""
    Write-Host "MPP:"
    Write-Host $mpp.FullName
    Write-Host ""
    Write-Host "File:"
    Write-Host $mpp.Name
    Write-Host ""
    Write-Host "Size:"
    Write-Host "$sizeKB KB"
    Write-Host ""
    Write-Host "========================================"
}
catch {
    Write-Host ""
    Write-Host "========================================"
    Write-Host "BUILD THAT BAI"
    Write-Host "========================================"
    Write-Host $_.Exception.Message
    Write-Host "========================================"

    exit 1
}
finally {

    # Token chi ton tai trong process build
    Remove-Item Env:\GITHUB_TOKEN -ErrorAction SilentlyContinue

    $token = $null
    $basic = $null
    $pair = $null
}