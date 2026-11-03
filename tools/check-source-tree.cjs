'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const SOURCE_PREFIX = 'app/src/';

function fail(code) {
  throw new Error(code);
}

function readExpectedSources(root) {
  const file = path.join(root, 'SOURCE-MANIFEST.json');
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch {
    fail('SOURCE_MANIFEST_UNREADABLE');
  }
  if (stat.isSymbolicLink() || !stat.isFile()) {
    fail('SOURCE_MANIFEST_UNREADABLE');
  }

  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    fail('SOURCE_MANIFEST_INVALID_JSON');
  }
  if (!manifest || Array.isArray(manifest) || manifest.formatVersion !== 1 ||
      !Array.isArray(manifest.includedFiles)) {
    fail('SOURCE_MANIFEST_INVALID');
  }

  const allPaths = new Set();
  const expected = new Set();
  for (const entry of manifest.includedFiles) {
    if (!entry || Array.isArray(entry) || typeof entry.path !== 'string') {
      fail('SOURCE_MANIFEST_INVALID');
    }
    const name = entry.path;
    if (!name || name.includes('\\') || path.posix.isAbsolute(name) ||
        path.posix.normalize(name) !== name || name.split('/').includes('..')) {
      fail('SOURCE_MANIFEST_INVALID_PATH');
    }
    if (allPaths.has(name)) fail('SOURCE_MANIFEST_DUPLICATE_PATH');
    allPaths.add(name);
    if (name.startsWith(SOURCE_PREFIX)) expected.add(name);
  }
  return expected;
}

function listIndexedSources(root, runGit = execFileSync) {
  const output = runGit('git', ['-C', root, 'ls-files', '-z', '--', 'app/src'], {
    encoding: 'utf8'
  });
  return new Set(output.split('\0').filter(Boolean));
}

function listWorkingSources(root) {
  const files = new Set();
  const unsafePaths = new Set();
  const scanErrors = new Set();
  const base = path.join(root, 'app', 'src');
  let current = root;

  for (const part of ['app', 'src']) {
    current = path.join(current, part);
    let stat;
    try {
      stat = fs.lstatSync(current);
    } catch (error) {
      if (error.code === 'ENOENT') scanErrors.add('app/src');
      else scanErrors.add(path.relative(root, current).split(path.sep).join('/'));
      return { files, unsafePaths, scanErrors };
    }
    if (stat.isSymbolicLink()) {
      unsafePaths.add(path.relative(root, current).split(path.sep).join('/'));
      return { files, unsafePaths, scanErrors };
    }
    if (!stat.isDirectory()) {
      scanErrors.add(path.relative(root, current).split(path.sep).join('/'));
      return { files, unsafePaths, scanErrors };
    }
  }

  function walk(directory) {
    let entries;
    try {
      entries = fs.readdirSync(directory, { withFileTypes: true });
    } catch {
      scanErrors.add(path.relative(root, directory).split(path.sep).join('/'));
      return;
    }
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const file = path.join(directory, entry.name);
      const name = path.relative(root, file).split(path.sep).join('/');
      let stat;
      try {
        stat = fs.lstatSync(file);
      } catch {
        scanErrors.add(name);
        continue;
      }
      if (stat.isSymbolicLink()) {
        unsafePaths.add(name);
        files.add(name);
      } else if (stat.isDirectory()) {
        walk(file);
      } else if (stat.isFile()) {
        files.add(name);
      } else {
        unsafePaths.add(name);
        files.add(name);
      }
    }
  }

  walk(base);
  return { files, unsafePaths, scanErrors };
}

function difference(left, right) {
  return [...left].filter(name => !right.has(name)).sort();
}

function inspectSourceTree(root = ROOT, runGit = execFileSync) {
  const absoluteRoot = fs.realpathSync(path.resolve(root));
  const expected = readExpectedSources(absoluteRoot);
  const indexed = listIndexedSources(absoluteRoot, runGit);
  const working = listWorkingSources(absoluteRoot);
  const unexpectedIndexed = difference(indexed, expected);
  const missingIndexed = difference(expected, indexed);
  const unexpectedWorking = difference(working.files, expected);
  const missingWorking = difference(expected, working.files);
  const unsafePaths = [...working.unsafePaths].sort();
  const scanErrors = [...working.scanErrors].sort();
  const ok = unexpectedIndexed.length === 0 && missingIndexed.length === 0 &&
    unexpectedWorking.length === 0 && missingWorking.length === 0 &&
    unsafePaths.length === 0 && scanErrors.length === 0;
  return {
    ok,
    count: expected.size,
    expected: [...expected].sort(),
    indexed: [...indexed].sort(),
    working: [...working.files].sort(),
    unexpectedIndexed,
    missingIndexed,
    unexpectedWorking,
    missingWorking,
    unsafePaths,
    scanErrors
  };
}

function main(root = ROOT) {
  let result;
  try {
    result = inspectSourceTree(root);
  } catch (error) {
    console.error(`Could not verify prepared app source tree: ${error.message}`);
    return 1;
  }

  if (result.ok) {
    console.log(`Prepared app source tree verified: ${result.count} files.`);
    return 0;
  }

  console.error('App source tree differs from the prepared source inventory.');
  for (const name of result.unexpectedIndexed) {
    console.error(`Unexpected indexed app source: ${name}`);
  }
  for (const name of result.missingIndexed) {
    console.error(`Missing indexed prepared source: ${name}`);
  }
  const indexed = new Set(result.indexed);
  for (const name of result.unexpectedWorking) {
    if (!indexed.has(name)) console.error(`Untracked app source: ${name}`);
  }
  for (const name of result.missingWorking) {
    console.error(`Missing prepared source in working tree: ${name}`);
  }
  for (const name of result.unsafePaths) {
    console.error(`Unsafe symlink or special source path: ${name}`);
  }
  for (const name of result.scanErrors) {
    console.error(`Could not scan app source path: ${name}`);
  }
  return 1;
}

if (require.main === module) process.exitCode = main();

module.exports = {
  inspectSourceTree,
  listIndexedSources,
  listWorkingSources,
  main,
  readExpectedSources
};
