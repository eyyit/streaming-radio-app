'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { inspectSourceTree, listWorkingSources } = require('./check-source-tree.cjs');

function temporaryDirectory(t, prefix) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}

function writeSource(root, name) {
  const file = path.join(root, ...name.split('/'));
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `// ${name}\n`);
}

function createRepo(t, {
  expected,
  indexed = expected,
  working = expected,
  manifest
}) {
  const root = temporaryDirectory(t, 'source-tree-guard-');
  const initial = new Set([...indexed, ...working]);
  for (const name of initial) writeSource(root, name);

  const init = spawnSync('git', ['-C', root, 'init', '--quiet'], { encoding: 'utf8' });
  assert.equal(init.status, 0, init.stderr);
  if (indexed.length) {
    const add = spawnSync('git', ['-C', root, 'add', '--', ...indexed], { encoding: 'utf8' });
    assert.equal(add.status, 0, add.stderr);
  }
  for (const name of initial) {
    if (!working.includes(name)) fs.unlinkSync(path.join(root, ...name.split('/')));
  }

  const entries = expected.map(name => ({ path: name, size: 10, sha256: '0'.repeat(64) }));
  const contents = manifest ?? { formatVersion: 1, includedFiles: entries };
  fs.writeFileSync(path.join(root, 'SOURCE-MANIFEST.json'), JSON.stringify(contents));
  return root;
}

const currentSource = 'app/src/main/java/example/CurrentFeature.kt';

test('accepts a clean 177-file prepared inventory', t => {
  const expected = Array.from({ length: 177 }, (_, index) =>
    `app/src/main/java/example/Feature${String(index + 1).padStart(3, '0')}.kt`);
  const root = createRepo(t, { expected });
  const result = inspectSourceTree(root);
  assert.equal(result.ok, true);
  assert.equal(result.count, 177);
  assert.equal(result.indexed.length, 177);
  assert.equal(result.working.length, 177);
});

test('accepts a general clean source set and ignores source content changes', t => {
  const expected = [
    currentSource,
    'app/src/automotive/java/example/Theme.kt',
    'app/src/test/java/example/FeatureTest.kt'
  ];
  const root = createRepo(t, { expected });
  fs.writeFileSync(path.join(root, ...currentSource.split('/')), '// changed content\n');
  const result = inspectSourceTree(root);
  assert.equal(result.ok, true);
  assert.equal(result.count, 3);
});

test('rejects the stale upstream R357 test that is absent from the prepared manifest', t => {
  const stale = 'app/src/test/java/net/mspanc/twinsenradio/playback/R357NowPlayingTest.kt';
  const root = createRepo(t, {
    expected: [currentSource],
    indexed: [currentSource, stale],
    working: [currentSource, stale]
  });
  const result = inspectSourceTree(root);
  assert.equal(result.ok, false);
  assert.deepEqual(result.unexpectedIndexed, [stale]);
  assert.deepEqual(result.unexpectedWorking, [stale]);
});

test('rejects a prepared source file missing from the Git index', t => {
  const expected = [currentSource, 'app/src/test/java/example/FeatureTest.kt'];
  const missing = expected[1];
  const root = createRepo(t, { expected, indexed: [currentSource], working: expected });
  const result = inspectSourceTree(root);
  assert.equal(result.ok, false);
  assert.deepEqual(result.missingIndexed, [missing]);
  assert.equal(result.missingWorking.includes(missing), false);
});

test('rejects an indexed prepared source file deleted from the working tree', t => {
  const missing = 'app/src/test/java/example/DeletedTest.kt';
  const root = createRepo(t, { expected: [currentSource, missing], working: [currentSource] });
  const result = inspectSourceTree(root);
  assert.equal(result.ok, false);
  assert.deepEqual(result.missingWorking, [missing]);
  assert.equal(result.missingIndexed.includes(missing), false);
});

test('rejects an untracked app source instead of silently ignoring it', t => {
  const untracked = 'app/src/main/java/example/UntrackedFeature.kt';
  const root = createRepo(t, { expected: [currentSource], working: [currentSource, untracked] });
  const result = inspectSourceTree(root);
  assert.equal(result.ok, false);
  assert.deepEqual(result.unexpectedWorking, [untracked]);
  assert.equal(result.unexpectedIndexed.includes(untracked), false);
});

test('requires a readable valid source manifest', t => {
  const root = createRepo(t, { expected: [currentSource] });
  const manifest = path.join(root, 'SOURCE-MANIFEST.json');
  fs.unlinkSync(manifest);
  assert.throws(() => inspectSourceTree(root), /SOURCE_MANIFEST_UNREADABLE/);
  fs.writeFileSync(manifest, '{not json');
  assert.throws(() => inspectSourceTree(root), /SOURCE_MANIFEST_INVALID_JSON/);
  fs.writeFileSync(manifest, JSON.stringify({ formatVersion: 2, includedFiles: [] }));
  assert.throws(() => inspectSourceTree(root), /SOURCE_MANIFEST_INVALID/);
});

test('does not follow an out-of-root symlink while scanning app sources', t => {
  const root = createRepo(t, { expected: [currentSource] });
  const outside = temporaryDirectory(t, 'source-tree-outside-');
  const outsideFile = path.join(outside, 'must-not-be-scanned.kt');
  fs.writeFileSync(outsideFile, '// outside the repository\n');
  const link = path.join(root, 'app', 'src', 'linked-outside');
  try {
    fs.symlinkSync(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
  } catch (error) {
    if (['EPERM', 'EACCES', 'ENOTSUP', 'UNKNOWN'].includes(error.code)) {
      t.skip(`directory symlinks are unavailable: ${error.code}`);
      return;
    }
    throw error;
  }

  const scan = listWorkingSources(root);
  assert.equal(scan.unsafePaths.has('app/src/linked-outside'), true);
  assert.equal([...scan.files].some(name => name.includes('must-not-be-scanned')), false);
  assert.equal(inspectSourceTree(root).ok, false);
});
