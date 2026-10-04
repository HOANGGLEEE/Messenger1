$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$secretFile = Join-Path $root ".secrets\github.env"

if (-not (Test-Path $secretFile)) {
    Write-Host "Chua co file .secrets\github.env"
    Write-Host "Hay tao file do va dien GitHub PAT vao GITHUB_TOKEN=..."
    exit 1
}

$config = @{}
Get-Content $secretFile | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $parts = $line.Split("=", 2)
    if ($parts.Count -eq 2) {
        $config[$parts[0].Trim()] = $parts[1].Trim()
    }
}

$actor = $config["GITHUB_ACTOR"]
$token = $config["GITHUB_TOKEN"]

if (-not $actor) { $actor = "HOANGGLEEE" }

if (-not $token -or $token -eq "PASTE_GITHUB_PAT_HERE") {
    Write-Host "GITHUB_TOKEN chua duoc dien trong .secrets\github.env"
    exit 1
}

$env:GITHUB_ACTOR = $actor
$env:GITHUB_TOKEN = $token

try {
    # Tim JDK 21
    $jdk21 = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1

    if ($jdk21) {
        $env:JAVA_HOME = $jdk21.FullName
        $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    }

    $javaVersion = (& java -version 2>&1 | Select-Object -First 1)
    if ($javaVersion -notmatch '"21[.\s]') {
        Write-Host "Project can JDK 21."
        Write-Host "Cai bang lenh:"
        Write-Host "winget install --id EclipseAdoptium.Temurin.21.JDK -e"
        exit 1
    }

    Write-Host "GitHub Actor: $env:GITHUB_ACTOR"
    Write-Host "GitHub Token: loaded"
    Write-Host "Java: $javaVersion"

    # Kiem tra GitHub Packages
    $url = "https://maven.pkg.github.com/MorpheApp/registry/app/morphe/patches/app.morphe.patches.gradle.plugin/1.3.4/app.morphe.patches.gradle.plugin-1.3.4.pom"
    $pair = "$($env:GITHUB_ACTOR):$($env:GITHUB_TOKEN)"
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))

    try {
        $resp = Invoke-WebRequest -Uri $url -Method Head -Headers @{ Authorization = "Basic $basic" } -UseBasicParsing
        if ($resp.StatusCode -ne 200) {
            throw "GitHub Packages HTTP $($resp.StatusCode)"
        }
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -eq 401) {
            Write-Host "Token khong hop le (HTTP 401)."
        }
        elseif ($status -eq 403) {
            Write-Host "Token thieu quyen read:packages (HTTP 403)."
        }
        else {
            Write-Host "Khong truy cap duoc GitHub Packages. HTTP: $status"
        }
        exit 1
    }

    function Run-GradleStep {
        param([string[]]$Args, [string]$Name)
        Write-Host ""
        Write-Host "=== $Name ==="
        & "$root\gradlew.bat" @Args
        if ($LASTEXITCODE -ne 0) {
            throw "$Name failed with exit code $LASTEXITCODE"
        }
    }

    & "$root\gradlew.bat" --stop | Out-Null

    Run-GradleStep @(":extensions:messenger:testDebugUnitTest", "--no-daemon") "Messenger unit tests"
    Run-GradleStep @(":patches:test", "--no-daemon") "Patch tests"
    Run-GradleStep @(":patches:check", "--no-daemon") "Patch checks"
    Run-GradleStep @(":patches:buildAndroid", "--no-daemon") "Build MPP"

    $mpp = Get-ChildItem "$root\patches\build\libs\*.mpp" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $mpp) {
        throw "Build xong nhung khong tim thay file .mpp"
    }

    Write-Host ""
    Write-Host "========================================"
    Write-Host "BUILD THANH CONG"
    Write-Host "========================================"
    Write-Host "MPP:"
    Write-Host $mpp.FullName
    Write-Host ""
    Write-Host "Size:"
    Write-Host ("{0:N0} bytes" -f $mpp.Length)
    Write-Host "========================================"
}
finally {
    Remove-Item Env:\GITHUB_TOKEN -ErrorAction SilentlyContinue
    Remove-Variable token -ErrorAction SilentlyContinue
}
