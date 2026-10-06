$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$secretFile = Join-Path $root ".secrets\github.env"

if (-not (Test-Path $secretFile)) {
    throw "Khong tim thay .secrets\github.env"
}

$config = @{}
Get-Content $secretFile | ForEach-Object {
    $line = $_.Trim()
    if ($line.Length -gt 0 -and -not $line.StartsWith("#")) {
        $parts = $line.Split("=", 2)
        if ($parts.Count -eq 2) {
            $config[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
}

$env:GITHUB_ACTOR = $config["GITHUB_ACTOR"]
if (-not $env:GITHUB_ACTOR) { $env:GITHUB_ACTOR = "HOANGGLEEE" }
$env:GITHUB_TOKEN = $config["GITHUB_TOKEN"]

if (-not $env:GITHUB_TOKEN -or $env:GITHUB_TOKEN -eq "TOKEN_CUA_BAN") {
    throw "GITHUB_TOKEN chua duoc dien trong .secrets\github.env"
}

$jdk21 = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $jdk21) { throw "Khong tim thay JDK 21" }

$env:JAVA_HOME = $jdk21.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

try {
    $versionLine = Get-Content (Join-Path $root "gradle.properties") |
        Where-Object { $_ -match '^version=' } |
        Select-Object -First 1
    if (-not $versionLine) { throw "Khong tim thay version trong gradle.properties" }
    $version = $versionLine.Split("=", 2)[1].Trim()

    Write-Host ""
    Write-Host "========================================"
    Write-Host "VERIFY RELEASE v$version"
    Write-Host "========================================"

    & "$root\gradlew.bat" :patches:verifyReleaseMetadata --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "verifyReleaseMetadata that bai" }

    python "$root\scripts\check_release.py" --release-tag "v$version" --checksums "$root\SHA256SUMS.txt"
    if ($LASTEXITCODE -ne 0) { throw "check_release.py that bai" }

    Write-Host ""
    Write-Host "========================================"
    Write-Host "RELEASE VERIFY PASS"
    Write-Host "Version: v$version"
    Write-Host "========================================"
}
finally {
    Remove-Item Env:\GITHUB_TOKEN -ErrorAction SilentlyContinue
}
