'use strict';

const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const sourceRepo = path.resolve(__dirname, '..');
const linuxOnly = process.platform === 'win32'
  ? 'requires Bash and Unix file permissions'
  : false;

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'docker build tests '));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));

  const repo = path.join(root, 'fake repo');
  fs.mkdirSync(path.join(repo, 'tools'), { recursive: true });
  fs.mkdirSync(path.join(repo, 'docker'), { recursive: true });
  fs.copyFileSync(path.join(sourceRepo, 'tools', 'docker-build.sh'),
    path.join(repo, 'tools', 'docker-build.sh'));
  for (const name of ['Dockerfile', 'entrypoint.sh']) {
    fs.copyFileSync(path.join(sourceRepo, 'docker', name),
      path.join(repo, 'docker', name));
  }
  fs.chmodSync(path.join(repo, 'tools', 'docker-build.sh'), 0o700);

  const bin = path.join(root, 'fake bin');
  fs.mkdirSync(bin);
  const ledger = path.join(root, 'docker ledger.json');
  const fakeDocker = path.join(bin, 'docker');
  fs.writeFileSync(fakeDocker, `#!${process.execPath}\n${String.raw`
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
const record = { args };
if (args[0] === 'build') {
  const context = args.at(-1);
  record.contextEntries = fs.readdirSync(context).sort();
}
fs.writeFileSync(process.env.DOCKER_LEDGER, JSON.stringify(record));
if (args[0] === 'run') {
  const output = args.find((arg) => arg.startsWith('type=bind,src=') &&
    arg.includes(',dst=/out'));
  const destination = output && output.match(/src=(.*),dst=\/out/);
  if (destination) {
    const out = destination[1];
    fs.writeFileSync(path.join(out, 'artifact.aab'), 'fixture');
    if (process.env.DOCKER_FAIL === '1' && args.includes('MODE=verify')) {
      const reports = path.join(out, 'app/build/reports/verification');
      const tests = path.join(out, 'app/build/test-results/unitTest');
      fs.mkdirSync(reports, { recursive: true });
      fs.mkdirSync(tests, { recursive: true });
      fs.writeFileSync(path.join(out, 'SOURCE-MANIFEST.json'), 'manifest');
      fs.writeFileSync(path.join(reports, 'verification.json'), 'failed receipt');
      fs.writeFileSync(path.join(tests, 'results.xml'), '<testsuite/>');
    }
  }
}
if (process.env.DOCKER_FAIL === '1') process.exit(37);
`}`);
  fs.chmodSync(fakeDocker, 0o700);

  return { root, repo, bin, ledger };
}

