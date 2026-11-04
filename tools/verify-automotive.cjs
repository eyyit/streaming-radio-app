'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const ROOT = path.resolve(__dirname, '..');
const TASKS = [':app:assembleAutomotiveDebugAndroidTest', ':app:testAutomotiveDebugUnitTest', ':app:testAutomotiveReleaseUnitTest',
  ':app:testPhoneDebugUnitTest', ':app:testPhoneReleaseUnitTest', ':app:lintPhoneRelease',
  ':app:lintAutomotiveRelease', ':app:bundleAutomotiveRelease',
  ':app:writeAutomotiveReleaseInventory'];
const SIGNING = ['STREAMING_RADIO_KEYSTORE', 'STREAMING_RADIO_STORE_FILE', 'STREAMING_RADIO_STORE_PASSWORD',
  'STREAMING_RADIO_KEY_ALIAS', 'STREAMING_RADIO_KEY_PASSWORD'];
class VerificationError extends Error { constructor(code) { super(code); this.code = code; } }
function locateSdkManager(sdk, platform, env = process.env) {
  if (env.SDKMANAGER && fs.existsSync(env.SDKMANAGER)) return env.SDKMANAGER;
  const pathList = (env.PATH || '').split(path.delimiter);
  for (const folder of pathList) {
    const candidate = path.join(folder, platform === 'win32' ? 'sdkmanager.bat' : 'sdkmanager');
    if (fs.existsSync(candidate)) return candidate;
  }
  const dir = path.join(sdk, 'cmdline-tools');
  let versions = [];
  try { versions = fs.readdirSync(dir).filter(x => x === 'latest' || /^\d+$/.test(x)); } catch {}
  versions.sort((a, b) => a === 'latest' ? -1 : b === 'latest' ? 1 : Number(b) - Number(a));
  for (const version of versions) {
    const names = platform === 'win32' ? ['sdkmanager.bat', 'sdkmanager.cmd', 'sdkmanager'] : ['sdkmanager'];
    for (const name of names) {
      const candidate = path.join(dir, version, 'bin', name);
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  throw new VerificationError('ANDROID_SDKMANAGER_MISSING');
}
function inspectPrerequisites(root = ROOT, env = process.env, options = {}) {
  const platform = options.platform || process.platform;
  const sdk = env.ANDROID_SDK_ROOT || env.ANDROID_HOME;
  if (!sdk) throw new VerificationError('ANDROID_SDK_ENV_MISSING');
  if (!fs.existsSync(path.join(sdk, 'platforms/android-36/android.jar')))
    throw new VerificationError('ANDROID_PLATFORM_36_MISSING');
  const aapt = path.join(sdk, 'build-tools/35.0.0',
    platform === 'win32' ? 'aapt2.exe' : 'aapt2');
  if (!fs.existsSync(aapt) || (platform !== 'win32' &&
    (fs.statSync(aapt).mode & 0o111) === 0))
    throw new VerificationError('ANDROID_BUILD_TOOLS_35_MISSING');
  const sdkManager = locateSdkManager(sdk, platform, env);
  const wrapper = path.join(root, 'gradle/wrapper/gradle-wrapper.jar');
  if (!fs.existsSync(wrapper)) throw new VerificationError('GRADLE_WRAPPER_JAR_MISSING');
  const java = env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin',
    platform === 'win32' ? 'java.exe' : 'java') : 'java';
  if (env.JAVA_HOME && !fs.existsSync(java)) throw new VerificationError('JAVA_VERSION_UNAVAILABLE');
  const runVersion = options.runJavaVersion || (exe => spawnSync(exe, ['-version'],
    { encoding: 'utf8', windowsHide: true }));
  const result = runVersion(java);
  const versionText = [result.stdout, result.stderr].filter(Boolean).join('\n');
  const version = versionText.split('version "')[1]?.split('"')[0]?.split('.')[0];
  if (result.error || result.status !== 0 || !version)
    throw new VerificationError('JAVA_VERSION_UNAVAILABLE');
  if (Number(version) !== 17) throw new VerificationError('JAVA_17_REQUIRED');
  const powershell = platform === 'win32' ? 'pwsh.exe' : 'pwsh';
  const ps = (options.runPowerShellVersion || (exe => spawnSync(exe,
    ['-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.Major'],
    { encoding: 'utf8', windowsHide: true, timeout: 10000, env: cleanChildEnv(env) })))(powershell);
  const major = Number(ps.stdout?.trim());
  if (ps.error || ps.status !== 0 || !Number.isInteger(major) || major < 7)
    throw new VerificationError('POWERSHELL_7_REQUIRED');
  return { java, sdk, sdkManager, wrapper, powershell };
}
function cleanChildEnv(env) {
  const child = { ...env };
  for (const key of SIGNING) delete child[key];
  return child;
}
function run(command, args, root, env, code) {
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit', windowsHide: true });
  if (result.error || result.status !== 0) throw new VerificationError(code);
}
function sourceCheck(root, env) {
  run(process.execPath, [path.join(root, 'tools/check-source-tree.cjs')], root, env,
    'SOURCE_TREE_CHECK_FAILED');
}
function toolTests(root, env) {
  run(process.execPath, ['--test', ...['check-dependencies.test.cjs',
    'check-source-tree.test.cjs', 'verify-automotive.test.cjs',
    'install-and-root-state.test.cjs'].map(x => path.join(root, 'tools', x))],
  root, env, 'TOOL_TESTS_FAILED');
}
function writeReceipt(root, data) {
  const file = path.join(root, 'app/build/reports/verification/verification.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
}
function errorMessage(code) {
  const messages = {
    ANDROID_SDK_ENV_MISSING: 'Set ANDROID_SDK_ROOT or ANDROID_HOME to an installed Android SDK.',
    ANDROID_SDKMANAGER_MISSING: 'Set SDKMANAGER or install Android command-line tools under cmdline-tools.',
    ANDROID_PLATFORM_36_MISSING: 'Install Android SDK platform 36 with sdkmanager.',
    ANDROID_BUILD_TOOLS_35_MISSING: 'Install Android build-tools 35.0.0 with sdkmanager.',
    GRADLE_WRAPPER_JAR_MISSING: 'Restore gradle/wrapper/gradle-wrapper.jar from the repository.',
    JAVA_VERSION_UNAVAILABLE: 'Install Java 17 or set JAVA_HOME to a Java 17 installation.',
    JAVA_17_REQUIRED: 'Use Java 17; the detected Java version is unsupported.',
    POWERSHELL_7_REQUIRED: 'Install PowerShell 7 or newer and put pwsh on PATH for the isolated developer-script tests.'
  };
  return messages[code] || 'See the failed verification stage for details.';
}
function main(args = process.argv.slice(2), options = {}) {
  const root = path.resolve(options.root || ROOT), env = options.env || process.env;
  const preflight = args.length === 1 && args[0] === '--preflight';
  if (args.length && !preflight) {
    process.stderr.write('Usage: node tools/verify-automotive.cjs [--preflight]\n'); return 2;
  }
  const child = cleanChildEnv(env);
  const git = spawnSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8', env: child });
  const status = spawnSync('git', ['-C', root, 'status', '--porcelain'], { encoding: 'utf8', env: child });
  const receipt = { started: new Date().toISOString(),
    revision: git.status === 0 ? git.stdout.trim() : null,
    workingTree: status.status === 0 ? (status.stdout.trim() ? 'dirty' : 'clean') : 'unknown',
    signing: 'unsigned', stages: [] };
  const stage = (name, action) => {
    const entry = { name, status: 'running', started: new Date().toISOString() };
    receipt.stages.push(entry);
    try { action(); entry.status = 'passed'; }
    catch (e) { entry.status = 'failed'; entry.error = e.code || 'STAGE_FAILED'; throw e; }
    finally { entry.finished = new Date().toISOString(); writeReceipt(root, receipt); }
  };
  try {
    let prereq;
    stage('prerequisites', () => { prereq = inspectPrerequisites(root, env, options); });
    stage('source-tree', () => sourceCheck(root, env));
    stage('tool-tests', () => toolTests(root, env));
    if (preflight) {
      receipt.status = 'preflight-passed';
      receipt.finished = new Date().toISOString();
      writeReceipt(root, receipt);
      process.stdout.write('Preflight passed; Gradle and OSV were not run.\n');
      return 0;
    }
    stage('gradle', () => run(prereq.java, ['-classpath', prereq.wrapper,
      'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', '--parallel', ...TASKS],
    root, child, 'GRADLE_FAILED'));
    stage('osv', () => run(process.execPath, [path.join(root, 'tools/check-dependencies.cjs'),
      '--inventory', path.join(root, 'app/build/reports/release/runtime-components.json'),
      '--output', path.join(root, 'app/build/reports/release/dependency-security-scan.json')],
    root, child, 'OSV_FAILED'));
    receipt.status = 'passed'; receipt.finished = new Date().toISOString(); writeReceipt(root, receipt);
    process.stdout.write('Automotive verification passed; see app/build/reports/verification/verification.json.\n');
    return 0;
  } catch (error) {
    receipt.status = preflight ? 'preflight-failed' : 'failed';
    receipt.finished = new Date().toISOString();
    try { writeReceipt(root, receipt); } catch {}
    const code = error.code || 'VERIFICATION_FAILED';
    process.stderr.write(`Automotive verification stopped (${code}): ${errorMessage(code)}\n`);
    return preflight || receipt.stages.length < 4 ? 2 : 1;
  }
}
if (require.main === module) process.exitCode = main();
module.exports = { VerificationError, cleanChildEnv, inspectPrerequisites, locateSdkManager, main, writeReceipt };
