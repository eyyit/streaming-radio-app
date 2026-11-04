'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const powershell = 'pwsh';
const sourceRoot = path.resolve(__dirname, '..');
const layoutRoot = path.join(__dirname, 'emulator-host-layout');

function fixture(t, prefix) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const log = path.join(root, 'commands.log');
  const uid = path.join(root, 'uid.txt');
  const state = path.join(root, 'adb-state.log');
  fs.writeFileSync(uid, process.env.TEST_ADBD_UID || '2000');
  const adbScript = path.join(root, 'fake-adb.ps1');
  const adb = path.join(root, process.platform === 'win32' ? 'fake-adb.cmd' : 'fake-adb');
  fs.writeFileSync(adbScript, `
$ErrorActionPreference = 'Stop'
$argv = @($args)
$line = $argv -join ' '
Add-Content -LiteralPath $env:TEST_ADB_LOG -Value $line
$command = if ($argv[0] -eq '-s') { $argv[2..($argv.Length - 1)] -join ' ' } else { $line }
$deviceRows = @("emulator-5580\tdevice")
if ($env:TEST_ADB_DEVICES) { $deviceRows = @($env:TEST_ADB_DEVICES -split '\\|') }
$targetSerial = if ($argv[0] -eq '-s') { $argv[1] } else {
  $ready = @($deviceRows | Where-Object { $_ -match '\tdevice$' })
  if ($ready.Count -eq 1) { ($ready[0] -split '\t')[0] } else { '' }
}
$stateFile = if ($env:TEST_ADB_STATE_FILE) { $env:TEST_ADB_STATE_FILE } else { Join-Path (Split-Path $env:TEST_ADB_LOG) 'adb-state.log' }
$uidFile = $env:TEST_ADB_UID_FILE
$uid = [int](Get-Content -LiteralPath $uidFile -Raw)
if ($command -eq 'shell getprop ro.kernel.qemu') {
  if ($env:TEST_ADB_QEMU) { $env:TEST_ADB_QEMU } elseif ($targetSerial -match '^emulator-\\d+$') { '1' } else { '0' }
  exit 0
}
if ($command -eq 'shell am get-current-user') { if ($env:TEST_ADB_USER) { $env:TEST_ADB_USER } else { '10' }; exit 0 }
if ($command -eq 'shell id -u') { "$uid"; exit 0 }
if ($argv[0] -eq 'devices') {
  "List of devices attached"
  foreach ($row in $deviceRows) { Add-Content -LiteralPath $env:TEST_ADB_LOG -Value "device-row $row"; $row }
  exit 0
}
if ($command -eq 'root') { Set-Content -LiteralPath $uidFile -Value '0'; 'restarting adbd as root'; exit 0 }
if ($command -eq 'unroot') { Set-Content -LiteralPath $uidFile -Value '2000'; 'restarting adbd as non root'; exit 0 }
if ($command -eq 'shell pm path com.eyyit.emulator.mediacontrols') { 'package:/data/app/base.apk'; exit 0 }
if ($command -eq 'remount') { if ($env:TEST_ADB_FAIL -eq 'remount') { 'remount failed'; exit 1 }; 'remount succeeded'; exit 0 }
if ($command -like 'shell cmd overlay fabricate*' -and $env:TEST_ADB_FAIL -eq 'fabricate') { 'fabricate failed'; exit 1 }
if ($command -like 'install -*') {
  if ($env:TEST_ADB_FAIL -eq 'install') { 'install failed'; exit 1 }
  Add-Content -LiteralPath $stateFile -Value "installed $targetSerial $command"
  exit 0
}
if ($command -like 'shell am start*') {
  if ($env:TEST_ADB_FAIL -eq 'start') { 'start failed'; exit 1 }
  Add-Content -LiteralPath $stateFile -Value "started $targetSerial $command"
  exit 0
}
if ($command -eq 'shell cmd overlay lookup --user 10 com.android.car.media com.android.car.media:bool/switch_to_playback_view_when_playable_item_is_clicked') { 'false'; exit 0 }
if ($command -eq 'shell cmd overlay dump com.eyyit.emulator.mediacontrols') { 'STATE_ENABLED'; exit 0 }
if ($command -eq 'shell getprop sys.boot_completed') { '1'; exit 0 }
exit 0
`);
  if (process.platform === 'win32') {
    fs.writeFileSync(adb, '@echo off\r\npwsh.exe -NoProfile -NonInteractive -File "%~dp0fake-adb.ps1" %*\r\nexit /b %errorlevel%\r\n');
  } else {
    fs.writeFileSync(adb, '#!/bin/sh\nexec pwsh -NoProfile -NonInteractive -File "$(dirname "$0")/fake-adb.ps1" "$@"\n');
    fs.chmodSync(adb, 0o755);
  }
  return { root, log, uid, adb, state };
}

