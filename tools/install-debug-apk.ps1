$ErrorActionPreference = "Stop"

# PowerShell 7.3+ can promote native stderr (including harmless ADB daemon startup
# messages) into NativeCommandError when ErrorActionPreference is Stop.
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

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

function Wait-AdbDevice {
    param(
        [int]$TimeoutSeconds = 20
    )

    # Starting the daemon may print informational text to stderr. That is not a failure.
    & $Adb start-server 2>&1 | ForEach-Object {
        if ($_ -and $_ -notmatch '^\* daemon') { Write-Host $_ }
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $stateOutput = & $Adb get-state 2>&1
        $stateExit = $LASTEXITCODE
        $stateText = ($stateOutput -join [Environment]::NewLine).Trim()

        if ($stateExit -eq 0 -and $stateText -match '(?m)^device\s*$') {
            return
        }

        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $devices = & $Adb devices 2>&1
    $deviceText = ($devices -join [Environment]::NewLine).Trim()
    throw "No ready Android device found after $TimeoutSeconds seconds. Check USB cable, unlock the phone, and confirm USB debugging. ADB: $deviceText"
}

function Get-CurrentAndroidUser {
    Wait-AdbDevice

    $output = & $Adb shell am get-current-user 2>&1
    $exit = $LASTEXITCODE
    $text = ($output -join [Environment]::NewLine).Trim()

    if ($exit -ne 0 -or -not ($text -match '^\d+$')) {
        throw "Unable to determine current Android user. ADB output: $text"
    }

    return $text
}

$currentUser = Get-CurrentAndroidUser

Write-Host "ADB=$Adb"
Write-Host "APK=$Apk"
Write-Host "PACKAGE=$Package"
Write-Host "TARGET_USER=$currentUser"

$remoteApk = "/data/local/tmp/eztube-debug.apk"

Write-Host "[INFO] Pushing APK..."
$pushSucceeded = $false
for ($attempt = 1; $attempt -le 2; $attempt++) {
    Wait-AdbDevice
    $pushOutput = & $Adb push $Apk $remoteApk 2>&1
    $pushExit = $LASTEXITCODE
    $pushOutput | ForEach-Object { Write-Host $_ }

    if ($pushExit -eq 0) {
        $pushSucceeded = $true
        break
    }

    if ($attempt -lt 2) {
        Write-Host "[WARN] ADB push failed. Reconnecting and retrying once..."
        & $Adb reconnect 2>&1 | Out-Null
        Start-Sleep -Milliseconds 700
    }
}
if (-not $pushSucceeded) {
    throw "APK push failed"
}

try {
    Write-Host "[INFO] Installing only for Android user $currentUser..."

    $installSucceeded = $false
    $lastInstallOutput = @()

    for ($attempt = 1; $attempt -le 2; $attempt++) {
        if ($attempt -gt 1) {
            Write-Host "[WARN] ADB install did not complete. Reconnecting and retrying once..."
            & $Adb reconnect 2>&1 | Out-Null
            Start-Sleep -Milliseconds 700
            Wait-AdbDevice
        }

        $installOutput = & $Adb shell pm install -r --user $currentUser $remoteApk 2>&1
        $installExit = $LASTEXITCODE
        $installText = $installOutput -join [Environment]::NewLine
        $lastInstallOutput = $installOutput

        $installOutput | ForEach-Object { Write-Host $_ }

        if ($installExit -eq 0 -and $installText -match '(?m)^Success\s*$') {
            $installSucceeded = $true
            break
        }

        $transportRestarted =
            $installText -match 'daemon not running|daemon started successfully|device offline|device not found|no devices/emulators found|closed|transport'

        if (-not $transportRestarted -or $attempt -ge 2) {
            break
        }
    }

    if (-not $installSucceeded) {
        $detail = ($lastInstallOutput -join [Environment]::NewLine).Trim()
        if ([string]::IsNullOrWhiteSpace($detail)) {
            $detail = "No package-manager output was returned."
        }
        throw "APK install failed for user $currentUser. Detail: $detail"
    }
}
finally {
    # Cleanup is best-effort. Never hang or fail a successful install merely because
    # the phone was unplugged immediately afterward.
    $stateOutput = & $Adb get-state 2>&1
    if ($LASTEXITCODE -eq 0 -and (($stateOutput -join "").Trim() -eq "device")) {
        & $Adb shell rm -f $remoteApk 2>&1 | Out-Null
    }
}

Wait-AdbDevice

# Remove only the ezTube package from Samsung Dual Messenger profiles.
$userList = & $Adb shell pm list users 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Unable to list Android users: " + ($userList -join [Environment]::NewLine)
}

$dualUserIds = @()
foreach ($line in $userList) {
    if ($line -match 'UserInfo\{(\d+):DUAL_APP:') {
        $dualUserIds += $Matches[1]
    }
}

foreach ($dualUserId in $dualUserIds) {
    $installed = & $Adb shell pm list packages --user $dualUserId $Package 2>&1
    if (($installed -join [Environment]::NewLine) -match [regex]::Escape("package:$Package")) {
        Write-Host "[INFO] Removing Samsung DUAL_APP ezTube copy from user $dualUserId..."
        $uninstallOutput = & $Adb shell pm uninstall --user $dualUserId $Package 2>&1
        $uninstallOutput | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to remove ezTube from DUAL_APP user $dualUserId"
        }
    }
}

Write-Host "[PASS] ezTube installed only for Android user $currentUser."
