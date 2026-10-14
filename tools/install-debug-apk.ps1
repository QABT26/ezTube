$ErrorActionPreference = "Stop"

$Package = "com.qabt.eztube"
$Adb = "C:\Users\hoang\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$Apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
$Apk = (Resolve-Path $Apk).Path

if (-not (Test-Path $Adb)) {
    throw "ADB not found: $Adb"
}
if (-not (Test-Path $Apk)) {
    throw "APK not found: $Apk"
}

$currentUser = (& $Adb shell am get-current-user).Trim()
if (-not ($currentUser -match '^\d+$')) {
    throw "Unable to determine current Android user: $currentUser"
}

Write-Host "ADB=$Adb"
Write-Host "APK=$Apk"
Write-Host "PACKAGE=$Package"
Write-Host "TARGET_USER=$currentUser"

$remoteApk = "/data/local/tmp/eztube-debug.apk"

Write-Host "[INFO] Pushing APK..."
& $Adb push $Apk $remoteApk
if ($LASTEXITCODE -ne 0) {
    throw "APK push failed"
}

try {
    Write-Host "[INFO] Installing only for Android user $currentUser..."
    $installOutput = & $Adb shell pm install -r --user $currentUser $remoteApk 2>&1
    $installExit = $LASTEXITCODE
    $installText = $installOutput -join [Environment]::NewLine
    $installOutput | ForEach-Object { Write-Host $_ }

    if ($installExit -ne 0 -or $installText -notmatch '(?m)^Success\s*$') {
        throw "APK install failed for user $currentUser"
    }
}
finally {
    & $Adb shell rm -f $remoteApk | Out-Null
}

# Remove only the ezTube package from Samsung Dual Messenger profiles.
$userList = & $Adb shell pm list users
$dualUserIds = @()
foreach ($line in $userList) {
    if ($line -match 'UserInfo\{(\d+):DUAL_APP:') {
        $dualUserIds += $Matches[1]
    }
}

foreach ($dualUserId in $dualUserIds) {
    $installed = & $Adb shell pm list packages --user $dualUserId $Package
    if (($installed -join [Environment]::NewLine) -match [regex]::Escape("package:$Package")) {
        Write-Host "[INFO] Removing Samsung DUAL_APP ezTube copy from user $dualUserId..."
        & $Adb shell pm uninstall --user $dualUserId $Package
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to remove ezTube from DUAL_APP user $dualUserId"
        }
    }
}

Write-Host "[PASS] ezTube installed only for Android user $currentUser."