function runScript(script, env, args = []) {
  return spawnSync(powershell, [
    '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script, ...args
  ], { encoding: 'utf8', env: { ...process.env, ...env } });
}

function commandLog(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim().split(/\r?\n/) : [];
}

function installFixture(t, adbExit = '0') {
  const f = fixture(t, 'radio-install-');
  const root = path.join(f.root, 'repo');
  const tools = path.join(root, 'tools');
  fs.mkdirSync(tools, { recursive: true });
  let script = fs.readFileSync(path.join(sourceRoot, 'tools/install.ps1'), 'utf8');
  script = script.replaceAll('"$root\\gradlew.bat"', '"$root\\gradlew.ps1"');
  script = script.replace("'C:\\Android\\Sdk\\platform-tools\\adb.exe'", `'${f.adb}'`);
  const scriptPath = path.join(tools, 'install.ps1');
  fs.writeFileSync(scriptPath, script);
  fs.writeFileSync(path.join(root, 'gradlew.ps1'), `
Add-Content -LiteralPath $env:TEST_GRADLE_LOG -Value ($args -join ' ')
$global:LASTEXITCODE = [int]$env:TEST_GRADLE_EXIT
`);
  const stale = path.join(root, 'app/build/outputs/apk/debug/stale.apk');
  const phone = path.join(root, 'app/build/outputs/apk/phone/debug/app-phone-debug.apk');
  fs.mkdirSync(path.dirname(stale), { recursive: true });
  fs.mkdirSync(path.dirname(phone), { recursive: true });
  fs.writeFileSync(stale, 'stale');
  fs.writeFileSync(phone, 'phone');
  return { ...f, scriptPath, stale, phone, adbExit };
}

test('install selects the phone flavor output instead of a stale unflavored APK', t => {
  const f = installFixture(t);
  const result = runScript(f.scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_GRADLE_LOG: path.join(f.root, 'gradle.log'),
    TEST_ADB_UID_FILE: f.uid,
    TEST_ADB_FAIL: ''
  });
  const calls = commandLog(f.log);
  assert.ok(calls.some(x => x.includes(f.phone)), `${calls.join('\n')}\n${result.stdout}\n${result.stderr}`);
  assert.ok(calls.includes('-s emulator-5580 shell getprop ro.kernel.qemu'), calls.join('\n'));
  assert.ok(calls.includes('-s emulator-5580 shell am get-current-user'), calls.join('\n'));
  assert.ok(calls.some(x => x.includes('-s emulator-5580 install --user 10 -r -d ')), calls.join('\n'));
  assert.ok(calls.some(x => x.includes('-s emulator-5580 shell am start --user 10 -n ')), calls.join('\n'));
  assert.ok(calls.some(x => x.includes('net.mspanc.twinsenradio/net.mspanc.twinsenradio.ui.MainActivity')),
    calls.join('\n'));
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('build failure stops before device installation', t => {
  const f = installFixture(t);
  const result = runScript(f.scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_GRADLE_LOG: path.join(f.root, 'gradle.log'),
    TEST_GRADLE_EXIT: '1',
    TEST_ADB_UID_FILE: f.uid
  });
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
  assert.equal(commandLog(f.log).length, 0);
});

