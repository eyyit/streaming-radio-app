'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { VerificationError, cleanChildEnv, inspectPrerequisites, main, writeReceipt } = require('./verify-automotive.cjs');
function fake() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'radio-sdk-'));
  const sdk = path.join(root, 'sdk');
  const aapt = process.platform === 'win32' ? 'aapt2.exe' : 'aapt2';
  const sdkmanager = process.platform === 'win32' ? 'sdkmanager.bat' : 'sdkmanager';
  const files = ['platforms/android-36/android.jar',
    path.join('build-tools', '35.0.0', aapt),
    path.join('cmdline-tools', 'latest', 'bin', sdkmanager)];
  for (const name of files) {
    const file = path.join(sdk, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, '');
    if (name.includes(aapt) && process.platform !== 'win32') fs.chmodSync(file, 0o755);
  }
  fs.mkdirSync(path.join(root, 'gradle/wrapper'), { recursive: true });
  fs.writeFileSync(path.join(root, 'gradle/wrapper/gradle-wrapper.jar'), '');
  return { root, sdk, sdkmanager };
}
function check(root, sdk, version = '17.0.12') {
  return inspectPrerequisites(root, { ANDROID_SDK_ROOT: sdk }, { platform: process.platform,
    runPowerShellVersion: () => ({ status: 0, stdout: '7' }),
    runJavaVersion: () => ({ status: 0, stdout: '', stderr: `openjdk version "${version}"` }) });
}

