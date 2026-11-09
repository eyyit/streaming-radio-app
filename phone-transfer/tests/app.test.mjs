import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import vm from 'node:vm';

const appPath = process.env.APP_FILE || new URL('../public/app.mjs', import.meta.url);
const source = readFileSync(appPath, 'utf8')
  .split(/\r?\n/)
  .filter(line => !line.startsWith('import '))
  .join('\n');
const storageKey = 'streaming-radio-phone-transfer-v1';
const ids = ['1'.repeat(64), '2'.repeat(64), '3'.repeat(64)];
const secrets = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)];
const pause = () => new Promise(resolve => setImmediate(resolve));

class FakeLocks {
  owner = null;
  request(tab, _name, options, callback) {
    return Promise.resolve().then(() => {
      if (options.ifAvailable && this.owner !== null) return callback(null);
      this.owner = tab;
      let result;
      try { result = callback({name: 'fake-lock'}); }
      catch (error) { this.owner = null; throw error; }
      return Promise.resolve(result).finally(() => {
        if (this.owner === tab) this.owner = null;
      });
    });
  }
  destroyTab(tab) { if (this.owner === tab) this.owner = null; }
}

function harness() {
  const storage = new Map();
  const locks = new FakeLocks();
  function boot(index, options = {}) {
    const elements = new Map();
    const authWaiters = [];
    let finishTransfer;
    const transferReady = new Promise(resolve => { finishTransfer = resolve; });
    let databaseCalls = 0;
    let reads = 0;
    let writes = 0;
    let failClaim = Boolean(options.failClaim);
    const localStorage = {
      getItem(key) { reads += 1; return storage.get(key) ?? null; },
      setItem(key, value) { writes += 1; storage.set(key, value); },
    };
    const document = {
      getElementById(id) {
        if (!elements.has(id)) {
          const listeners = new Map();
          elements.set(id, {
            hidden: false, textContent: '', value: '', disabled: false,
            files: [], listeners, classList: {toggle() {}},
            addEventListener(type, fn) { listeners.set(type, fn); }, click() {},
          });
        }
        return elements.get(id);
      },
    };
    const sandbox = {
      console,
      rawConfig: {enabled: true, apiKey: 'x', databaseUrl: 'https://example.firebaseio.com'},
      Date, URL, URLSearchParams, TextDecoder, localStorage, document,
      location: {hash: `#s=${ids[index]}&k=${secrets[index]}`, pathname: '/transfer', search: ''},
      history: {replaceState() {}}, setTimeout() {},
      navigator: options.unsupported ? {} : {
        locks: {request: (name, config, fn) => locks.request(index, name, config, fn)},
      },
      configuration: value => value,
      pairing(fragment) {
        const params = new URLSearchParams(fragment.slice(1));
        return {id: params.get('s'), secret: params.get('k')};
      },
      jsonRequest() { return new Promise((resolve, reject) => authWaiters.push({resolve, reject})); },
      async databaseRequest(_config, _token, path) {
        databaseCalls += 1;
        if (path.startsWith('claims/') && failClaim) {
          failClaim = false;
          throw new Error('simulated claims write failure');
        }
        if (path.startsWith('transfers/')) {
          finishTransfer({status: 'waiting', expiresAt: Date.now() + 300000});
          return {status: 'waiting', expiresAt: Date.now() + 300000};
        }
        return null;
      },
      async removePair() {}, LIMIT: 1048576, payload() {},
    };
    vm.runInNewContext(source, sandbox, {filename: `tab-${index}-app.mjs`});
    return {
      id: index, elements, authWaiters, transferReady, localStorage,
      get reads() { return reads; }, get writes() { return writes; },
      get databaseCalls() { return databaseCalls; },
    };
  }
  return {storage, locks, boot};
}

async function until(predicate, message) {
  for (let i = 0; i < 20; i += 1) {
    if (predicate()) return;
    await pause();
  }
  assert.fail(message);
}

function resolveAuth(tab, index) {
  assert.equal(tab.authWaiters.length, 1);
  tab.authWaiters[0].resolve({idToken: `token-${index}`, localId: `user-${index}`, refreshToken: `refresh-${index}`});
}

test('second tab is denied before storage and signup while first is starting', async () => {
  const h = harness();
  const first = h.boot(0);
  await until(() => first.authWaiters.length === 1, 'first tab did not reach signup');
  const second = h.boot(1);
  await pause();
  assert.equal(second.authWaiters.length, 0, 'blocked tab must not authenticate');
  assert.equal(second.reads, 0, 'blocked tab must not read storage');
  assert.equal(second.writes, 0, 'blocked tab must not write storage');
  assert.equal(second.databaseCalls, 0, 'blocked tab must not make database requests');
  assert.equal(second.elements.get('form').hidden, true);
  resolveAuth(first, 0);
  await first.transferReady;
});

test('lock remains owned after startup finishes', async () => {
  const h = harness();
  const first = h.boot(0);
  await until(() => first.authWaiters.length === 1, 'first tab did not reach signup');
  resolveAuth(first, 0);
  await first.transferReady;
  const second = h.boot(1);
  await pause();
  assert.equal(second.authWaiters.length, 0);
  assert.equal(second.reads, 0);
  assert.equal(second.databaseCalls, 0);
  assert.equal(h.locks.owner, 0);
});

test('startup failure keeps lock through retry cleanup', async () => {
  const h = harness();
  const first = h.boot(0, {failClaim: true});
  await until(() => first.authWaiters.length === 1, 'first tab did not reach signup');
  resolveAuth(first, 0);
  await until(() => first.elements.get('cancel')?.textContent === 'Retry cleanup', 'retry cleanup was not shown');
  assert.equal(first.elements.get('cancel').textContent, 'Retry cleanup');
  const second = h.boot(1);
  await pause();
  assert.equal(second.authWaiters.length, 0, 'cleanup retry must keep tab lock');
  assert.equal(second.reads, 0);
  assert.equal(second.writes, 0);
  assert.equal(second.databaseCalls, 0);
  await first.elements.get('cancel').listeners.get('click')();
  assert.equal(h.locks.owner, 0, 'cleanup completion does not release the tab lock');
});

test('unsupported browsers fail closed before storage and authentication', async () => {
  const h = harness();
  const tab = h.boot(0, {unsupported: true});
  await pause();
  assert.equal(tab.reads, 0);
  assert.equal(tab.writes, 0);
  assert.equal(tab.databaseCalls, 0);
  assert.equal(tab.authWaiters.length, 0);
  assert.equal(tab.elements.get('form').hidden, true);
  assert.match(tab.elements.get('status').textContent, /cannot safely manage/i);
});

test('tab destruction releases lock and next tab preserves old cleanup records', async () => {
  const h = harness();
  const oldId = 'f'.repeat(64);
  h.storage.set(storageKey, JSON.stringify({refreshToken: 'existing-refresh', pending: [{id: oldId, expiresAt: Date.now() + 300000}]}));
  const first = h.boot(0);
  await until(() => first.authWaiters.length === 1, 'first tab did not reach token refresh');
  h.locks.destroyTab(0);
  const second = h.boot(1);
  await until(() => second.authWaiters.length === 1, 'new tab did not reach token refresh');
  resolveAuth(second, 1);
  await second.transferReady;
  const saved = JSON.parse(h.storage.get(storageKey));
  assert.deepEqual(saved.pending.map(item => item.id).sort(), [oldId, ids[1]].sort());
});
