# Modified for Streaming Radio by Impostor Labs.
# Build and install the selected phone or automotive variant.
#   .\tools\install.ps1                 -> phone debug
#   .\tools\install.ps1 -Automotive   -> automotive debug
#   .\tools\install.ps1 -Release      -> signed matching release variant
param(
    [switch]$Release,
    [switch]$Automotive,
    [ValidatePattern('^emulator-\d+$')]
    [string]$Serial = 'emulator-5580'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = 'C:\Android\Sdk'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'

$variant = if ($Release) { 'Release' } else { 'Debug' }
$apkDir = if ($Release) { 'release' } else { 'debug' }
$platform = if ($Automotive) { 'automotive' } else { 'phone' }
$platformTask = if ($Automotive) { 'Automotive' } else { 'Phone' }
$testTask = ':app:test' + $platformTask + $variant + 'UnitTest'
$assembleTask = ':app:assemble' + $platformTask + $variant
$apkPath = Join-Path $root "app\build\outputs\apk\$platform\$apkDir\app-$platform-$apkDir.apk"
if ($Automotive) {
    $package = if ($Release) { 'com.impostorlabs.streamingradio' } else { 'com.impostorlabs.streamingradio.debug' }
    $component = "$package/net.mspanc.twinsenradio.automotive.ImpostorPlayerActivity"
} else {
    $package = 'net.mspanc.twinsenradio'
    $component = "$package/net.mspanc.twinsenradio.ui.MainActivity"
}

if ($Release -and (
    [string]::IsNullOrWhiteSpace($env:STREAMING_RADIO_KEYSTORE) -or
    [string]::IsNullOrWhiteSpace($env:STREAMING_RADIO_STORE_PASSWORD) -or
    [string]::IsNullOrWhiteSpace($env:STREAMING_RADIO_KEY_ALIAS) -or
    [string]::IsNullOrWhiteSpace($env:STREAMING_RADIO_KEY_PASSWORD)
)) {
    throw 'Release installation requires all four STREAMING_RADIO signing variables.'
}
Push-Location $root
try {
    # Testy przed zlozeniem APK. Kosztuja tyle co nic, gdy nic sie nie zmienilo,
    # a pilnuja regul tekstowych, ktorych na telefonie nie da sie sprawdzic
    # inaczej niz czekajac, az radio zagra wlasciwy utwor.
    & "$root\gradlew.bat" $testTask
    if ($LASTEXITCODE -ne 0) { throw "Unit tests failed: $testTask" }


    & "$root\gradlew.bat" $assembleTask
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $assembleTask" }

} finally {
    Pop-Location
}

$apk = Get-Item -LiteralPath $apkPath -ErrorAction SilentlyContinue
if (-not $apk) { throw "Expected $platform APK was not produced: $apkPath" }
Write-Host "Package: $package"

Write-Host "APK: $($apk.FullName)  ($([Math]::Round($apk.Length/1KB)) KB)"

$devices = @(& $adb devices)
$target = @($devices | Where-Object { $_ -ceq ($Serial + "`tdevice") })
if ($target.Count -ne 1) {
    Write-Host "Target emulator not connected: $Serial" -ForegroundColor Yellow
    & $adb devices -l
    exit 1
}

$qemu = & $adb -s $Serial shell getprop ro.kernel.qemu
if ($LASTEXITCODE -ne 0 -or ($qemu -join '').Trim() -ne '1') {
    throw "Refusing install on unverified emulator: $Serial"
}

$currentUser = & $adb -s $Serial shell am get-current-user
if ($LASTEXITCODE -ne 0 -or ($currentUser -join '').Trim() -ne '10') {
    throw "Refusing install unless Driver user 10 is current on $Serial"
}

& $adb -s $Serial install --user 10 -r -d $apk.FullName
if ($LASTEXITCODE -ne 0) { throw "Install failed for $package; launch skipped." }
& $adb -s $Serial shell am start --user 10 -n $component
if ($LASTEXITCODE -ne 0) { throw "Launch failed for $component." }