test('reports missing, failing or obsolete PowerShell before tool tests', () => {
  const { root, sdk } = fake();
  try {
    for (const result of [{ status: null, error: new Error('ENOENT') }, { status: 1 }, { status: 0, stdout: '5' }, { status: 0, stdout: 'invalid' }])
      assert.throws(() => inspectPrerequisites(root, { ANDROID_SDK_ROOT: sdk }, {
        runJavaVersion: () => ({ status: 0, stderr: 'openjdk version "17.0.12"' }),
        runPowerShellVersion: () => result }), e => e.code === 'POWERSHELL_7_REQUIRED');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
test('reports missing sdkmanager before command-not-found exit 127', () => {
  const { root, sdk } = fake(); fs.rmSync(path.join(sdk, 'cmdline-tools'), { recursive: true });
  try { assert.throws(() => check(root, sdk), e => e.code === 'ANDROID_SDKMANAGER_MISSING'); }
  finally { fs.rmSync(root, { recursive: true, force: true }); }
});
test('finds a numbered cmdline-tools SDK manager', () => {
  const { root, sdk } = fake();
  const latest = path.join(sdk, 'cmdline-tools/latest');
  const numbered = path.join(sdk, 'cmdline-tools/14/bin',
    process.platform === 'win32' ? 'sdkmanager.bat' : 'sdkmanager');
  fs.rmSync(latest, { recursive: true }); fs.mkdirSync(path.dirname(numbered), { recursive: true });
  fs.writeFileSync(numbered, '');
  try { assert.equal(check(root, sdk).sdkManager, numbered); }
  finally { fs.rmSync(root, { recursive: true, force: true }); }
});
test('identifies missing Android 36 platform and build tools separately', () => {
  const a = fake(); fs.rmSync(path.join(a.sdk, 'platforms'), { recursive: true });
  try { assert.throws(() => check(a.root, a.sdk), /ANDROID_PLATFORM_36_MISSING/); }
  finally { fs.rmSync(a.root, { recursive: true, force: true }); }
  const b = fake(); fs.rmSync(path.join(b.sdk, 'build-tools'), { recursive: true });
  try { assert.throws(() => check(b.root, b.sdk), /ANDROID_BUILD_TOOLS_35_MISSING/); }
  finally { fs.rmSync(b.root, { recursive: true, force: true }); }
});
test('rejects Java versions other than 17', () => {
  const { root, sdk } = fake();
  try { assert.throws(() => check(root, sdk, '21.0.1'), e => e.code === 'JAVA_17_REQUIRED'); }
  finally { fs.rmSync(root, { recursive: true, force: true }); }
});
test('accepts complete SDK fixtures with Java 17', () => {
  const { root, sdk } = fake();
  try { assert.equal(check(root, sdk).sdk, sdk); }
  finally { fs.rmSync(root, { recursive: true, force: true }); }
});
test('strips only signing values from copied child environment', () => {
  const parent = { PATH: '/tools', JAVA_HOME: '/jdk', STREAMING_RADIO_KEYSTORE: '/key', STREAMING_RADIO_STORE_FILE: '/legacy-key',
    STREAMING_RADIO_STORE_PASSWORD: 'secret', STREAMING_RADIO_KEY_ALIAS: 'alias',
    STREAMING_RADIO_KEY_PASSWORD: 'secret',
    STREAMING_RADIO_STORE_PASSWORD_FILE: '/run/secrets/store-password',
    STREAMING_RADIO_KEY_PASSWORD_FILE: '/run/secrets/key-password' }, child = cleanChildEnv(parent);
  assert.equal(child.PATH, '/tools'); assert.equal(child.JAVA_HOME, '/jdk');
  for (const key of Object.keys(parent).filter(x => x.startsWith('STREAMING_RADIO_')))
    assert.equal(key in child, false);
  assert.equal(parent.STREAMING_RADIO_KEYSTORE, '/key');
  assert.equal(parent.STREAMING_RADIO_STORE_PASSWORD, 'secret');
});

test('writes parseable receipt JSON with a real trailing newline on preflight failure', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'radio-receipt-'));
  try {
    assert.equal(main(['--preflight'], { root, env: {} }), 2);
    const file = path.join(root, 'app/build/reports/verification/verification.json');
    const raw = fs.readFileSync(file, 'utf8');
    assert.ok(raw.endsWith('\n'));
    const receipt = JSON.parse(raw);
    assert.equal(receipt.status, 'preflight-failed');
    assert.deepEqual(receipt.stages.map(x => [x.name, x.status]), [['prerequisites', 'failed']]);
    assert.equal(receipt.stages[0].error, 'ANDROID_SDK_ENV_MISSING');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('receipt writer preserves parseable JSON and newline termination', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'radio-receipt-writer-'));
  try {
    writeReceipt(root, { status: 'preflight-passed', stages: [] });
    const raw = fs.readFileSync(path.join(root,
      'app/build/reports/verification/verification.json'), 'utf8');
    assert.ok(raw.endsWith('\n'));
    assert.equal(JSON.parse(raw).status, 'preflight-passed');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});


test('shared verification runs phone protocol tests and propagates their failure', () => {
  const { toolTests } = require('./verify-automotive.cjs');
  const childEnv = { ...process.env }; delete childEnv.NODE_TEST_CONTEXT;
  assert.equal(typeof toolTests, 'function', 'shared protocol verification must be callable');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'radio-protocol-gate-'));
  try {
    const tools = path.join(root, 'tools'); fs.mkdirSync(tools);
    for (const file of ['check-dependencies.test.cjs', 'check-source-tree.test.cjs',
      'verify-automotive.test.cjs', 'install-and-root-state.test.cjs',
      'export-public-source.test.cjs', 'docker-build.test.cjs'])
      fs.writeFileSync(path.join(tools, file), '');
    const protocol = path.join(root, 'phone-transfer/tests/protocol.test.mjs');
    fs.mkdirSync(path.dirname(protocol), { recursive: true });
    const app = path.join(root, 'phone-transfer/tests/app.test.mjs');
    fs.writeFileSync(app, '');
    fs.writeFileSync(protocol, "import assert from 'node:assert/strict'; assert.fail('protocol regression fixture');\n");
    assert.throws(() => toolTests(root, childEnv), e => e.code === 'TOOL_TESTS_FAILED');
    fs.writeFileSync(protocol, "import assert from 'node:assert/strict'; assert.equal(1, 1);\n");
    assert.doesNotThrow(() => toolTests(root, childEnv));
    fs.writeFileSync(app, "import assert from 'node:assert/strict'; assert.fail('multi-tab regression fixture');\n");
    assert.throws(() => toolTests(root, childEnv), e => e.code === 'TOOL_TESTS_FAILED');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