function invoke(f, mode, extraEnv = [], args = []) {
  const result = spawnSync('bash', [path.join(f.repo, 'tools', 'docker-build.sh'),
    mode, ...args], {
    cwd: f.repo,
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${f.bin}${path.delimiter}${process.env.PATH}`,
      DOCKER_LEDGER: f.ledger,
      STREAMING_RADIO_PRIVATE_DIR: path.join(f.root, 'private signing'),
      HOME: f.root,
      ...extraEnv,
    },
  });
  return result;
}

function readLedger(f) {
  return JSON.parse(fs.readFileSync(f.ledger, 'utf8'));
}

function randomCanary() {
  return crypto.randomBytes(32).toString('hex');
}

function signingFiles(f, dir = path.join(f.root, 'private signing')) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  fs.chmodSync(dir, 0o700);
  const key = path.join(dir, 'upload.p12');
  const password = path.join(dir, 'upload-password');
  fs.writeFileSync(key, crypto.randomBytes(64), { mode: 0o600 });
  fs.writeFileSync(password, randomCanary(), { mode: 0o600 });
  return { dir, key, password, value: fs.readFileSync(password, 'utf8') };
}

test('unsigned run uses readonly source and cache/output mounts without signing secrets',
  { skip: linuxOnly }, (t) => {
    const f = fixture(t);
    const result = invoke(f, 'bundle');
    assert.equal(result.status, 0, result.stderr);
    const args = readLedger(f).args;
    assert.ok(args.some((arg) => arg.includes(`src=${f.repo},dst=/src,readonly`)));
    assert.ok(args.some((arg) => arg.includes('dst=/gradle-cache')));
    assert.ok(args.some((arg) => arg.includes('dst=/out')));
    assert.ok(!args.some((arg) => arg.includes('/run/secrets/')));
    assert.ok(!args.some((arg) => arg.includes('PASSWORD')));
    assert.ok(!args.some((arg) => arg.includes('KEYSTORE')));
  });

test('signed run mounts protected external files readonly and never passes values',
  { skip: linuxOnly }, (t) => {
    const f = fixture(t);
    const signing = signingFiles(f);
    const result = invoke(f, 'signed-bundle');
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.statSync(signing.dir).mode & 0o777, 0o700);
    assert.equal(fs.statSync(signing.key).mode & 0o777, 0o600);
    assert.equal(fs.statSync(signing.password).mode & 0o777, 0o600);
    const args = readLedger(f).args;
    assert.ok(args.some((arg) => arg.includes(
      `src=${signing.key},dst=/run/secrets/upload.p12,readonly`)));
    assert.ok(args.some((arg) => arg.includes(
      `src=${signing.password},dst=/run/secrets/upload-password,readonly`)));
    assert.ok(!args.includes(signing.value));
    assert.ok(!args.some((arg) => arg.includes(signing.value)));
  });

test('weak permissions and symlinked signing files are rejected before Docker',
  { skip: linuxOnly }, (t) => {
    for (const [name, change] of [
      ['weak keystore mode', (files) => fs.chmodSync(files.key, 0o644)],
      ['weak password mode', (files) => fs.chmodSync(files.password, 0o644)],
      ['keystore symlink', (files) => {
        fs.unlinkSync(files.key);
        fs.symlinkSync(files.password, files.key);
      }],
      ['password symlink', (files) => {
        fs.unlinkSync(files.password);
        fs.symlinkSync(files.key, files.password);
      }],
    ]) {
      const f = fixture(t);
      change(signingFiles(f));
      assert.notEqual(invoke(f, 'signed-bundle').status, 0, name);
      assert.equal(fs.existsSync(f.ledger), false, name);
    }
  });

test('image build context contains only Dockerfile and entrypoint',
  { skip: linuxOnly }, (t) => {
    const f = fixture(t);
    const canary = randomCanary();
    fs.writeFileSync(path.join(f.repo, 'docker', 'user-input-canary'), canary);
    const archive = path.join(f.root, 'external licenses archive.tar.gz');
    fs.writeFileSync(archive, crypto.randomBytes(128));
    const result = invoke(f, 'image', { ANDROID_LICENSES_TAR: archive });
    assert.equal(result.status, 0, result.stderr);
    const ledger = readLedger(f);
    assert.deepEqual(ledger.contextEntries, ['Dockerfile', 'entrypoint.sh']);
    assert.ok(!JSON.stringify(ledger).includes(canary));
    assert.ok(!fs.readdirSync(path.join(f.repo, 'docker')).some((name) =>
      name.includes('licenses archive')));
  });

test('Docker failure propagates and removes pending output', { skip: linuxOnly }, (t) => {
  const f = fixture(t);
  const result = invoke(f, 'bundle', { DOCKER_FAIL: '1' });
  assert.equal(result.status, 37);
  assert.ok(!result.stdout.includes('Build artifacts saved under'));
  assert.deepEqual(fs.readdirSync(path.join(f.repo, 'dist', 'docker')), []);
});


test('failed verification retains partial evidence and its exit status',
  { skip: linuxOnly },
  (t) => {
    const f = fixture(t);
    const result = invoke(f, 'verify', { DOCKER_FAIL: '1' });
    assert.equal(result.status, 37);
    assert.ok(!result.stdout.includes('Build artifacts saved under'));
    const output = path.join(f.repo, 'dist', 'docker');
    const entries = fs.readdirSync(output);
    assert.equal(
      entries.length,
      1,
      'failed verification must keep its evidence directory',
    );
    assert.match(entries[0], /^failed-verify-/);
    assert.match(result.stderr, /Verification failed; evidence retained under/);
    const evidence = path.join(output, entries[0]);
    assert.equal(
      fs.readFileSync(path.join(evidence, 'SOURCE-MANIFEST.json'), 'utf8'),
      'manifest',
    );
    assert.equal(
      fs.readFileSync(
        path.join(evidence, 'app/build/reports/verification/verification.json'),
        'utf8',
      ),
      'failed receipt',
    );
    assert.equal(
      fs.readFileSync(
        path.join(evidence, 'app/build/test-results/unitTest/results.xml'),
        'utf8',
      ),
      '<testsuite/>',
    );
  },
);

test('setup-signing preserves password spaces without echoing them',
  { skip: linuxOnly, timeout: 40000 }, (t) => {
    const f = fixture(t);
    const key = path.join(f.root, 'creds', 'streaming_radio',
      'streaming-radio-upload.p12');
    fs.mkdirSync(path.dirname(key), { recursive: true });
    const password = `  ${randomCanary()}  `;
    fs.writeFileSync(key, crypto.randomBytes(64), { mode: 0o600 });
    const harness = String.raw`
import json, os, pty, select, signal, sys, termios, time
p = json.load(sys.stdin)
pid, fd = pty.fork()
if pid == 0:
  env = os.environ.copy()
  env['STREAMING_RADIO_PRIVATE_DIR'] = p['private']
  env['HOME'] = p['home']
  env.pop('STREAMING_RADIO_KEY_SOURCE', None)
  os.execvpe('bash', ['bash', p['script'], 'setup-signing'], env)
buf = b''
try:
  for prompt in (b'Upload key password: ', b'Confirm password: '):
    deadline = time.time() + 30
    while prompt not in buf:
      remaining = deadline - time.time()
      ready, _, _ = select.select([fd], [], [], max(remaining, 0))
      if not ready:
        raise RuntimeError('password prompt timeout')
      part = os.read(fd, 4096)
      if not part:
        raise RuntimeError('signing helper exited before both prompts')
      buf += part
    deadline = time.time() + 2
    while termios.tcgetattr(fd)[3] & termios.ECHO:
      if time.time() >= deadline:
        raise RuntimeError('password echo was not disabled')
      time.sleep(0.01)
    os.write(fd, p['password'].encode() + b'\n')
  _, status = os.waitpid(pid, 0)
  while True:
    ready, _, _ = select.select([fd], [], [], 0.1)
    if not ready:
      break
    try:
      part = os.read(fd, 4096)
    except OSError:
      break
    if not part:
      break
    buf += part
  print(json.dumps({'status': os.waitstatus_to_exitcode(status),
                    'output': buf.decode(errors='replace')}))
except BaseException:
  os.kill(pid, signal.SIGKILL)
  os.waitpid(pid, 0)
  raise
`;
    const result = spawnSync('python3', ['-c', harness], {
      encoding: 'utf8',
      input: JSON.stringify({
        key,
        home: f.root,
        password,
        private: path.join(f.root, 'private signing'),
        script: path.join(f.repo, 'tools', 'docker-build.sh'),
      }),
      timeout: 35000,
    });
    assert.equal(result.status, 0, result.stderr || result.error);
    const interaction = JSON.parse(result.stdout.trim());
    assert.equal(interaction.status, 0, interaction.output);
    assert.ok(!interaction.output.includes(password), 'password was echoed');
    const signing = path.join(f.root, 'private signing');
    assert.equal(fs.readFileSync(path.join(signing, 'upload-password'), 'utf8'),
      password);
    assert.equal(fs.statSync(path.join(signing, 'upload-password')).mode & 0o777,
      0o600);
  });

test('noninteractive signed run with no credentials gives setup instructions',
  { skip: linuxOnly }, (t) => {
    const f = fixture(t);
    const result = invoke(f, 'signed-bundle');
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /run tools\/docker-build\.sh setup-signing interactively first/);
    assert.equal(fs.existsSync(f.ledger), false);
  });
test('comma paths fail before Docker is invoked', { skip: linuxOnly }, (t) => {
  const repoCase = fixture(t);
  const commaRepo = path.join(repoCase.root, 'fake,repo');
  fs.renameSync(repoCase.repo, commaRepo);
  repoCase.repo = commaRepo;
  let result = invoke(repoCase, 'bundle');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /cannot contain commas/i);
  assert.equal(fs.existsSync(repoCase.ledger), false);
  const privateCase = fixture(t);
  const signing = signingFiles(privateCase,
    path.join(privateCase.root, 'private,signing'));
  result = invoke(privateCase, 'signed-bundle', {
    STREAMING_RADIO_PRIVATE_DIR: signing.dir,
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /cannot contain commas/i);
  assert.equal(fs.existsSync(privateCase.ledger), false);
  const licenseCase = fixture(t);
  const archive = path.join(licenseCase.root, 'licenses,archive.tar.gz');
  fs.writeFileSync(archive, crypto.randomBytes(128));
  result = invoke(licenseCase, 'image', { ANDROID_LICENSES_TAR: archive });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /cannot contain commas/i);
  assert.equal(fs.existsSync(licenseCase.ledger), false);
});
test(
  'verify entrypoint exports phone bundle, test evidence, and source identity',
  { skip: linuxOnly },
  (t) => {
    const f = fixture(t);
    const source = path.join(f.root, 'source');
    const output = path.join(f.root, 'out');
    const work = path.join(f.root, 'work');
    const bin = path.join(f.root, 'entrypoint bin');
    fs.mkdirSync(source);
    fs.mkdirSync(output);
    fs.mkdirSync(bin);
    fs.writeFileSync(
      path.join(source, 'SOURCE-MANIFEST.json'),
      '{"gitHead":null}\n',
    );
    fs.writeFileSync(
      path.join(source, 'SOURCE-MANIFEST.sha256'),
      'fixture  SOURCE-MANIFEST.json\n',
    );
    const node = path.join(bin, 'node');
    fs.writeFileSync(node, `#!/bin/sh
set -eu
case "$1" in
  *export-public-source.cjs)
    mkdir -p "$3/app/src" "$3/tools"
    cp "$STREAMING_RADIO_SOURCE_DIR"/SOURCE-MANIFEST* "$3/"
    cat > "$3/gradlew" <<'GRADLE'
#!/bin/sh
set -eu
case " $* " in
  *" :app:assembleAutomotiveDebug "*) ;;
  *) printf 'unexpected Gradle task: %s\n' "$*" >&2; exit 51 ;;
