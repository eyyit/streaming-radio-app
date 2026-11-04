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
    fs.writeFileSync(path.join(destination[1], 'artifact.aab'), 'fixture');
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
