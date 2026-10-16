$ErrorActionPreference = "Stop"

$Repo = Split-Path -Parent $PSScriptRoot
Push-Location $Repo
try {
    Write-Host "===== EZTUBE DEBUG BUILD + SAFE SAMSUNG INSTALL ====="

    & .\gradlew.bat testDebugUnitTest assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed"
    }

    & (Join-Path $PSScriptRoot "install-debug-apk.ps1")
}
finally {
    Pop-Location
}
