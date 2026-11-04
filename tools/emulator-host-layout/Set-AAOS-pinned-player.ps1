[CmdletBinding()]
param([switch]$RestoreDefault,[string]$AdbPath)
$ErrorActionPreference='Stop'
if (-not $AdbPath) { throw 'Supply -AdbPath with the verified emulator adb executable.' }
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw "ADB executable not found: $AdbPath" }
$taskAdb=(Get-Item -LiteralPath $AdbPath).FullName

function Invoke-Emulator {
    & $taskAdb -s emulator-5580 @args
    if ($LASTEXITCODE -ne 0) { throw "Emulator command failed: $args" }
}
$taskVirtual=Invoke-Emulator shell getprop ro.kernel.qemu
if ("$taskVirtual".Trim() -ne '1') { throw 'This command requires the verified local emulator.' }
$taskUser=Invoke-Emulator shell am get-current-user
if ("$taskUser".Trim() -ne '10') { throw 'Only Android user 10 (Driver) is authorized.' }
$taskOriginalUid = (Invoke-Emulator shell id -u).Trim()
if ($taskOriginalUid -notin @('0', '2000')) { throw "Unexpected adbd UID: $taskOriginalUid" }
if ($RestoreDefault) {
    Invoke-Emulator shell cmd overlay disable --user 10 com.android.shell:EyyitKeepBrowse
} else {
    try {
        if ($taskOriginalUid -ne '0') {
            Invoke-Emulator root
            Invoke-Emulator wait-for-device
            $taskVirtual = Invoke-Emulator shell getprop ro.kernel.qemu
            if ("$taskVirtual".Trim() -ne '1') { throw 'Emulator identity changed.' }
            if ((Invoke-Emulator shell id -u).Trim() -ne '0') { throw 'adbd did not become root.' }
        }
    Invoke-Emulator wait-for-device
    $taskVirtual=Invoke-Emulator shell getprop ro.kernel.qemu
    if ("$taskVirtual".Trim() -ne '1') { throw 'Emulator identity changed.' }
    Invoke-Emulator shell cmd overlay fabricate --user 10 --target-name CarMediaApp --target com.android.car.media --name EyyitKeepBrowse com.android.car.media:bool/switch_to_playback_view_when_playable_item_is_clicked 0x12 0
    Invoke-Emulator shell cmd overlay enable --user 10 com.android.shell:EyyitKeepBrowse
    $taskValue=Invoke-Emulator shell cmd overlay lookup --user 10 com.android.car.media com.android.car.media:bool/switch_to_playback_view_when_playable_item_is_clicked
    if ("$taskValue".Trim() -ne 'false') { throw 'Native media overlay did not apply.' }
    } finally {
        try {
            Invoke-Emulator wait-for-device
            $taskVirtual = Invoke-Emulator shell getprop ro.kernel.qemu
            if ("$taskVirtual".Trim() -ne '1') { throw 'Emulator identity changed during adbd restoration.' }
            $taskCurrentUid = (Invoke-Emulator shell id -u).Trim()
            if ($taskCurrentUid -ne $taskOriginalUid) {
                if ($taskOriginalUid -eq '0') { Invoke-Emulator root } else { Invoke-Emulator unroot }
                Invoke-Emulator wait-for-device
            }
        } finally {
            $taskVirtual = Invoke-Emulator shell getprop ro.kernel.qemu
            if ("$taskVirtual".Trim() -ne '1') { throw 'Emulator identity changed during adbd verification.' }
            $taskRestoredUid = (Invoke-Emulator shell id -u).Trim()
            if ($taskRestoredUid -ne $taskOriginalUid) {
                throw "adbd UID restoration failed: expected $taskOriginalUid, got $taskRestoredUid"
            }
        }
    }
    Invoke-Emulator wait-for-device
}
Invoke-Emulator shell am force-stop --user 10 com.android.car.media
Invoke-Emulator shell am start --user 10 -W -a android.car.intent.action.MEDIA_TEMPLATE --es android.car.intent.extra.MEDIA_COMPONENT com.impostorlabs.streamingradio.debug/net.mspanc.twinsenradio.playback.RadioService