esac
mkdir -p app/build/outputs/apk/automotive/debug
printf 'apk' > app/build/outputs/apk/automotive/debug/app-automotive-debug.apk
GRADLE
    chmod +x "$3/gradlew"
    ;;
  *verify-automotive.cjs)
    mkdir -p app/build/test-results/unitTest
    mkdir -p app/build/reports/lint app/build/reports/verification
    printf '<testsuite/>' > app/build/test-results/unitTest/results.xml
    if [ "$FAIL_VERIFY" = 1 ]; then
      mkdir -p app/build/outputs/bundle/automotiveRelease
      printf 'partial bundle' > app/build/outputs/bundle/automotiveRelease/app-automotive-release.aab
      printf '{"status":"failed"}\n' > app/build/reports/verification/verification.json
      exit 42
    fi
    mkdir -p app/build/outputs/bundle/automotiveRelease
    mkdir -p app/build/outputs/bundle/phoneRelease
    printf 'automotive' > app/build/outputs/bundle/automotiveRelease/app-automotive-release.aab
    if [ "$OMIT_PHONE_BUNDLE" != 1 ]; then
      printf 'phone' > app/build/outputs/bundle/phoneRelease/app-phone-release.aab
    fi
    printf 'lint' > app/build/reports/lint/lint.html
    printf '{"status":"passed"}\n' > app/build/reports/verification/verification.json
    ;;