test('release selection requires signing values and does not fall back to unsigned output', t => {
  const f = installFixture(t);
  const gradleLog = path.join(f.root, 'gradle.log');
  const result = runScript(f.scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_GRADLE_LOG: gradleLog,
    TEST_ADB_UID_FILE: f.uid,
    STREAMING_RADIO_KEYSTORE: '',
    STREAMING_RADIO_STORE_PASSWORD: '',
    STREAMING_RADIO_KEY_ALIAS: '',
    STREAMING_RADIO_KEY_PASSWORD: ''
  }, ['-Release']);
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
  assert.ok(result.stderr.includes('requires all four STREAMING_RADIO signing variables'));
  assert.equal(fs.existsSync(gradleLog), false);
  assert.equal(commandLog(f.log).length, 0);
});

test('install failure does not launch the package', t => {
  const f = installFixture(t);
  const result = runScript(f.scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_GRADLE_LOG: path.join(f.root, 'gradle.log'),
    TEST_ADB_UID_FILE: f.uid,
    TEST_ADB_FAIL: 'install'
  });
  const calls = commandLog(f.log);
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
  assert.ok(calls.some(x => x.includes('-s emulator-5580 install --user 10 -r -d ')), calls.join('\n'));
  assert.ok(!calls.some(x => x.includes('shell am start')), calls.join('\n'));
});

test('automotive install uses its Gradle flavor, APK path, and package component', t => {
  const f = installFixture(t);
  const automotive = path.join(f.root, 'repo/app/build/outputs/apk/automotive/debug/app-automotive-debug.apk');
  fs.mkdirSync(path.dirname(automotive), { recursive: true });
  fs.writeFileSync(automotive, 'automotive');
  const gradleLog = path.join(f.root, 'gradle.log');
  const result = runScript(f.scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_GRADLE_LOG: gradleLog,
    TEST_ADB_UID_FILE: f.uid,
    TEST_ADB_FAIL: ''
  }, ['-Automotive']);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const calls = commandLog(f.log);
  assert.match(fs.readFileSync(gradleLog, 'utf8'), /:app:testAutomotiveDebugUnitTest/);
  assert.match(fs.readFileSync(gradleLog, 'utf8'), /:app:assembleAutomotiveDebug/);
  assert.ok(calls.some(x => x.includes(automotive)), calls.join('\n'));
  assert.ok(calls.some(x => x.includes('com.impostorlabs.streamingradio.debug/net.mspanc.twinsenradio.automotive.ImpostorPlayerActivity')),
    calls.join('\n'));
});

test('physical-only adb devices are never installed or launched', t => {
  const f = installFixture(t);
  const result = runScript(f.scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_ADB_STATE_FILE: f.state,
    TEST_ADB_DEVICES: 'USB123\tdevice',
    TEST_GRADLE_LOG: path.join(f.root, 'gradle.log'),
    TEST_ADB_UID_FILE: f.uid
  });
  const calls = commandLog(f.log);
  assert.ok(calls.includes('device-row USB123\tdevice'), calls.join('\n'));
  const mutations = fs.existsSync(f.state)
    ? fs.readFileSync(f.state, 'utf8').trim().split(/\r?\n/)
    : [];
  assert.deepEqual(mutations, [], 'a physical target must not receive install or launch');
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
});

test('unverified emulator or non-Driver user is never mutated', t => {
  for (const override of [{ TEST_ADB_QEMU: '0' }, { TEST_ADB_USER: '0' }]) {
    const f = installFixture(t);
    const result = runScript(f.scriptPath, {
      TEST_ADB_LOG: f.log,
      TEST_ADB_STATE_FILE: f.state,
      TEST_ADB_DEVICES: 'emulator-5580\tdevice',
      TEST_GRADLE_LOG: path.join(f.root, 'gradle.log'),
      TEST_ADB_UID_FILE: f.uid,
      ...override
    });
    const mutations = fs.existsSync(f.state)
      ? fs.readFileSync(f.state, 'utf8').trim().split(/\r?\n/)
      : [];
    assert.deepEqual(mutations, [], 'an unverified target must not receive install or launch');
    assert.notEqual(result.status, 0, result.stdout + result.stderr);
  }
});

