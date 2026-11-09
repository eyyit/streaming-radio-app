import test from 'node:test';
import assert from 'node:assert/strict';
import {pairing, payload, configuration, jsonRequest, LIMIT} from '../public/protocol.mjs';
test('pairing requires separate 256-bit hexadecimal capabilities', () => {
  assert.deepEqual(pairing(`#s=${'a'.repeat(64)}&k=${'b'.repeat(64)}`), {id: 'a'.repeat(64), secret: 'b'.repeat(64)});
  for (const fragment of ['', '#s=x&k=y', `#s=${'a'.repeat(64)}`]) assert.throws(() => pairing(fragment));
});
test('enforces URL, name, UTF-8 byte and file catalog boundaries', () => {
  const station = 'http://example.org/live\n';
  assert.equal(payload('file', station + 'x'.repeat(LIMIT - station.length)).value.length, LIMIT);
  assert.equal(payload('file', station + 'é'.repeat(Math.floor((LIMIT - station.length) / 2))).kind, 'file');
  for (const args of [['file', 'é'.repeat(LIMIT / 2 + 1)], ['file', ''],
    ['url', 'javascript:alert(1)'], ['url', 'https://x/' + 'a'.repeat(4096)],
    ['file', '#EXTM3U\n#EXT-X-TARGETDURATION:6'], ['url', 'https://x', 'a'.repeat(513)]]) {
    assert.throws(() => payload(...args));
  }
  assert.equal(payload('url', 'https://example.org/a.m3u', '日本語').name, '日本語');
});
test('matches car HTTPS URL policy and canonical URI encoding; files need a station line', () => {
  const credentials = new URL('https://example.org/list.m3u');
  credentials.username = 'fixture';
  credentials.password = ['not', 'a', 'secret'].join('-');
  assert.throws(() => payload('url', new URL('/list.m3u', 'http:' + '//example.org').href));
  assert.throws(() => payload('url', credentials.href));
  assert.equal(payload('url', 'https://münich.example/a playlist.m3u').value,
    'https://xn--mnich-kva.example/a%20playlist.m3u');
  assert.throws(() => payload('url', 'https://example.org/' + 'a '.repeat(1400) + 'b'));
  assert.throws(() => payload('file', '#EXTM3U\n# no stations'));
  assert.throws(() => payload('file', 'not an absolute station URL'));
  assert.equal(payload('file', '#EXTM3U\nhttp://example.org/live').kind, 'file');
});
test('configuration defaults fail closed and cannot target another host', () => {
  assert.throws(() => configuration({enabled: false}));
  const base = {enabled: true, apiKey: 'a'.repeat(30)};
  const userInfo = new URL('https://x.firebaseio.com');
  userInfo.username = 'fixture';
  for (const databaseUrl of ['http://x.firebaseio.com', 'https://evil.org', 'https://x.firebaseio.com.evil.org', userInfo.href, 'https://x.firebaseio.com/path']) {
    assert.throws(() => configuration({...base, databaseUrl}));
  }
  assert.equal(configuration({...base, databaseUrl: 'https://x-default-rtdb.europe-west1.firebasedatabase.app'}).enabled, true);
});
test('REST prohibits redirects, credentials, cache and referrer; network failure propagates', async () => {
  await jsonRequest('https://example.invalid', {}, async (_url, options) => {
    assert.equal(options.redirect, 'error'); assert.equal(options.credentials, 'omit');
    assert.equal(options.cache, 'no-store'); assert.equal(options.referrerPolicy, 'no-referrer');
    return {ok: true, json: async () => null};
  });
  await assert.rejects(jsonRequest('https://example.invalid', {}, async () => ({ok: false})));
  await assert.rejects(jsonRequest('https://example.invalid', {}, async () => { throw new Error('offline'); }));
});