esac
`);
    fs.chmodSync(node, 0o700);
    const runVerify = (omitPhoneBundle, failVerifier = false) => spawnSync(
      'sh',
      [path.join(f.repo, 'docker', 'entrypoint.sh')],
      {
        cwd: f.root,
        encoding: 'utf8',
        env: {
          ...process.env,
          PATH: `${bin}${path.delimiter}${process.env.PATH}`,
          MODE: 'verify',
          SOURCE_ROOT: source,
          OUTPUT_ROOT: output,
          WORK_ROOT: work,
          HOME_TMP_DIR: path.join(f.root, 'home-tmp'),
          GRADLE_CACHE_DIR: path.join(f.root, 'gradle-cache'),
          STREAMING_RADIO_SOURCE_DIR: source,
          ANDROID_USER_HOME: path.join(f.root, 'android-user'),
          ANDROID_HOME: path.join(f.root, 'android-sdk'),
          OMIT_PHONE_BUNDLE: omitPhoneBundle ? '1' : '0',
          FAIL_VERIFY: failVerifier ? '1' : '0',
        },
      },
    );
    const result = runVerify(false);
    assert.equal(result.status, 0, result.stderr);
    const phoneBundle = path.join(output, 'Streaming-Radio-phone-unsigned.aab');
    assert.ok(
      fs.existsSync(phoneBundle),
      'verify must export the phone release bundle',
    );
    assert.equal(fs.readFileSync(phoneBundle, 'utf8'), 'phone');
    const sumsPath = path.join(output, 'SHA256SUMS');
    const checksumLines = fs.readFileSync(sumsPath, 'utf8').trim().split('\n');
    assert.equal(checksumLines.length, 3);
    for (const line of checksumLines) {
      const [expected, name] = line.split(/\s+/, 2);
      const actual = crypto.createHash('sha256')
        .update(fs.readFileSync(path.join(output, name))).digest('hex');
      assert.equal(expected, actual, name);
    }
    assert.equal(
      fs.readFileSync(path.join(output, 'SOURCE-MANIFEST.json'), 'utf8'),
      '{"gitHead":null}\n',
    );
    assert.equal(
      fs.readFileSync(path.join(output, 'SOURCE-MANIFEST.sha256'), 'utf8'),
      'fixture  SOURCE-MANIFEST.json\n',
    );
    assert.equal(
      fs.readFileSync(
        path.join(output, 'app/build/test-results/unitTest/results.xml'),
        'utf8',
      ),
      '<testsuite/>',
    );
    assert.equal(
      fs.readFileSync(path.join(output, 'app/build/reports/lint/lint.html'), 'utf8'),
      'lint',
    );
    assert.equal(
      fs.readFileSync(
        path.join(output, 'app/build/reports/verification/verification.json'),
        'utf8',
      ),
      '{"status":"passed"}\n',
    );
    fs.rmSync(work, { recursive: true, force: true });
    fs.rmSync(output, { recursive: true, force: true });
    fs.mkdirSync(output);
    const missingPhone = runVerify(true);
    assert.notEqual(missingPhone.status, 0);
    assert.match(
      missingPhone.stderr,
      /phone release bundle was not produced/,
    );
    fs.rmSync(work, { recursive: true, force: true });
    fs.rmSync(output, { recursive: true, force: true });
    fs.mkdirSync(output);
    const failedVerifier = runVerify(false, true);
    assert.equal(failedVerifier.status, 42, failedVerifier.stderr);
    assert.ok(
      fs.existsSync(path.join(output, 'SOURCE-MANIFEST.json')),
      'failed verify must retain source identity',
    );
    assert.ok(
      fs.existsSync(
        path.join(output, 'app/build/reports/verification/verification.json'),
      ),
      'failed verify must retain its partial receipt',
    );
    assert.ok(
      fs.existsSync(
        path.join(output, 'app/build/test-results/unitTest/results.xml'),
      ),
      'failed verify must retain available test output',
    );
    assert.equal(
      fs.readFileSync(path.join(output, 'SOURCE-MANIFEST.json'), 'utf8'),
      '{"gitHead":null}\n',
    );
    assert.equal(
      fs.readFileSync(path.join(output, 'SOURCE-MANIFEST.sha256'), 'utf8'),
      'fixture  SOURCE-MANIFEST.json\n',
    );
    assert.equal(
      fs.readFileSync(
        path.join(output, 'app/build/reports/verification/verification.json'),
        'utf8',
      ),
      '{"status":"failed"}\n',
    );
    assert.equal(
      fs.readFileSync(
        path.join(output, 'app/build/test-results/unitTest/results.xml'),
        'utf8',
      ),
      '<testsuite/>',
    );
    const partialBundle = path.join(
      output,
      'app/build/outputs/bundle/automotiveRelease/app-automotive-release.aab',
    );
    assert.ok(
      fs.existsSync(partialBundle),
      'failed verify must retain an available bundle',
    );
    assert.equal(fs.readFileSync(partialBundle, 'utf8'), 'partial bundle');
    for (const name of [
      'Streaming-Radio-unsigned.aab',
      'Streaming-Radio-phone-unsigned.aab',
      'Streaming-Radio-play-upload.aab',
      'SHA256SUMS',
    ]) {
      assert.equal(
        fs.existsSync(path.join(output, name)),
        false,
        'failed verification must not expose success-only outputs',
      );
    }
    fs.rmSync(work, { recursive: true, force: true });
    fs.rmSync(output, { recursive: true, force: true });
    fs.mkdirSync(output);
    fs.writeFileSync(path.join(output, 'app'), 'blocks evidence destination');
    const copyFailure = runVerify(false);
    assert.notEqual(
      copyFailure.status,
      0,
      'successful build must fail if required report copying is obstructed',
    );
    assert.match(copyFailure.stderr, /Could not retain verification evidence/);
    assert.ok(
      fs.existsSync(path.join(output, 'SOURCE-MANIFEST.json')),
      'manifest copy should succeed before the report obstruction',
    );
    for (const name of [
      'Streaming-Radio-unsigned.aab',
      'Streaming-Radio-phone-unsigned.aab',
    ]) {
      assert.equal(
        fs.existsSync(path.join(output, name)),
        false,
        'copy failure must not expose success-named bundles',
      );
    }
  },
);
test('Android CI retains both phone and automotive release bundles', () => {
  const workflow = fs.readFileSync(
    path.join(sourceRepo, '.github', 'workflows', 'android-ci.yml'),
    'utf8',
  );
  assert.match(workflow, /sha256sum[^>]*app\/build\/outputs\/bundle\/automotiveRelease\/\*\.aab/);
  assert.match(workflow, /sha256sum[^>]*app\/build\/outputs\/bundle\/phoneRelease\/\*\.aab/);
  const digest = workflow.indexOf('Write candidate SHA-256 report');
  assert.ok(workflow.indexOf('Run shared local and CI verification') < digest &&
    digest < workflow.indexOf('Retain review evidence'));
  assert.match(workflow, /sha256sum[\s\S]*candidate-sha256\.txt/);
  assert.match(
    workflow,
    /name: Retain review evidence[\s\S]*app\/build\/reports\//,
  );
});