test('pinned-player failure restores an initially unrooted adbd', t => {
  const f = fixture(t, 'radio-pinned-');
  const scriptPath = path.join(layoutRoot, 'Set-AAOS-pinned-player.ps1');



  const result = runScript(scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_ADB_UID_FILE: f.uid,
    TEST_ADB_FAIL: 'fabricate'
  }, ['-AdbPath', f.adb]);
  const calls = commandLog(f.log);
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
  assert.ok(calls.some(x => x.includes('shell cmd overlay fabricate')), calls.join('\n'));
  assert.ok(calls.some(x => x.endsWith(' root')), `${calls.join('\n')}\n${result.stdout}\n${result.stderr}`);
  assert.ok(calls.some(x => x.endsWith(' unroot')), `${calls.join('\n')}\n${result.stdout}\n${result.stderr}`);
  assert.equal(fs.readFileSync(f.uid, 'utf8').trim(), '2000');
  assert.ok(calls.some(x => x.endsWith(' shell id -u')), calls.join('\n'));
});

test('pinned-player preserves an adbd that was already root', t => {
  const f = fixture(t, 'radio-pinned-root-');
  fs.writeFileSync(f.uid, '0');
  const scriptPath = path.join(layoutRoot, 'Set-AAOS-pinned-player.ps1');



  const result = runScript(scriptPath, { TEST_ADB_LOG: f.log, TEST_ADB_UID_FILE: f.uid }, ['-AdbPath', f.adb]);
  const calls = commandLog(f.log);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.ok(calls.some(x => x.includes('shell cmd overlay fabricate')), calls.join('\n'));
  assert.ok(!calls.some(x => x.endsWith(' unroot')), calls.join('\n'));
  assert.equal(fs.readFileSync(f.uid, 'utf8').trim(), '0');
});

test('wide-controls remount failure restores the original adbd UID', t => {
  const f = fixture(t, 'radio-wide-');
  const scriptPath = path.join(layoutRoot, 'Set-AAOS-wide-controls.ps1');



  fs.writeFileSync(path.join(f.root, 'AAOS-emulator-wide-controls.apk'), 'fixture overlay');
  const result = runScript(scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_ADB_UID_FILE: f.uid,
    TEST_ADB_FAIL: 'remount'
  }, ['-AdbPath', f.adb, '-OverlayApkPath', path.join(f.root, 'AAOS-emulator-wide-controls.apk')]);
  const calls = commandLog(f.log);
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
  assert.ok(calls.some(x => x.endsWith(' remount')), `${calls.join('\n')}\n${result.stdout}\n${result.stderr}`);
  assert.ok(calls.some(x => x.endsWith(' root')), `${calls.join('\n')}\n${result.stdout}\n${result.stderr}`);
  assert.ok(calls.some(x => x.endsWith(' unroot')), `${calls.join('\n')}\n${result.stdout}\n${result.stderr}`);
  assert.equal(fs.readFileSync(f.uid, 'utf8').trim(), '2000');
  assert.ok(calls.some(x => x.endsWith(' shell id -u')), calls.join('\n'));
});

test('wide-controls leaves an initially rooted adbd rooted', t => {
  const f = fixture(t, 'radio-wide-root-');
  fs.writeFileSync(f.uid, '0');
  const scriptPath = path.join(layoutRoot, 'Set-AAOS-wide-controls.ps1');



  fs.writeFileSync(path.join(f.root, 'AAOS-emulator-wide-controls.apk'), 'fixture overlay');
  const result = runScript(scriptPath, {
    TEST_ADB_LOG: f.log,
    TEST_ADB_UID_FILE: f.uid,
    TEST_ADB_FAIL: 'remount'
  }, ['-AdbPath', f.adb, '-OverlayApkPath', path.join(f.root, 'AAOS-emulator-wide-controls.apk')]);
  const calls = commandLog(f.log);
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
  assert.ok(calls.some(x => x.endsWith(' remount')), `${calls.join('\n')}\n${result.stdout}\n${result.stderr}`);
  assert.ok(!calls.some(x => x.endsWith(' unroot')), calls.join('\n'));
  assert.equal(fs.readFileSync(f.uid, 'utf8').trim(), '0');
});
