[CmdletBinding()]
param([switch]$RestoreDefault,[string]$AdbPath,[string]$OverlayApkPath)
$ErrorActionPreference='Stop'
if (-not $AdbPath) { throw 'Supply -AdbPath with the verified emulator adb executable.' }
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "ADB executable not found: $AdbPath" }
$taskAdb=(Get-Item -LiteralPath $AdbPath).FullName

function Invoke-LocalEmulator {
    $taskOutput=& $taskAdb -s emulator-5580 @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($taskOutput | Out-String) }
    $taskOutput
}
function Assert-LocalEmulator {
    $taskValue=Invoke-LocalEmulator shell getprop ro.kernel.qemu
    if ("$taskValue".Trim() -ne '1') { throw 'Only the local emulator is authorized.' }
}
function Assert-DriverUser {
    $taskUser=Invoke-LocalEmulator shell am get-current-user
    if ("$taskUser".Trim() -ne '10') { throw 'Only Android user 10 (Driver) is authorized.' }
}
function Wait-LocalBoot {
    Invoke-LocalEmulator wait-for-device | Out-Null
    $taskDeadline=[DateTime]::UtcNow.AddMinutes(4)
    do {
        $taskReady=Invoke-LocalEmulator shell getprop sys.boot_completed
        if ("$taskReady".Trim() -eq '1') { Assert-LocalEmulator; return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $taskDeadline)
    throw 'Emulator boot timed out.'
}
function Root-LocalEmulator {
    Assert-LocalEmulator
    if ((Invoke-LocalEmulator shell id -u).Trim() -ne '0') {
        Invoke-LocalEmulator root | Out-Null
        Invoke-LocalEmulator wait-for-device | Out-Null
    }
    Assert-LocalEmulator
    if ((Invoke-LocalEmulator shell id -u).Trim() -ne '0') { throw 'adbd did not become root.' }
    Invoke-LocalEmulator wait-for-device | Out-Null
    Assert-LocalEmulator
}
Assert-LocalEmulator
Assert-LocalEmulator
Assert-DriverUser
$taskOriginalUid = (Invoke-LocalEmulator shell id -u).Trim()
if ($taskOriginalUid -notin @('0', '2000')) { throw "Unexpected adbd UID: $taskOriginalUid" }
try {
if ($RestoreDefault) {
    Invoke-LocalEmulator shell cmd overlay disable --user 10 com.eyyit.emulator.mediacontrols | Out-Null
} else {
    $taskInstalled=Invoke-LocalEmulator shell pm path com.eyyit.emulator.mediacontrols
    $taskOverlayApk=$null
    if ($OverlayApkPath) {
        if (-not (Test-Path -LiteralPath $OverlayApkPath -PathType Leaf)) { throw "Overlay APK not found: $OverlayApkPath" }
        $taskOverlayApk=(Get-Item -LiteralPath $OverlayApkPath).FullName
    }
    $taskOverlayHash=if ($taskOverlayApk) { (Get-FileHash -LiteralPath $taskOverlayApk -Algorithm SHA256).Hash } else { $null }
    $taskInstalledOverlayHash=''
    $taskNeedsInstall="$taskInstalled" -notlike '*package:/system/*'
    if ($taskNeedsInstall -and -not $taskOverlayApk) { throw 'Supply -OverlayApkPath when the wide-controls overlay must be installed.' }
    if (-not $taskNeedsInstall -and $taskOverlayApk) {
        $taskRemotePath=("$taskInstalled".Trim() -replace '^package:','')
        $taskRemoteHash=& $taskAdb -s emulator-5580 shell sha256sum $taskRemotePath 2>$null
        if ($LASTEXITCODE -eq 0) { $taskInstalledOverlayHash=(("$taskRemoteHash" -split '\s+')[0]).ToUpperInvariant() }
    }
    if ($taskNeedsInstall -or ($taskOverlayApk -and $taskInstalledOverlayHash -ne $taskOverlayHash)) {
        Root-LocalEmulator
        $taskRemount=Invoke-LocalEmulator remount
        if (("$taskRemount" -match 'reboot.*(device|settings)|Now reboot')) {
            Invoke-LocalEmulator reboot | Out-Null
            Wait-LocalBoot
            Root-LocalEmulator
            $taskRemount=Invoke-LocalEmulator remount
        }
        if ("$taskRemount" -notmatch 'remount succeeded') { throw 'Start the emulator with -writable-system before applying this local host layout.' }
        Invoke-LocalEmulator shell mkdir -p /system/app/EyyitWideControls | Out-Null
        Invoke-LocalEmulator push $taskOverlayApk /system/app/EyyitWideControls/WideControls.apk | Out-Null
        Invoke-LocalEmulator shell chmod 644 /system/app/EyyitWideControls/WideControls.apk | Out-Null
        Invoke-LocalEmulator shell restorecon -R /system/app/EyyitWideControls | Out-Null
        Invoke-LocalEmulator reboot | Out-Null
        Wait-LocalBoot
    }
    Invoke-LocalEmulator shell pm install-existing --user 10 com.eyyit.emulator.mediacontrols | Out-Null
    Invoke-LocalEmulator shell cmd overlay enable --user 10 com.eyyit.emulator.mediacontrols | Out-Null
    $taskStyle=Invoke-LocalEmulator shell cmd overlay lookup --user 10 com.android.car.media com.android.car.media:style/MinimizedControlBar
    $taskDump=Invoke-LocalEmulator shell cmd overlay dump com.eyyit.emulator.mediacontrols
    if ("$taskDump" -notmatch 'STATE_ENABLED') { throw 'The local host layout did not enable.' }
    $taskSwitch=Invoke-LocalEmulator shell cmd overlay lookup --user 10 com.android.car.media com.android.car.media:bool/switch_to_playback_view_when_playable_item_is_clicked
    if ("$taskSwitch".Trim() -ne 'false') { & (Join-Path $PSScriptRoot 'Set-AAOS-pinned-player.ps1') -AdbPath $AdbPath | Out-Null }


















    Invoke-LocalEmulator wait-for-device | Out-Null
 }
} finally {
    try {
        Invoke-LocalEmulator wait-for-device | Out-Null
        Assert-LocalEmulator
        $taskCurrentUid = (Invoke-LocalEmulator shell id -u).Trim()
        if ($taskCurrentUid -ne $taskOriginalUid) {
            if ($taskOriginalUid -eq '0') { Invoke-LocalEmulator root | Out-Null }
            else { Invoke-LocalEmulator unroot | Out-Null }
            Invoke-LocalEmulator wait-for-device | Out-Null
        }
    } finally {
        Assert-LocalEmulator
        $taskRestoredUid = (Invoke-LocalEmulator shell id -u).Trim()
        if ($taskRestoredUid -ne $taskOriginalUid) {
            throw "adbd UID restoration failed: expected $taskOriginalUid, got $taskRestoredUid"
        }
    }
}
Invoke-LocalEmulator shell am force-stop --user 10 com.android.car.media | Out-Null
